package com.df.queue.service;

import com.df.queue.web.SignalWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Polls Docker containers to determine Redis node status and broadcasts to WebSocket clients.
 *
 * <p>Every 2 seconds, inspects each Redis container via {@code docker inspect} to check
 * running state, then uses {@code docker exec redis-cli INFO replication} to determine
 * the node's role (master/slave/down).
 *
 * <p>Requires the Docker socket to be mounted in the app container
 * ({@code /var/run/docker.sock}).
 */
@Service
public class RedisStatusService {

    private static final Logger log = LoggerFactory.getLogger(RedisStatusService.class);

    private final SignalWebSocketHandler wsHandler;

    @Value("${redis.nodes:redis-master,redis-replica-1,redis-replica-2}")
    private String nodesCsv;

    public RedisStatusService(SignalWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    /** Polls all Redis containers and broadcasts their status. */
    @Scheduled(fixedRate = 2000)
    public void pollAndBroadcast() {
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String node : nodesCsv.split(",")) {
            statuses.add(inspectNode(node.trim()));
        }
        wsHandler.broadcast("redis-status", statuses);
    }

    /**
     * Inspects a single Redis container via Docker CLI.
     *
     * @param containerName Docker container name (e.g. "redis-master")
     * @return map with keys: name, running, status, role
     */
    private Map<String, Object> inspectNode(String containerName) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", containerName);

        // Check container running state via docker inspect
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

        // Determine replication role via redis-cli INFO
        try {
            Boolean running = (Boolean) info.get("running");
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
