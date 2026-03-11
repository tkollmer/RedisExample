package com.df.queue.web;

import com.df.queue.service.FailoverService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health endpoint used by peer heartbeats and container monitoring.
 * Works in both sim and queue modes.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${failover.instance-id:sim}")
    private String instanceId;

    @Autowired(required = false)
    private FailoverService failoverService;

    @GetMapping
    public Map<String, Object> health() {
        boolean active = failoverService != null ? failoverService.isActive() : true;
        return Map.of(
                "instanceId", instanceId,
                "active", active,
                "timestamp", System.currentTimeMillis()
        );
    }
}
