package com.df.queue.service;

import com.df.queue.model.DetectedEntity;
import com.df.queue.model.EntityMessage;
import com.df.queue.model.SignalBlock;
import com.df.queue.web.SignalWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DetectorService {

    private static final Logger log = LoggerFactory.getLogger(DetectorService.class);

    private final QueueService queueService;
    private final SignalWebSocketHandler wsHandler;

    @Value("${signal.max-width}")
    private int maxWidth;

    @Value("${detector.window-width}")
    private int windowWidth;

    @Value("${detector.overlap-percent}")
    private int overlapPercent;

    @Value("${detector.time-window-ms:1000}")
    private long timeWindowMs;

    @Value("${detector.detection-probability:100}")
    private int detectionProbability; // 0-100%

    // Track which signal block IDs have already been assigned entity IDs
    private final Map<String, String> blockToEntity = new ConcurrentHashMap<>();
    // Active entities by entity ID
    private final Map<String, DetectedEntity> activeEntities = new ConcurrentHashMap<>();

    // Current detector window position (for UI overlay)
    private double windowPosition = 0;

    public DetectorService(QueueService queueService, SignalWebSocketHandler wsHandler) {
        this.queueService = queueService;
        this.wsHandler = wsHandler;
    }

    public void detect(List<SignalBlock> currentBlocks, long now) {
        double step = windowWidth * (1.0 - overlapPercent / 100.0);

        // Filter to only blocks within the detector time window
        long timeThreshold = now - timeWindowMs;
        List<SignalBlock> inWindow = currentBlocks.stream()
                .filter(b -> b.getStartTime() >= timeThreshold)
                .toList();

        // Slide the detector window across the width axis
        for (double pos = 0; pos + windowWidth <= maxWidth; pos += step) {
            double winStart = pos;
            double winEnd = pos + windowWidth;

            for (SignalBlock block : inWindow) {
                // Check if block intersects this window
                if (block.getWidthEnd() > winStart && block.getWidthStart() < winEnd) {
                    String existingEntityId = blockToEntity.get(block.getId());

                    if (existingEntityId == null) {
                        // Roll detection probability
                        if (detectionProbability < 100 &&
                                ThreadLocalRandom.current().nextInt(100) >= detectionProbability) {
                            continue;
                        }
                        // New detection
                        DetectedEntity entity = new DetectedEntity(block, now);
                        blockToEntity.put(block.getId(), entity.getEntityId());
                        activeEntities.put(entity.getEntityId(), entity);

                        // Publish to Redis queue
                        EntityMessage msg = queueService.publish(entity);
                        wsHandler.broadcast("entity", msg);
                    }
                }
            }
        }

        // Update window position for UI (sweep animation)
        windowPosition += step;
        if (windowPosition + windowWidth > maxWidth) {
            windowPosition = 0;
        }
        wsHandler.broadcast("detector", Map.of(
                "position", windowPosition,
                "width", windowWidth,
                "timeWindowMs", timeWindowMs
        ));

        // Clean up ended entities
        Set<String> endedBlocks = new HashSet<>();
        blockToEntity.forEach((blockId, entityId) -> {
            DetectedEntity entity = activeEntities.get(entityId);
            if (entity != null && entity.getEndTime() < now) {
                endedBlocks.add(blockId);
                activeEntities.remove(entityId);
            }
        });
        endedBlocks.forEach(blockToEntity::remove);

        // Send active entities list
        wsHandler.broadcast("entities", activeEntities.values());
    }

    public double getWindowPosition() {
        return windowPosition;
    }

    public int getWindowWidth() { return windowWidth; }
    public void setWindowWidth(int v) { this.windowWidth = Math.max(10, Math.min(maxWidth, v)); }

    public int getOverlapPercent() { return overlapPercent; }
    public void setOverlapPercent(int v) { this.overlapPercent = Math.max(0, Math.min(90, v)); }

    public long getTimeWindowMs() { return timeWindowMs; }
    public void setTimeWindowMs(long v) { this.timeWindowMs = Math.max(100, Math.min(30000, v)); }

    public int getDetectionProbability() { return detectionProbability; }
    public void setDetectionProbability(int v) { this.detectionProbability = Math.max(0, Math.min(100, v)); }
}
