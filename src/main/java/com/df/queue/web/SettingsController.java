package com.df.queue.web;

import com.df.queue.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings controller for queue mode instances.
 * Serves local queue settings and proxies signal/detector settings to the sim service.
 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "queue", matchIfMissing = true)
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final QueueService queueService;
    private final RestTemplate restTemplate;

    @Value("${sim.url:http://sim:8080}")
    private String simUrl;

    public SettingsController(QueueService queueService, RestTemplate restTemplate) {
        this.queueService = queueService;
        this.restTemplate = restTemplate;
    }

    @GetMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = new HashMap<>();

        // Local queue settings
        m.put("entityTtlSeconds", queueService.getEntityTtlSeconds());
        m.put("mergeWindowSeconds", queueService.getMergeWindowSeconds());

        // Fetch signal/detector settings from sim
        try {
            Map<String, Object> simSettings = restTemplate.getForObject(
                    simUrl + "/api/settings", Map.class);
            if (simSettings != null) {
                m.putAll(simSettings);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch sim settings: {}", e.getMessage());
        }

        // Override with local values (in case sim returned stale data)
        m.put("entityTtlSeconds", queueService.getEntityTtlSeconds());
        m.put("mergeWindowSeconds", queueService.getMergeWindowSeconds());

        return m;
    }

    @PostMapping
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> settings) {
        // Apply local queue settings
        if (settings.containsKey("entityTtlSeconds")) {
            queueService.setEntityTtlSeconds(((Number) settings.get("entityTtlSeconds")).longValue());
        }
        if (settings.containsKey("mergeWindowSeconds")) {
            queueService.setMergeWindowSeconds(((Number) settings.get("mergeWindowSeconds")).intValue());
        }

        // Forward all settings to sim (sim ignores queue-only settings)
        try {
            restTemplate.postForObject(simUrl + "/api/settings", settings, Map.class);
        } catch (Exception e) {
            log.debug("Failed to forward settings to sim: {}", e.getMessage());
        }

        return getSettings();
    }
}
