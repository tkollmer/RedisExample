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

@Service
public class RedisStatusService {

    private static final Logger log = LoggerFactory.getLogger(RedisStatusService.class);

    private final SignalWebSocketHandler wsHandler;

    @Value("${redis.nodes:redis-master,redis-replica-1,redis-replica-2}")
    private String nodesCsv;

    public RedisStatusService(SignalWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @Scheduled(fixedRate = 2000)
    public void pollAndBroadcast() {
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String node : nodesCsv.split(",")) {
            statuses.add(inspectNode(node.trim()));
        }
        wsHandler.broadcast("redis-status", statuses);
    }

    private Map<String, Object> inspectNode(String containerName) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", containerName);
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

        // Get role
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
