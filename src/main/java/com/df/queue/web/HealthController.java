package com.df.queue.web;

import com.df.queue.service.FailoverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final FailoverService failoverService;

    public HealthController(FailoverService failoverService) {
        this.failoverService = failoverService;
    }

    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "instanceId", failoverService.getInstanceId(),
                "active", failoverService.isActive(),
                "timestamp", System.currentTimeMillis()
        );
    }
}
