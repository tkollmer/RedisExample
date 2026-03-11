package com.df.queue.service;

import com.df.queue.model.DetectedEntity;
import com.df.queue.model.EntityMessage;
import com.df.queue.web.SignalWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core Redis storage service for detected entities.
 *
 * <p>Uses two global sorted sets for 2D range queries:
 * <ul>
 *   <li>{@code entities:by_time} — score = detectionTime (epoch ms)</li>
 *   <li>{@code entities:by_freq} — score = center frequency</li>
 * </ul>
 * Entity hashes remain at {@code entity:{id}}.
 *
 * <p>Merge candidates are found via intersection of time-range and frequency-range
 * queries, then filtered by color match and width similarity.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private static final String BY_TIME_KEY = "entities:by_time";
    private static final String BY_FREQ_KEY = "entities:by_freq";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SignalWebSocketHandler wsHandler;
    private final ThroughputService throughputService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${queue.entity-ttl-seconds}")
    private long entityTtlSeconds;

    @Value("${queue.merge-window-seconds:30}")
    private int mergeWindowSeconds;

    @Value("${queue.merge-width-overlap-percent}")
    private int mergeWidthOverlapPercent;

    private volatile boolean broadcastEnabled = true;

    public QueueService(RedisTemplate<String, Object> redisTemplate, SignalWebSocketHandler wsHandler,
                        ThroughputService throughputService) {
        this.redisTemplate = redisTemplate;
        this.wsHandler = wsHandler;
        this.throughputService = throughputService;
    }

    public long getEntityTtlSeconds() { return entityTtlSeconds; }
    public void setEntityTtlSeconds(long v) { this.entityTtlSeconds = Math.max(5, Math.min(3600, v)); }

    public int getMergeWindowSeconds() { return mergeWindowSeconds; }
    public void setMergeWindowSeconds(int v) { this.mergeWindowSeconds = Math.max(1, Math.min(120, v)); }

    public void setBroadcastEnabled(boolean enabled) { this.broadcastEnabled = enabled; }
    public boolean isBroadcastEnabled() { return broadcastEnabled; }

    // ────────────────────────────────────────────────────────────────────
    //  Hash conversion helpers
    // ────────────────────────────────────────────────────────────────────

    private Map<String, Object> entityToHash(DetectedEntity entity) {
        Map<String, Object> hash = new HashMap<>();
        hash.put("entityId", entity.getEntityId());
        hash.put("detectionTime", String.valueOf(entity.getDetectionTime()));
        hash.put("startTime", String.valueOf(entity.getStartTime()));
        hash.put("endTime", String.valueOf(entity.getEndTime()));
        hash.put("widthStart", String.valueOf(entity.getWidthStart()));
        hash.put("widthEnd", String.valueOf(entity.getWidthEnd()));
        hash.put("amplitude", String.valueOf(entity.getAmplitude()));
        hash.put("color", entity.getColor() != null ? entity.getColor() : "green");
        return hash;
    }

    private Map<Object, Object> entityToObjectMap(DetectedEntity entity) {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("entityId", entity.getEntityId());
        hash.put("detectionTime", String.valueOf(entity.getDetectionTime()));
        hash.put("startTime", String.valueOf(entity.getStartTime()));
        hash.put("endTime", String.valueOf(entity.getEndTime()));
        hash.put("widthStart", String.valueOf(entity.getWidthStart()));
        hash.put("widthEnd", String.valueOf(entity.getWidthEnd()));
        hash.put("amplitude", String.valueOf(entity.getAmplitude()));
        hash.put("color", entity.getColor() != null ? entity.getColor() : "green");
        return hash;
    }

    private double centerFreq(DetectedEntity e) {
        return (e.getWidthStart() + e.getWidthEnd()) / 2.0;
    }

    private double centerFreq(double widthStart, double widthEnd) {
        return (widthStart + widthEnd) / 2.0;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Single-entity publish
    // ────────────────────────────────────────────────────────────────────

    public EntityMessage publish(DetectedEntity entity) {
        try {
            throughputService.recordEntity();
            DetectedEntity merged = findMergeCandidateSingle(entity);
            if (merged != null) {
                if (broadcastEnabled) wsHandler.broadcast("merge-start", buildMergeStartData(merged, entity));
                merged.setStartTime(Math.min(merged.getStartTime(), entity.getStartTime()));
                merged.setEndTime(Math.max(merged.getEndTime(), entity.getEndTime()));
                merged.setWidthStart(Math.min(merged.getWidthStart(), entity.getWidthStart()));
                merged.setWidthEnd(Math.max(merged.getWidthEnd(), entity.getWidthEnd()));
                merged.setAmplitude(Math.max(merged.getAmplitude(), entity.getAmplitude()));
                saveEntity(merged);
                if (broadcastEnabled) wsHandler.broadcast("merge-end", buildMergeEndData(merged, entity));
                return new EntityMessage("merged", merged);
            }
            saveEntity(entity);
            return new EntityMessage("new", entity);
        } catch (Exception e) {
            log.error("Failed to publish entity {}: {}", entity.getEntityId(), e.getMessage());
            return new EntityMessage("new", entity);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Batch entity publish — 3 RTTs total
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<EntityMessage> publishBatch(List<DetectedEntity> entities) {
        if (entities.isEmpty()) return Collections.emptyList();

        List<EntityMessage> results = new ArrayList<>(entities.size());

        try {
            for (DetectedEntity e : entities) throughputService.recordEntity();

            // ── RTT 1: Range queries on both sorted sets ────────────
            long now = System.currentTimeMillis();
            long mergeWindowMs = mergeWindowSeconds * 1000L;

            // Compute frequency bounds across entire batch
            double batchMinFreq = Double.MAX_VALUE;
            double batchMaxFreq = Double.MIN_VALUE;
            for (DetectedEntity entity : entities) {
                double cf = centerFreq(entity);
                double width = entity.getWidthEnd() - entity.getWidthStart();
                batchMinFreq = Math.min(batchMinFreq, cf - width);
                batchMaxFreq = Math.max(batchMaxFreq, cf + width);
            }

            final double minFreq = batchMinFreq;
            final double maxFreq = batchMaxFreq;

            List<Object> rangeResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    // Time range: entities detected within merge window
                    operations.opsForZSet().rangeByScore(BY_TIME_KEY, now - mergeWindowMs, now);
                    // Freq range: entities with similar center frequency
                    operations.opsForZSet().rangeByScore(BY_FREQ_KEY, minFreq, maxFreq);
                    return null;
                }
            });

            Set<Object> timeMatches = rangeResults.get(0) instanceof Set
                    ? (Set<Object>) rangeResults.get(0) : Collections.emptySet();
            Set<Object> freqMatches = rangeResults.get(1) instanceof Set
                    ? (Set<Object>) rangeResults.get(1) : Collections.emptySet();

            // Intersect: candidates must appear in both time AND freq ranges
            Set<Object> candidateIds = new LinkedHashSet<>(timeMatches);
            candidateIds.retainAll(freqMatches);

            // ── RTT 2: Fetch candidate entity hashes ────────────────
            Map<String, Map<Object, Object>> candidateHashMap = new HashMap<>();
            if (!candidateIds.isEmpty()) {
                List<String> candidateIdList = new ArrayList<>();
                List<String> candidateKeyList = new ArrayList<>();
                for (Object id : candidateIds) {
                    String idStr = id.toString();
                    candidateIdList.add(idStr);
                    candidateKeyList.add("entity:" + idStr);
                }

                List<Object> hashResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    public Object execute(RedisOperations operations) throws DataAccessException {
                        for (String ek : candidateKeyList) {
                            operations.opsForHash().entries(ek);
                        }
                        return null;
                    }
                });

                for (int i = 0; i < candidateIdList.size(); i++) {
                    Object hr = hashResults.get(i);
                    if (hr instanceof Map && !((Map<?, ?>) hr).isEmpty()) {
                        candidateHashMap.put(candidateIdList.get(i), (Map<Object, Object>) hr);
                    }
                }
            }

            // ── Local merge evaluation (0 RTTs) ─────────────────────
            Map<String, DetectedEntity> modifiedEntities = new HashMap<>();
            Map<String, DetectedEntity> toSave = new LinkedHashMap<>();

            for (DetectedEntity entity : entities) {
                DetectedEntity merged = findMergeCandidateLocal(
                        entity, candidateHashMap, modifiedEntities);

                if (merged != null) {
                    if (broadcastEnabled) wsHandler.broadcast("merge-start", buildMergeStartData(merged, entity));

                    merged.setStartTime(Math.min(merged.getStartTime(), entity.getStartTime()));
                    merged.setEndTime(Math.max(merged.getEndTime(), entity.getEndTime()));
                    merged.setWidthStart(Math.min(merged.getWidthStart(), entity.getWidthStart()));
                    merged.setWidthEnd(Math.max(merged.getWidthEnd(), entity.getWidthEnd()));
                    merged.setAmplitude(Math.max(merged.getAmplitude(), entity.getAmplitude()));

                    modifiedEntities.put(merged.getEntityId(), merged);
                    candidateHashMap.put(merged.getEntityId(), entityToObjectMap(merged));

                    toSave.put(merged.getEntityId(), merged);

                    if (broadcastEnabled) wsHandler.broadcast("merge-end", buildMergeEndData(merged, entity));
                    results.add(new EntityMessage("merged", merged));
                } else {
                    // New entity — add to local tracking for intra-batch merging
                    candidateHashMap.put(entity.getEntityId(), entityToObjectMap(entity));

                    toSave.put(entity.getEntityId(), entity);
                    results.add(new EntityMessage("new", entity));
                }
            }

            // ── RTT 3: Save all entities in 1 pipeline ──────────────
            List<Map.Entry<String, DetectedEntity>> saveList = new ArrayList<>(toSave.entrySet());
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (Map.Entry<String, DetectedEntity> entry : saveList) {
                        DetectedEntity e = entry.getValue();
                        String entityKey = "entity:" + e.getEntityId();

                        Map<String, Object> hash = entityToHash(e);
                        operations.opsForHash().putAll(entityKey, hash);
                        operations.opsForZSet().add(BY_TIME_KEY, e.getEntityId(), e.getDetectionTime());
                        operations.opsForZSet().add(BY_FREQ_KEY, e.getEntityId(), centerFreq(e));
                    }
                    return null;
                }
            });

            // Broadcast entity events
            if (broadcastEnabled) {
                for (EntityMessage msg : results) {
                    wsHandler.broadcast("entity", msg);
                }
            }

        } catch (Exception e) {
            log.error("Batch publish failed: {}", e.getMessage());
            for (DetectedEntity entity : entities) {
                results.add(new EntityMessage("new", entity));
            }
        }

        return results;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Local merge candidate lookup (zero Redis calls)
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private DetectedEntity findMergeCandidateLocal(DetectedEntity incoming,
            Map<String, Map<Object, Object>> candidateHashMap, Map<String, DetectedEntity> modifiedEntities) {

        String incomingColor = incoming.getColor() != null ? incoming.getColor() : "green";
        long mergeWindowMs = mergeWindowSeconds * 1000L;

        for (Map.Entry<String, Map<Object, Object>> entry : candidateHashMap.entrySet()) {
            String idStr = entry.getKey();

            // Prefer in-batch modified state
            DetectedEntity modified = modifiedEntities.get(idStr);
            if (modified != null) {
                if (isMergeEligible(incoming, incomingColor, modified.getColor(),
                        modified.getDetectionTime(), modified.getWidthStart(), modified.getWidthEnd(), mergeWindowMs)) {
                    return modified;
                }
                continue;
            }

            Map<Object, Object> hash = entry.getValue();
            if (hash == null || hash.isEmpty()) continue;

            try {
                String existColor = hash.get("color") != null ? hash.get("color").toString() : "green";
                double existWidthStart = Double.parseDouble(hash.get("widthStart").toString());
                double existWidthEnd = Double.parseDouble(hash.get("widthEnd").toString());
                long existDetectionTime = Long.parseLong(hash.get("detectionTime").toString());

                if (isMergeEligible(incoming, incomingColor, existColor,
                        existDetectionTime, existWidthStart, existWidthEnd, mergeWindowMs)) {
                    DetectedEntity existing = new DetectedEntity();
                    existing.setEntityId(hash.get("entityId").toString());
                    existing.setDetectionTime(existDetectionTime);
                    existing.setStartTime(Long.parseLong(hash.get("startTime").toString()));
                    existing.setEndTime(Long.parseLong(hash.get("endTime").toString()));
                    existing.setWidthStart(existWidthStart);
                    existing.setWidthEnd(existWidthEnd);
                    existing.setAmplitude(Double.parseDouble(hash.get("amplitude").toString()));
                    existing.setColor(existColor);
                    return existing;
                }
            } catch (Exception e) {
                log.warn("Error checking batch merge candidate: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Evaluates merge eligibility: color match, time proximity, and width overlap.
     * Entities must have the same color, close center frequency, similar width,
     * and be within the merge time window.
     */
    private boolean isMergeEligible(DetectedEntity incoming, String incomingColor,
            String candidateColor, long candidateDetectionTime,
            double candidateWidthStart, double candidateWidthEnd, long mergeWindowMs) {
        // 1. Color must match
        if (!incomingColor.equals(candidateColor)) return false;

        // 2. Time proximity check (using detection time within merge window)
        if (Math.abs(incoming.getDetectionTime() - candidateDetectionTime) > mergeWindowMs) return false;

        // 3. Width overlap check: overlap / min(widths) >= threshold
        double overlapStart = Math.max(incoming.getWidthStart(), candidateWidthStart);
        double overlapEnd = Math.min(incoming.getWidthEnd(), candidateWidthEnd);
        double overlap = Math.max(0, overlapEnd - overlapStart);
        double incomingWidth = incoming.getWidthEnd() - incoming.getWidthStart();
        double existingWidth = candidateWidthEnd - candidateWidthStart;
        double minWidth = Math.min(incomingWidth, existingWidth);

        return minWidth > 0 && (overlap / minWidth) * 100 >= mergeWidthOverlapPercent;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Single-entity merge candidate (2 RTTs)
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private DetectedEntity findMergeCandidateSingle(DetectedEntity incoming) {
        long now = System.currentTimeMillis();
        long mergeWindowMs = mergeWindowSeconds * 1000L;
        double cf = centerFreq(incoming);
        double width = incoming.getWidthEnd() - incoming.getWidthStart();

        // RTT 1: Range queries on both sorted sets
        List<Object> rangeResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForZSet().rangeByScore(BY_TIME_KEY, now - mergeWindowMs, now);
                operations.opsForZSet().rangeByScore(BY_FREQ_KEY, cf - width, cf + width);
                return null;
            }
        });

        Set<Object> timeMatches = rangeResults.get(0) instanceof Set
                ? (Set<Object>) rangeResults.get(0) : Collections.emptySet();
        Set<Object> freqMatches = rangeResults.get(1) instanceof Set
                ? (Set<Object>) rangeResults.get(1) : Collections.emptySet();

        Set<Object> candidateIds = new LinkedHashSet<>(timeMatches);
        candidateIds.retainAll(freqMatches);
        if (candidateIds.isEmpty()) return null;

        // RTT 2: Fetch candidate hashes
        List<String> entityKeys = new ArrayList<>(candidateIds.size());
        for (Object memberId : candidateIds) {
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

        String incomingColor = incoming.getColor() != null ? incoming.getColor() : "green";

        for (Object hashResult : hashResults) {
            if (!(hashResult instanceof Map)) continue;
            Map<Object, Object> hash = (Map<Object, Object>) hashResult;
            if (hash.isEmpty()) continue;

            try {
                String existColor = hash.get("color") != null ? hash.get("color").toString() : "green";
                double existWidthStart = Double.parseDouble(hash.get("widthStart").toString());
                double existWidthEnd = Double.parseDouble(hash.get("widthEnd").toString());
                long existDetectionTime = Long.parseLong(hash.get("detectionTime").toString());

                if (isMergeEligible(incoming, incomingColor, existColor,
                        existDetectionTime, existWidthStart, existWidthEnd, mergeWindowMs)) {
                    DetectedEntity existing = new DetectedEntity();
                    existing.setEntityId(hash.get("entityId").toString());
                    existing.setDetectionTime(existDetectionTime);
                    existing.setStartTime(Long.parseLong(hash.get("startTime").toString()));
                    existing.setEndTime(Long.parseLong(hash.get("endTime").toString()));
                    existing.setWidthStart(existWidthStart);
                    existing.setWidthEnd(existWidthEnd);
                    existing.setAmplitude(Double.parseDouble(hash.get("amplitude").toString()));
                    existing.setColor(existColor);
                    return existing;
                }
            } catch (Exception e) {
                log.warn("Error checking merge candidate: {}", e.getMessage());
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Private helper: save entity to hash + both sorted sets
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void saveEntity(DetectedEntity entity) {
        String entityKey = "entity:" + entity.getEntityId();
        Map<String, Object> hash = entityToHash(entity);

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().putAll(entityKey, hash);
                operations.opsForZSet().add(BY_TIME_KEY, entity.getEntityId(), entity.getDetectionTime());
                operations.opsForZSet().add(BY_FREQ_KEY, entity.getEntityId(), centerFreq(entity));
                return null;
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────
    //  Merge event builders
    // ────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildMergeStartData(DetectedEntity target, DetectedEntity incoming) {
        Map<String, Object> data = new HashMap<>();
        data.put("targetId", target.getEntityId());
        data.put("incomingId", incoming.getEntityId());
        data.put("targetWidthStart", target.getWidthStart());
        data.put("targetWidthEnd", target.getWidthEnd());
        data.put("targetStartTime", target.getStartTime());
        data.put("targetEndTime", target.getEndTime());
        data.put("incomingWidthStart", incoming.getWidthStart());
        data.put("incomingWidthEnd", incoming.getWidthEnd());
        data.put("incomingStartTime", incoming.getStartTime());
        data.put("incomingEndTime", incoming.getEndTime());
        data.put("color", target.getColor());
        return data;
    }

    private Map<String, Object> buildMergeEndData(DetectedEntity merged, DetectedEntity absorbed) {
        Map<String, Object> data = new HashMap<>();
        data.put("entityId", merged.getEntityId());
        data.put("widthStart", merged.getWidthStart());
        data.put("widthEnd", merged.getWidthEnd());
        data.put("startTime", merged.getStartTime());
        data.put("endTime", merged.getEndTime());
        data.put("amplitude", merged.getAmplitude());
        data.put("absorbedId", absorbed.getEntityId());
        data.put("color", merged.getColor());
        return data;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Scheduled tasks
    // ────────────────────────────────────────────────────────────────────

    /**
     * Cleans up expired entities using ZRANGEBYSCORE on the time index.
     * No SCAN needed — the sorted set IS the index.
     */
    @SuppressWarnings("unchecked")
    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        try {
            long now = System.currentTimeMillis();
            long cutoff = now - (entityTtlSeconds * 1000L);

            // Get all expired entity IDs from the time index
            Set<Object> expiredIds = redisTemplate.opsForZSet().rangeByScore(BY_TIME_KEY, 0, cutoff);
            if (expiredIds == null || expiredIds.isEmpty()) return;

            // Pipeline: remove from both sorted sets + delete hashes
            List<Object> expiredList = new ArrayList<>(expiredIds);
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (Object id : expiredList) {
                        operations.opsForZSet().remove(BY_TIME_KEY, id);
                        operations.opsForZSet().remove(BY_FREQ_KEY, id);
                        operations.delete("entity:" + id);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.debug("Cleanup cycle: {}", e.getMessage());
        }
    }

    /**
     * Broadcasts the complete Redis entity state to all WebSocket clients.
     */
    @Scheduled(fixedRate = 500)
    public void broadcastMergedEntities() {
        if (!broadcastEnabled) return;
        try {
            List<Map<String, String>> all = getQueueState();
            wsHandler.broadcast("redis-entities", all);
        } catch (Exception e) {
            log.debug("broadcastMergedEntities: {}", e.getMessage());
        }
    }

    /**
     * Returns all entities currently stored in Redis.
     * Uses the time sorted set as the index, then pipelines HGETALL.
     */
    public List<Map<String, String>> getQueueState() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            Set<Object> allIds = redisTemplate.opsForZSet().range(BY_TIME_KEY, 0, -1);
            if (allIds == null || allIds.isEmpty()) return result;

            List<String> keyList = new ArrayList<>(allIds.size());
            for (Object id : allIds) {
                keyList.add("entity:" + id);
            }

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

    /**
     * Restores entities from a snapshot (used for failover state sync).
     * Flushes current Redis data and repopulates.
     */
    public void restoreFromSnapshot(List<Map<String, String>> snapshot) {
        try {
            // Clear existing data
            redisTemplate.delete(BY_TIME_KEY);
            redisTemplate.delete(BY_FREQ_KEY);

            // Get and delete all existing entity hashes
            Set<Object> existingIds = redisTemplate.opsForZSet().range(BY_TIME_KEY, 0, -1);
            if (existingIds != null) {
                for (Object id : existingIds) {
                    redisTemplate.delete("entity:" + id);
                }
            }

            if (snapshot.isEmpty()) return;

            // Repopulate
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (Map<String, String> entityData : snapshot) {
                        String entityId = entityData.get("entityId");
                        if (entityId == null) continue;

                        String entityKey = "entity:" + entityId;
                        Map<String, Object> hash = new HashMap<>(entityData);
                        operations.opsForHash().putAll(entityKey, hash);

                        double detTime = Double.parseDouble(entityData.getOrDefault("detectionTime", "0"));
                        operations.opsForZSet().add(BY_TIME_KEY, entityId, detTime);

                        double ws = Double.parseDouble(entityData.getOrDefault("widthStart", "0"));
                        double we = Double.parseDouble(entityData.getOrDefault("widthEnd", "0"));
                        operations.opsForZSet().add(BY_FREQ_KEY, entityId, (ws + we) / 2.0);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.error("Failed to restore from snapshot: {}", e.getMessage());
        }
    }
}
