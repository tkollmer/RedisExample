package com.df.queue.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Forwards signal blocks, detector state, and detected entities
 * from the sim service to all queue service instances.
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "sim")
public class SimForwardingService {

    private static final Logger log = LoggerFactory.getLogger(SimForwardingService.class);

    private final RestTemplate restTemplate;

    @Value("${sim.targets:http://app-a:8080,http://app-b:8080}")
    private String targetsCsv;

    public SimForwardingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private String[] getTargets() {
        return targetsCsv.split(",");
    }

    /** Forward signal blocks to all queue instances for WebSocket broadcast. */
    public void forwardBlocks(Map<String, Object> blockData) {
        forward("/api/sim/blocks", blockData);
    }

    /** Forward detector window position/width to all queue instances. */
    public void forwardDetector(Map<String, Object> detectorData) {
        forward("/api/sim/detector", detectorData);
    }

    /** Forward detector active entity count to all queue instances. */
    public void forwardEntityCount(Object entityCountData) {
        forward("/api/sim/entity-count", entityCountData);
    }

    /** Forward detected entities to all queue instances for Redis publish + merge. */
    public void forwardDetectedEntities(Object entities) {
        forward("/api/sync/entities", entities);
    }

    private void forward(String path, Object data) {
        for (String target : getTargets()) {
            try {
                restTemplate.postForObject(target.trim() + path, data, Map.class);
            } catch (Exception e) {
                log.debug("Forward to {}{} failed: {}", target.trim(), path, e.getMessage());
            }
        }
    }
}
