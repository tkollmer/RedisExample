package com.df.queue.service;

import com.df.queue.model.SignalBlock;
import com.df.queue.web.SignalWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class SignalGenerator {

    private static final Logger log = LoggerFactory.getLogger(SignalGenerator.class);

    private final SignalWebSocketHandler wsHandler;
    private final DetectorService detectorService;

    @Value("${signal.max-width}")
    private int maxWidth;

    @Value("${signal.max-blocks-per-tick}")
    private int maxBlocksPerTick;

    @Value("${signal.min-block-duration-ms}")
    private long minBlockDuration;

    @Value("${signal.max-block-duration-ms}")
    private long maxBlockDuration;

    @Value("${signal.min-block-width:20}")
    private double minBlockWidth;

    @Value("${signal.max-block-width:300}")
    private double maxBlockWidth;

    @Value("${signal.retention-ms:30000}")
    private long retentionMs;

    @Value("${signal.tick-interval-ms:100}")
    private long tickIntervalMs;

    private volatile boolean paused = false;

    private final Deque<SignalBlock> activeBlocks = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> tickFuture;

    public SignalGenerator(SignalWebSocketHandler wsHandler, DetectorService detectorService) {
        this.wsHandler = wsHandler;
        this.detectorService = detectorService;
    }

    @PostConstruct
    public void start() {
        reschedule();
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private synchronized void reschedule() {
        if (tickFuture != null) {
            tickFuture.cancel(false);
        }
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void tick() {
        if (paused) return;

        long now = System.currentTimeMillis();

        activeBlocks.removeIf(b -> b.getEndTime() < now - retentionMs);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = rng.nextInt(0, maxBlocksPerTick + 1);
        List<SignalBlock> newBlocks = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long duration = rng.nextLong(minBlockDuration, maxBlockDuration + 1);
            double ws = rng.nextDouble(0, maxWidth * 0.8);
            double blockWidth = rng.nextDouble(minBlockWidth, maxBlockWidth + 1);
            double we = Math.min(ws + blockWidth, maxWidth);
            double amp = rng.nextDouble(0.1, 1.0);

            String color = SignalBlock.colorFromAmplitude(amp);
            SignalBlock block = new SignalBlock(
                    now,
                    now + duration,
                    ws, we, amp, color,
                    Map.of("source", "gen", "tick", String.valueOf(now))
            );
            newBlocks.add(block);
            activeBlocks.add(block);
        }

        List<SignalBlock> visible = activeBlocks.stream()
                .filter(b -> b.getEndTime() >= now - retentionMs)
                .toList();
        wsHandler.broadcast("blocks", Map.of(
                "blocks", visible,
                "maxWidth", maxWidth,
                "retentionMs", retentionMs
        ));

        List<SignalBlock> current = activeBlocks.stream()
                .filter(b -> b.getEndTime() >= now)
                .toList();
        detectorService.detect(current, now);
    }

    public Collection<SignalBlock> getActiveBlocks() {
        return Collections.unmodifiableCollection(activeBlocks);
    }

    public int getMaxBlocksPerTick() { return maxBlocksPerTick; }
    public void setMaxBlocksPerTick(int v) { this.maxBlocksPerTick = Math.max(0, Math.min(500, v)); }

    public int getMaxWidth() { return maxWidth; }
    public void setMaxWidth(int v) { this.maxWidth = Math.max(100, Math.min(100000, v)); }

    public long getRetentionMs() { return retentionMs; }
    public void setRetentionMs(long v) { this.retentionMs = Math.max(1000, Math.min(120000, v)); }

    public long getMinBlockDuration() { return minBlockDuration; }
    public void setMinBlockDuration(long v) { this.minBlockDuration = Math.max(100, v); }

    public long getMaxBlockDuration() { return maxBlockDuration; }
    public void setMaxBlockDuration(long v) { this.maxBlockDuration = Math.max(minBlockDuration, v); }

    public double getMinBlockWidth() { return minBlockWidth; }
    public void setMinBlockWidth(double v) { this.minBlockWidth = Math.max(5, v); }

    public double getMaxBlockWidth() { return maxBlockWidth; }
    public void setMaxBlockWidth(double v) { this.maxBlockWidth = Math.max(minBlockWidth, Math.min(maxWidth, v)); }

    public long getTickIntervalMs() { return tickIntervalMs; }
    public void setTickIntervalMs(long v) {
        long newVal = Math.max(10, Math.min(1000, v));
        if (newVal != tickIntervalMs) {
            this.tickIntervalMs = newVal;
            reschedule();
        }
    }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { this.paused = p; }
}
