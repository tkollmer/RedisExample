package com.df.queue.model;

import java.util.Map;
import java.util.UUID;

public class DetectedEntity {
    private String entityId;
    private long detectionTime;
    private long startTime;
    private long endTime;
    private double widthStart;
    private double widthEnd;
    private double amplitude;
    private String color;
    private Map<String, String> metadata;

    public DetectedEntity() {}

    public DetectedEntity(SignalBlock block, long detectionTime) {
        this.entityId = "E-" + UUID.randomUUID().toString().substring(0, 8);
        this.detectionTime = detectionTime;
        this.startTime = block.getStartTime();
        this.endTime = block.getEndTime();
        this.widthStart = block.getWidthStart();
        this.widthEnd = block.getWidthEnd();
        this.amplitude = block.getAmplitude();
        this.color = block.getColor();
        this.metadata = block.getMetadata();
    }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public long getDetectionTime() { return detectionTime; }
    public void setDetectionTime(long detectionTime) { this.detectionTime = detectionTime; }
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
