package com.df.queue.model;

public class EntityMessage {
    private String type; // "new", "merged", "expired"
    private DetectedEntity entity;

    public EntityMessage() {}

    public EntityMessage(String type, DetectedEntity entity) {
        this.type = type;
        this.entity = entity;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public DetectedEntity getEntity() { return entity; }
    public void setEntity(DetectedEntity entity) { this.entity = entity; }
}
