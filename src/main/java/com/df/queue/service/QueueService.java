package com.df.queue.service;

import com.df.queue.model.DetectedEntity;
import com.df.queue.model.EntityMessage;
import com.df.queue.web.SignalWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final SignalWebSocketHandler wsHandler;
    private final ThroughputService throughputService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${queue.entity-ttl-seconds}")
    private long entityTtlSeconds;

    @Value("${queue.merge-time-threshold-ms}")
    private long mergeTimeThresholdMs;

    @Value("${queue.merge-width-overlap-percent}")
    private int mergeWidthOverlapPercent;

    public QueueService(RedisTemplate<String, Object> redisTemplate, SignalWebSocketHandler wsHandler,
                        ThroughputService throughputService) {
        this.redisTemplate = redisTemplate;
        this.wsHandler = wsHandler;
        this.throughputService = throughputService;
    }

    public long getEntityTtlSeconds() { return entityTtlSeconds; }
    public void setEntityTtlSeconds(long v) { this.entityTtlSeconds = Math.max(5, Math.min(3600, v)); }

    public EntityMessage publish(DetectedEntity entity) {
        try {
            throughputService.recordEntity();

            // Time slot key (1-second buckets)
            long timeSlot = entity.getStartTime() / 1000;
            String slotKey = "entities:" + timeSlot;

            // Check for merge candidates in nearby time slots
            DetectedEntity merged = findMergeCandidate(entity, timeSlot);
            if (merged != null) {
                // Broadcast merge-start: the pre-merge state + the incoming entity
                Map<String, Object> mergeStartData = new HashMap<>();
                mergeStartData.put("targetId", merged.getEntityId());
                mergeStartData.put("incomingId", entity.getEntityId());
                mergeStartData.put("targetWidthStart", merged.getWidthStart());
                mergeStartData.put("targetWidthEnd", merged.getWidthEnd());
                mergeStartData.put("targetStartTime", merged.getStartTime());
                mergeStartData.put("targetEndTime", merged.getEndTime());
                mergeStartData.put("incomingWidthStart", entity.getWidthStart());
                mergeStartData.put("incomingWidthEnd", entity.getWidthEnd());
                mergeStartData.put("incomingStartTime", entity.getStartTime());
                mergeStartData.put("incomingEndTime", entity.getEndTime());
                mergeStartData.put("color", merged.getColor());
                wsHandler.broadcast("merge-start", mergeStartData);

                // Merge: extend existing entity's range
                merged.setStartTime(Math.min(merged.getStartTime(), entity.getStartTime()));
                merged.setEndTime(Math.max(merged.getEndTime(), entity.getEndTime()));
                merged.setWidthStart(Math.min(merged.getWidthStart(), entity.getWidthStart()));
                merged.setWidthEnd(Math.max(merged.getWidthEnd(), entity.getWidthEnd()));
                merged.setAmplitude(Math.max(merged.getAmplitude(), entity.getAmplitude()));

                // Update in Redis (pipelined)
                saveEntity(merged, slotKey);
                log.debug("Merged entity {} into {}", entity.getEntityId(), merged.getEntityId());

                Map<String, Object> mergeEndData = new HashMap<>();
                mergeEndData.put("entityId", merged.getEntityId());
                mergeEndData.put("widthStart", merged.getWidthStart());
                mergeEndData.put("widthEnd", merged.getWidthEnd());
                mergeEndData.put("startTime", merged.getStartTime());
                mergeEndData.put("endTime", merged.getEndTime());
                mergeEndData.put("amplitude", merged.getAmplitude());
                mergeEndData.put("absorbedId", entity.getEntityId());
                mergeEndData.put("color", merged.getColor());
                wsHandler.broadcast("merge-end", mergeEndData);

                return new EntityMessage("merged", merged);
            }

            // Insert as new entity (pipelined)
            saveEntity(entity, slotKey);
            log.debug("New entity {} at width [{}, {}]",
                    entity.getEntityId(), entity.getWidthStart(), entity.getWidthEnd());
            return new EntityMessage("new", entity);

        } catch (Exception e) {
            log.error("Failed to publish entity {}: {}", entity.getEntityId(), e.getMessage());
            return new EntityMessage("new", entity);
        }
    }

    @SuppressWarnings("unchecked")
    private void saveEntity(DetectedEntity entity, String slotKey) {
        String entityKey = "entity:" + entity.getEntityId();

        Map<String, Object> hash = new HashMap<>();
        hash.put("entityId", entity.getEntityId());
        hash.put("detectionTime", String.valueOf(entity.getDetectionTime()));
        hash.put("startTime", String.valueOf(entity.getStartTime()));
        hash.put("endTime", String.valueOf(entity.getEndTime()));
        hash.put("widthStart", String.valueOf(entity.getWidthStart()));
        hash.put("widthEnd", String.valueOf(entity.getWidthEnd()));
        hash.put("amplitude", String.valueOf(entity.getAmplitude()));
        hash.put("color", entity.getColor() != null ? entity.getColor() : "green");

        // Pipeline all writes into a single round-trip
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().putAll(entityKey, hash);
                operations.expire(entityKey, entityTtlSeconds, TimeUnit.SECONDS);
                operations.opsForZSet().add(slotKey, entity.getEntityId(), entity.getWidthStart());
                operations.expire(slotKey, entityTtlSeconds, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private DetectedEntity findMergeCandidate(DetectedEntity incoming, long timeSlot) {
        // Pipeline: fetch all 3 slot sorted sets in one round-trip
        String[] slotKeys = {
                "entities:" + (timeSlot - 1),
                "entities:" + timeSlot,
                "entities:" + (timeSlot + 1)
        };

        List<Object> slotResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (String sk : slotKeys) {
                    operations.opsForZSet().range(sk, 0, -1);
                }
                return null;
            }
        });

        // Collect all unique member IDs
        Set<Object> allMembers = new LinkedHashSet<>();
        for (Object result : slotResults) {
            if (result instanceof Set) {
                allMembers.addAll((Set<Object>) result);
            }
        }
        if (allMembers.isEmpty()) return null;

        // Pipeline: fetch all candidate entity hashes in one round-trip
        List<String> entityKeys = new ArrayList<>(allMembers.size());
        for (Object memberId : allMembers) {
            entityKeys.add("entity:" + memberId);
        }

        List<Object> hashResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (String ek : entityKeys) {
                    operations.opsForHash().entries(ek);
                }
                return null;
            }
        });

        // Check each candidate for merge eligibility
        String incomingColor = incoming.getColor() != null ? incoming.getColor() : "green";

        for (Object hashResult : hashResults) {
            if (!(hashResult instanceof Map)) continue;
            Map<Object, Object> hash = (Map<Object, Object>) hashResult;
            if (hash.isEmpty()) continue;

            try {
                String existColor = (String) hash.get("color");
                if (!incomingColor.equals(existColor)) continue;

                double existWidthStart = Double.parseDouble((String) hash.get("widthStart"));
                double existWidthEnd = Double.parseDouble((String) hash.get("widthEnd"));
                long existStartTime = Long.parseLong((String) hash.get("startTime"));

                // Check time overlap
                if (Math.abs(incoming.getStartTime() - existStartTime) > mergeTimeThresholdMs) continue;

                // Check width overlap
                double overlapStart = Math.max(incoming.getWidthStart(), existWidthStart);
                double overlapEnd = Math.min(incoming.getWidthEnd(), existWidthEnd);
                double overlap = Math.max(0, overlapEnd - overlapStart);
                double incomingWidth = incoming.getWidthEnd() - incoming.getWidthStart();
                double existingWidth = existWidthEnd - existWidthStart;
                double minWidth = Math.min(incomingWidth, existingWidth);

                if (minWidth > 0 && (overlap / minWidth) * 100 >= mergeWidthOverlapPercent) {
                    long existEndTime = Long.parseLong((String) hash.get("endTime"));
                    DetectedEntity existing = new DetectedEntity();
                    existing.setEntityId((String) hash.get("entityId"));
                    existing.setDetectionTime(Long.parseLong((String) hash.get("detectionTime")));
                    existing.setStartTime(existStartTime);
                    existing.setEndTime(existEndTime);
                    existing.setWidthStart(existWidthStart);
                    existing.setWidthEnd(existWidthEnd);
                    existing.setAmplitude(Double.parseDouble((String) hash.get("amplitude")));
                    existing.setColor(existColor);
                    return existing;
                }
            } catch (Exception e) {
                log.warn("Error checking merge candidate: {}", e.getMessage());
            }
        }
        return null;
    }

    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        try {
            Set<String> keys = redisTemplate.keys("entities:*");
            if (keys == null) return;
            long now = System.currentTimeMillis() / 1000;
            for (String key : keys) {
                try {
                    long slot = Long.parseLong(key.split(":")[1]);
                    if (now - slot > entityTtlSeconds) {
                        Set<Object> members = redisTemplate.opsForZSet().range(key, 0, -1);
                        if (members != null) {
                            for (Object m : members) {
                                redisTemplate.delete("entity:" + m);
                            }
                        }
                        redisTemplate.delete(key);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            log.debug("Cleanup cycle: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 500)
    public void broadcastMergedEntities() {
        try {
            List<Map<String, String>> all = getQueueState();
            wsHandler.broadcast("redis-entities", all);
        } catch (Exception e) {
            log.debug("broadcastMergedEntities: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> getQueueState() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            Set<String> keys = redisTemplate.keys("entity:*");
            if (keys == null) return result;

            // Pipeline all hash reads
            List<String> keyList = new ArrayList<>(keys);
            List<Object> hashResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String key : keyList) {
                        operations.opsForHash().entries(key);
                    }
                    return null;
                }
            });

            for (Object hashResult : hashResults) {
                if (hashResult instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> hash = (Map<Object, Object>) hashResult;
                    if (!hash.isEmpty()) {
                        Map<String, String> entry = new HashMap<>();
                        hash.forEach((k, v) -> entry.put(k.toString(), v.toString()));
                        result.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("getQueueState: {}", e.getMessage());
        }
        return result;
    }
}
