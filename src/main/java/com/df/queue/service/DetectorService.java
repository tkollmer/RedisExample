package com.df.queue.service;

import com.df.queue.model.DetectedEntity;
import com.df.queue.model.SignalBlock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Sliding-window entity detector. Forwards detected entities to all queue instances
 * via SimForwardingService. Only active in sim mode.
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "sim")
public class DetectorService {

    private static final Logger log = LoggerFactory.getLogger(DetectorService.class);

    private final SimForwardingService forwardingService;
    private final @Lazy SignalGenerator signalGenerator;

    @Value("${detector.window-width-percent:10}")
    private int windowWidthPercent;

    @Value("${detector.overlap-percent}")
    private int overlapPercent;

    @Value("${detector.time-window-ms:1000}")
    private long timeWindowMs;

    @Value("${detector.detection-probability:100}")
    private int detectionProbability;

    private final Map<String, String> blockToEntity = new ConcurrentHashMap<>();
    private final Map<String, DetectedEntity> activeEntities = new ConcurrentHashMap<>();
    private double windowPosition = 0;

    private final ArrayBlockingQueue<DetectRequest> detectQueue = new ArrayBlockingQueue<>(32);
    private Thread drainThread;

    record DetectRequest(List<SignalBlock> blocks, long timestamp) {}

    public DetectorService(SimForwardingService forwardingService,
                           @Lazy SignalGenerator signalGenerator) {
        this.forwardingService = forwardingService;
        this.signalGenerator = signalGenerator;
    }

    @PostConstruct
    public void start() {
        drainThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    DetectRequest req = detectQueue.take();
                    detect(req.blocks(), req.timestamp());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Detector drain error: {}", e.getMessage());
                }
            }
        }, "detector-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    @PreDestroy
    public void shutdown() {
        if (drainThread != null) drainThread.interrupt();
    }

    public void offerDetect(List<SignalBlock> blocks, long now) {
        detectQueue.offer(new DetectRequest(blocks, now));
    }

    private int getEffectiveWindowWidth() {
        return (int) (signalGenerator.getMaxWidth() * windowWidthPercent / 100.0);
    }

    public void detect(List<SignalBlock> currentBlocks, long now) {
        int maxWidth = signalGenerator.getMaxWidth();
        int windowWidth = getEffectiveWindowWidth();
        if (windowWidth < 1) windowWidth = 1;

        double step = windowWidth * (1.0 - overlapPercent / 100.0);
        if (step < 1) step = 1;

        long timeThreshold = now - timeWindowMs;
        List<SignalBlock> inWindow = currentBlocks.stream()
                .filter(b -> b.getStartTime() >= timeThreshold)
                .toList();

        List<DetectedEntity> newEntities = new ArrayList<>();

        for (double pos = 0; pos + windowWidth <= maxWidth; pos += step) {
            double winStart = pos;
            double winEnd = pos + windowWidth;

            for (SignalBlock block : inWindow) {
                if (block.getWidthEnd() > winStart && block.getWidthStart() < winEnd) {
                    String existingEntityId = blockToEntity.get(block.getId());

                    if (existingEntityId == null) {
                        if (detectionProbability < 100 &&
                                ThreadLocalRandom.current().nextInt(100) >= detectionProbability) {
                            continue;
                        }
                        DetectedEntity entity = new DetectedEntity(block, now);
                        blockToEntity.put(block.getId(), entity.getEntityId());
                        activeEntities.put(entity.getEntityId(), entity);
                        newEntities.add(entity);
                    }
                }
            }
        }

        // Forward detected entities to all queue instances
        if (!newEntities.isEmpty()) {
            try {
                forwardingService.forwardDetectedEntities(newEntities);
            } catch (Exception e) {
                log.warn("Entity forward failed: {}", e.getMessage());
            }
        }

        windowPosition += step;
        if (windowPosition + windowWidth > maxWidth) {
            windowPosition = 0;
        }

        // Forward detector state to all queue instances
        forwardingService.forwardDetector(Map.of(
                "position", windowPosition,
                "width", windowWidth,
                "timeWindowMs", timeWindowMs
        ));

        // Clean up expired block-to-entity mappings
        Set<String> endedBlocks = new HashSet<>();
        blockToEntity.forEach((blockId, entityId) -> {
            DetectedEntity entity = activeEntities.get(entityId);
            if (entity != null && entity.getEndTime() < now) {
                endedBlocks.add(blockId);
                activeEntities.remove(entityId);
            }
        });
        endedBlocks.forEach(blockToEntity::remove);

        // Forward entity count to all queue instances
        forwardingService.forwardEntityCount(activeEntities.values());
    }

    public double getWindowPosition() { return windowPosition; }

    public int getWindowWidthPercent() { return windowWidthPercent; }
    public void setWindowWidthPercent(int v) { this.windowWidthPercent = Math.max(1, Math.min(100, v)); }

    public int getOverlapPercent() { return overlapPercent; }
    public void setOverlapPercent(int v) { this.overlapPercent = Math.max(0, Math.min(90, v)); }

    public long getTimeWindowMs() { return timeWindowMs; }
    public void setTimeWindowMs(long v) { this.timeWindowMs = Math.max(100, Math.min(30000, v)); }

    public int getDetectionProbability() { return detectionProbability; }
    public void setDetectionProbability(int v) { this.detectionProbability = Math.max(0, Math.min(100, v)); }
}
