package com.df.queue.service;

import com.df.queue.web.SignalWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Polls Docker containers to determine node status and broadcasts to WebSocket clients.
 * Supports both Redis containers and app containers (with active/standby roles).
 */
@Service
public class RedisStatusService {

    private static final Logger log = LoggerFactory.getLogger(RedisStatusService.class);

    private final SignalWebSocketHandler wsHandler;
    private final RestTemplate restTemplate;

    @Value("${redis.nodes:redis-a,redis-b,app-a,app-b}")
    private String nodesCsv;

    public RedisStatusService(SignalWebSocketHandler wsHandler, RestTemplate restTemplate) {
        this.wsHandler = wsHandler;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedRate = 2000)
    public void pollAndBroadcast() {
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String node : nodesCsv.split(",")) {
            statuses.add(inspectNode(node.trim()));
        }
        wsHandler.broadcast("redis-status", statuses);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inspectNode(String containerName) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", containerName);

        // Check container running state
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "inspect", "-f",
                    "{{.State.Running}}|{{.State.Status}}",
                    containerName);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                output = reader.readLine();
            }
            proc.waitFor(3, TimeUnit.SECONDS);

            if (output != null && output.contains("|")) {
                String[] parts = output.split("\\|");
                info.put("running", Boolean.parseBoolean(parts[0]));
                info.put("status", parts[1]);
            } else {
                info.put("running", false);
                info.put("status", "unknown");
            }
        } catch (Exception e) {
            info.put("running", false);
            info.put("status", "error");
        }

        Boolean running = (Boolean) info.get("running");

        // For app containers, determine role via health endpoint
        if (containerName.startsWith("app-")) {
            if (running != null && running) {
                try {
                    // Determine port: app-a=8080, app-b=8081
                    int port = containerName.equals("app-a") ? 8080 : 8081;
                    Map<String, Object> health = restTemplate.getForObject(
                            "http://localhost:" + port + "/api/health", Map.class);
                    if (health != null && health.get("active") != null) {
                        info.put("role", (Boolean) health.get("active") ? "active" : "standby");
                    } else {
                        info.put("role", "unknown");
                    }
                } catch (Exception e) {
                    info.put("role", "starting");
                }
            } else {
                info.put("role", "down");
            }
            return info;
        }

        // For Redis containers, check replication role
        try {
            if (running != null && running) {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", containerName,
                        "redis-cli", "INFO", "replication");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String role = "unknown";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("role:")) {
                            role = line.substring(5).trim();
                            break;
                        }
                    }
                }
                proc.waitFor(2, TimeUnit.SECONDS);
                info.put("role", role);
            } else {
                info.put("role", "down");
            }
        } catch (Exception e) {
            info.put("role", "unknown");
        }
        return info;
    }
}
