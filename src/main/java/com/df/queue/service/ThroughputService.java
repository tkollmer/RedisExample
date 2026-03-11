package com.df.queue.service;

import com.df.queue.web.SignalWebSocketHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks entity throughput and broadcasts metrics to WebSocket clients.
 *
 * <p>Uses a simple sliding sample approach: every 1 second, computes the
 * delta in entity count and derives entities/sec. Thread-safe via {@link AtomicLong}.
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "queue", matchIfMissing = true)
public class ThroughputService {

    private final SignalWebSocketHandler wsHandler;
    private final AtomicLong entityCounter = new AtomicLong(0);
    private volatile double entitiesPerSec = 0;
    private long lastSampleTime = System.currentTimeMillis();
    private long lastSampleCount = 0;

    public ThroughputService(SignalWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    /** Increments the global entity counter. Called by QueueService on each publish. */
    public void recordEntity() {
        entityCounter.incrementAndGet();
    }

    public double getEntitiesPerSec() {
        return entitiesPerSec;
    }

    /** Computes entities/sec from the counter delta and broadcasts to all clients. */
    @Scheduled(fixedRate = 1000)
    public void computeAndBroadcast() {
        long now = System.currentTimeMillis();
        long currentCount = entityCounter.get();
        long elapsed = now - lastSampleTime;

        if (elapsed > 0) {
            long delta = currentCount - lastSampleCount;
            entitiesPerSec = delta * 1000.0 / elapsed;
        }

        lastSampleTime = now;
        lastSampleCount = currentCount;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entitiesPerSec", Math.round(entitiesPerSec * 10.0) / 10.0);
        data.put("totalEntities", currentCount);
        wsHandler.broadcast("throughput", data);
    }
}
