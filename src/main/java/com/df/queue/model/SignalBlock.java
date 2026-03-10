package com.df.queue.model;

import java.util.Map;
import java.util.UUID;

/**
 * A raw signal block representing a detected signal in frequency-time space.
 *
 * <p>Each block occupies a rectangular region defined by:
 * <ul>
 *   <li><b>Frequency band</b>: [widthStart, widthEnd] in Hz</li>
 *   <li><b>Time span</b>: [startTime, endTime] in epoch milliseconds</li>
 *   <li><b>Amplitude</b>: 0.0 to 1.0 (determines color)</li>
 * </ul>
 *
 * <p>Color is derived from amplitude via {@link #colorFromAmplitude}:
 * blue (0.0-0.2) → cyan (0.2-0.4) → green (0.4-0.6) → yellow (0.6-0.8) → red (0.8-1.0)
 */
public class SignalBlock {

    private String id;
    private long startTime;
    private long endTime;
    private double widthStart;
    private double widthEnd;
    private double amplitude;
    private String color;
    private Map<String, String> metadata;

    public SignalBlock() {}

    public SignalBlock(long startTime, long endTime, double widthStart, double widthEnd,
                       double amplitude, String color, Map<String, String> metadata) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.startTime = startTime;
        this.endTime = endTime;
        this.widthStart = widthStart;
        this.widthEnd = widthEnd;
        this.amplitude = amplitude;
        this.color = color;
        this.metadata = metadata;
    }

    /**
     * Maps an amplitude value (0.0–1.0) to a color string for visualization.
     * Lower amplitudes are cool colors, higher amplitudes are warm colors.
     */
    public static String colorFromAmplitude(double amp) {
        if (amp < 0.2) return "blue";
        if (amp < 0.4) return "cyan";
        if (amp < 0.6) return "green";
        if (amp < 0.8) return "yellow";
        return "red";
    }

    // ── Getters & Setters ───────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }
    public double getWidthStart() { return widthStart; }
    public void setWidthStart(double widthStart) { this.widthStart = widthStart; }
    public double getWidthEnd() { return widthEnd; }
    public void setWidthEnd(double widthEnd) { this.widthEnd = widthEnd; }
    public double getAmplitude() { return amplitude; }
    public void setAmplitude(double amplitude) { this.amplitude = amplitude; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
