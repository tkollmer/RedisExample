package com.df.queue.service;

import com.df.queue.model.DetectedEntity;
import com.df.queue.model.SignalBlock;
import com.df.queue.web.SignalWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Sliding-window entity detector that scans signal blocks across the frequency axis.
 *
 * <p>The detector window slides from 0 to maxWidth with configurable overlap,
 * checking each position for intersecting signal blocks. New blocks that haven't
 * been previously detected are promoted to {@link DetectedEntity} objects and
 * published to Redis via {@link QueueService#publishBatch}.
 *
 * <h3>Decoupled Architecture</h3>
 * <p>Detection is decoupled from the tick thread via an {@link ArrayBlockingQueue}.
 * The signal generator calls {@link #offerDetect} (non-blocking), and a dedicated
 * drain thread processes detection asynchronously. This prevents slow Redis operations
 * from stalling the tick scheduler.
 */
@Service
public class DetectorService {

    private static final Logger log = LoggerFactory.getLogger(DetectorService.class);

    private final QueueService queueService;
    private final SignalWebSocketHandler wsHandler;
    private final SignalGenerator signalGenerator;

    @Value("${detector.window-width-percent:10}")
    private int windowWidthPercent;

    @Value("${detector.overlap-percent}")
    private int overlapPercent;

    @Value("${detector.time-window-ms:1000}")
    private long timeWindowMs;

    @Value("${detector.detection-probability:100}")
    private int detectionProbability;

    /** Maps signal block ID -> entity ID to avoid duplicate detections. */
    private final Map<String, String> blockToEntity = new ConcurrentHashMap<>();

    /** Active (non-expired) entities tracked by the detector. */
    private final Map<String, DetectedEntity> activeEntities = new ConcurrentHashMap<>();

    /** Current position of the sliding detector window along the frequency axis. */
    private double windowPosition = 0;

    /**
     * Bounded handoff queue between tick thread (producer) and detect thread (consumer).
     * Capacity of 32 allows burst absorption; if full, tick drops the request (no stall).
     */
    private final ArrayBlockingQueue<DetectRequest> detectQueue = new ArrayBlockingQueue<>(32);

    private Thread drainThread;

    /** Immutable request object for the detect handoff queue. */
    record DetectRequest(List<SignalBlock> blocks, long timestamp) {}

    public DetectorService(QueueService queueService, SignalWebSocketHandler wsHandler,
                           @Lazy SignalGenerator signalGenerator) {
        this.queueService = queueService;
        this.wsHandler = wsHandler;
        this.signalGenerator = signalGenerator;
    }

    /** Starts the background drain thread that processes detection requests. */
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

    /**
     * Non-blocking handoff from the tick thread.
     *
     * <p>If the detect queue is full (detector can't keep up), the request
     * is silently dropped. This prevents the tick scheduler from stalling.
     *
     * @param blocks current signal blocks to scan
     * @param now    current timestamp in millis
     */
    public void offerDetect(List<SignalBlock> blocks, long now) {
        detectQueue.offer(new DetectRequest(blocks, now));
    }

    /** Computes the detector window width in Hz based on the configured percentage. */
    private int getEffectiveWindowWidth() {
        return (int) (signalGenerator.getMaxWidth() * windowWidthPercent / 100.0);
    }

    /**
     * Runs the sliding window detection algorithm over the given signal blocks.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Filter blocks to those within the time window</li>
     *   <li>Slide detector window across frequency axis with configured overlap</li>
     *   <li>At each position, check for intersecting blocks not yet detected</li>
     *   <li>Apply detection probability (configurable miss rate)</li>
     *   <li>Batch-publish all new entities to Redis (3 RTTs total)</li>
     *   <li>Clean up expired block-to-entity mappings</li>
     * </ol>
     *
     * @param currentBlocks active signal blocks (endTime >= now)
     * @param now           current timestamp in millis
     */
    public void detect(List<SignalBlock> currentBlocks, long now) {
        int maxWidth = signalGenerator.getMaxWidth();
        int windowWidth = getEffectiveWindowWidth();
        if (windowWidth < 1) windowWidth = 1;

        double step = windowWidth * (1.0 - overlapPercent / 100.0);
        if (step < 1) step = 1;

        // Filter to blocks within the detection time window
        long timeThreshold = now - timeWindowMs;
        List<SignalBlock> inWindow = currentBlocks.stream()
                .filter(b -> b.getStartTime() >= timeThreshold)
                .toList();

        List<DetectedEntity> newEntities = new ArrayList<>();

        // Slide the detector window across the frequency axis
        for (double pos = 0; pos + windowWidth <= maxWidth; pos += step) {
            double winStart = pos;
            double winEnd = pos + windowWidth;

            for (SignalBlock block : inWindow) {
                // Check if block intersects this window position
                if (block.getWidthEnd() > winStart && block.getWidthStart() < winEnd) {
                    String existingEntityId = blockToEntity.get(block.getId());

                    if (existingEntityId == null) {
                        // Apply configurable detection probability
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

        // Batch publish all new entities — 3 RTTs total instead of 3 * N
        if (!newEntities.isEmpty()) {
            try {
                queueService.publishBatch(newEntities);
            } catch (Exception e) {
                log.warn("Batch publish failed: {}", e.getMessage());
            }
        }

        // Advance window position for visualization
        windowPosition += step;
        if (windowPosition + windowWidth > maxWidth) {
            windowPosition = 0;
        }
        wsHandler.broadcast("detector", Map.of(
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

        wsHandler.broadcast("entities", activeEntities.values());
    }

    // ── Getters & setters (used by SettingsController) ──────────────────

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
