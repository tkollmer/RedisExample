package com.df.queue.model;

/**
 * WebSocket message wrapper for entity events.
 *
 * <p>Wraps a {@link DetectedEntity} with a type indicator:
 * <ul>
 *   <li>{@code "new"} — entity was inserted as a new entry in Redis</li>
 *   <li>{@code "merged"} — entity was merged into an existing entity</li>
 * </ul>
 */
public class EntityMessage {

    private String type;
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
