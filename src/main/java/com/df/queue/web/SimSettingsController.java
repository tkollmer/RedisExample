package com.df.queue.web;

import com.df.queue.service.DetectorService;
import com.df.queue.service.SignalGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings controller for the sim service.
 * Handles signal generator and detector settings only.
 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "sim")
@RequestMapping("/api/settings")
public class SimSettingsController {

    private final SignalGenerator signalGenerator;
    private final DetectorService detectorService;

    public SimSettingsController(SignalGenerator signalGenerator, DetectorService detectorService) {
        this.signalGenerator = signalGenerator;
        this.detectorService = detectorService;
    }

    @GetMapping
    public Map<String, Object> getSettings() {
        Map<String, Object> m = new HashMap<>();
        m.put("maxBlocksPerTick", signalGenerator.getMaxBlocksPerTick());
        m.put("minBlockDurationMs", signalGenerator.getMinBlockDuration());
        m.put("maxBlockDurationMs", signalGenerator.getMaxBlockDuration());
        m.put("minBlockWidth", signalGenerator.getMinBlockWidth());
        m.put("maxBlockWidth", signalGenerator.getMaxBlockWidth());
        m.put("detectorWindowWidthPercent", detectorService.getWindowWidthPercent());
        m.put("detectorOverlapPercent", detectorService.getOverlapPercent());
        m.put("detectorTimeWindowMs", detectorService.getTimeWindowMs());
        m.put("detectionProbability", detectorService.getDetectionProbability());
        m.put("maxWidth", signalGenerator.getMaxWidth());
        m.put("retentionMs", signalGenerator.getRetentionMs());
        m.put("tickIntervalMs", signalGenerator.getTickIntervalMs());
        m.put("paused", signalGenerator.isPaused());
        return m;
    }

    @PostMapping
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> settings) {
        if (settings.containsKey("maxBlocksPerTick")) {
            signalGenerator.setMaxBlocksPerTick(((Number) settings.get("maxBlocksPerTick")).intValue());
        }
        if (settings.containsKey("minBlockDurationMs")) {
            signalGenerator.setMinBlockDuration(((Number) settings.get("minBlockDurationMs")).longValue());
        }
        if (settings.containsKey("maxBlockDurationMs")) {
            signalGenerator.setMaxBlockDuration(((Number) settings.get("maxBlockDurationMs")).longValue());
        }
        if (settings.containsKey("minBlockWidth")) {
            signalGenerator.setMinBlockWidth(((Number) settings.get("minBlockWidth")).doubleValue());
        }
        if (settings.containsKey("maxBlockWidth")) {
            signalGenerator.setMaxBlockWidth(((Number) settings.get("maxBlockWidth")).doubleValue());
        }
        if (settings.containsKey("detectorWindowWidthPercent")) {
            detectorService.setWindowWidthPercent(((Number) settings.get("detectorWindowWidthPercent")).intValue());
        }
        if (settings.containsKey("detectorOverlapPercent")) {
            detectorService.setOverlapPercent(((Number) settings.get("detectorOverlapPercent")).intValue());
        }
        if (settings.containsKey("detectorTimeWindowMs")) {
            detectorService.setTimeWindowMs(((Number) settings.get("detectorTimeWindowMs")).longValue());
        }
        if (settings.containsKey("detectionProbability")) {
            detectorService.setDetectionProbability(((Number) settings.get("detectionProbability")).intValue());
        }
        if (settings.containsKey("maxWidth")) {
            signalGenerator.setMaxWidth(((Number) settings.get("maxWidth")).intValue());
        }
        if (settings.containsKey("retentionMs")) {
            signalGenerator.setRetentionMs(((Number) settings.get("retentionMs")).longValue());
        }
        if (settings.containsKey("tickIntervalMs")) {
            signalGenerator.setTickIntervalMs(((Number) settings.get("tickIntervalMs")).longValue());
        }
        if (settings.containsKey("paused")) {
            signalGenerator.setPaused((Boolean) settings.get("paused"));
        }
        return getSettings();
    }
}
