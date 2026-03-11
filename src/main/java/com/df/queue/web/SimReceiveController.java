package com.df.queue.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * Receives forwarded data from the sim service and broadcasts to WebSocket clients.
 * Only active in queue mode (app-a, app-b).
 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "queue", matchIfMissing = true)
@RequestMapping("/api/sim")
public class SimReceiveController {

    private final SignalWebSocketHandler wsHandler;

    public SimReceiveController(SignalWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @PostMapping("/blocks")
    public void receiveBlocks(@RequestBody Map<String, Object> blockData) {
        wsHandler.broadcast("blocks", blockData);
    }

    @PostMapping("/detector")
    public void receiveDetector(@RequestBody Map<String, Object> detectorData) {
        wsHandler.broadcast("detector", detectorData);
    }

    @PostMapping("/entity-count")
    public void receiveEntityCount(@RequestBody Collection<?> entityCountData) {
        wsHandler.broadcast("entities", entityCountData);
    }
}
