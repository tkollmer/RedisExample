package com.df.queue.model;

import java.util.Map;
import java.util.UUID;

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

    public static String colorFromAmplitude(double amp) {
        if (amp < 0.2) return "blue";
        if (amp < 0.4) return "cyan";
        if (amp < 0.6) return "green";
        if (amp < 0.8) return "yellow";
        return "red";
    }
}
