package com.df.queue.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/redis")
public class RedisNodeController {

    private static final Logger log = LoggerFactory.getLogger(RedisNodeController.class);

    @Value("${redis.nodes:redis-a,redis-b,app-a,app-b}")
    private String nodesCsv;

    private List<String> getNodes() {
        return Arrays.asList(nodesCsv.split(","));
    }

    @PostMapping("/stop/{node}")
    public Map<String, Object> stopNode(@PathVariable String node) {
        return execDocker("stop", node);
    }

    @PostMapping("/start/{node}")
    public Map<String, Object> startNode(@PathVariable String node) {
        return execDocker("start", node);
    }

    @PostMapping("/stop-all")
    public Map<String, Object> stopAll() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String node : getNodes()) {
            results.put(node, execDocker("stop", node));
        }
        return results;
    }

    @PostMapping("/start-all")
    public Map<String, Object> startAll() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String node : getNodes()) {
            results.put(node, execDocker("start", node));
        }
        return results;
    }

    @GetMapping("/status")
    public List<Map<String, Object>> getStatus() {
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String node : getNodes()) {
            statuses.add(inspectNode(node));
        }
        return statuses;
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
            proc.waitFor(5, TimeUnit.SECONDS);

            if (output != null && output.contains("|")) {
                String[] parts = output.split("\\|");
                info.put("running", Boolean.parseBoolean(parts[0]));
                info.put("status", parts[1]);
            } else {
                info.put("running", false);
                info.put("status", "unknown");
            }
        } catch (Exception e) {
            log.warn("Failed to inspect {}: {}", containerName, e.getMessage());
            info.put("running", false);
            info.put("status", "error");
        }

        // For Redis nodes, check replication role
        if (containerName.startsWith("redis-")) {
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
                    proc.waitFor(3, TimeUnit.SECONDS);
                    info.put("role", role);
                } else {
                    info.put("role", "down");
                }
            } catch (Exception e) {
                info.put("role", "unknown");
            }
        } else {
            // App nodes — role determined elsewhere
            info.put("role", info.get("running") != null && (Boolean) info.get("running") ? "app" : "down");
        }
        return info;
    }

    private Map<String, Object> execDocker(String action, String containerName) {
        if (!getNodes().contains(containerName)) {
            return Map.of("success", false, "error", "Unknown node: " + containerName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", containerName);
        result.put("action", action);
        try {
            List<String> cmd = "stop".equals(action)
                    ? List.of("docker", "stop", "-t", "1", containerName)
                    : List.of("docker", action, containerName);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                output = sb.toString();
            }
            boolean ok = proc.waitFor(15, TimeUnit.SECONDS) && proc.exitValue() == 0;
            result.put("success", ok);
            result.put("output", output);
            log.info("Docker {} {} -> {}", action, containerName, ok ? "OK" : "FAIL");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("Docker {} {} failed: {}", action, containerName, e.getMessage());
        }
        return result;
    }
}
