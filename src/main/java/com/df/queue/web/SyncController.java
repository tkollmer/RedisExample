package com.df.queue.web;

import com.df.queue.model.DetectedEntity;
import com.df.queue.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "queue", matchIfMissing = true)
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final QueueService queueService;

    public SyncController(QueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * Returns a snapshot of all entities in this instance's Redis.
     * Used by a recovering peer to sync state.
     */
    @GetMapping("/snapshot")
    public List<Map<String, String>> snapshot() {
        return queueService.getQueueState();
    }

    /**
     * Receives a snapshot and restores it into this instance's Redis.
     * Used when this instance recovers and needs to sync from peer.
     */
    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody List<Map<String, String>> snapshot) {
        try {
            queueService.restoreFromSnapshot(snapshot);
            log.info("Restored {} entities from peer snapshot", snapshot.size());
            return Map.of("success", true, "count", snapshot.size());
        } catch (Exception e) {
            log.error("Restore failed: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * Receives forwarded entities from the active peer.
     * Publishes them to local Redis using the same merge logic.
     */
    @PostMapping("/entities")
    public Map<String, Object> receiveEntities(@RequestBody List<DetectedEntity> entities) {
        try {
            queueService.publishBatch(entities);
            return Map.of("success", true, "count", entities.size());
        } catch (Exception e) {
            log.error("Entity sync failed: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
