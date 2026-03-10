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
import java.util.concurrent.TimeUnit;

/**
 * Core Redis storage service for detected entities.
 *
 * <p>Handles entity persistence, merge logic, and periodic cleanup.
 * Uses two Redis data structures per entity:
 * <ul>
 *   <li><b>Entity Hash</b> ({@code entity:{id}}) — stores all entity properties with TTL</li>
 *   <li><b>Time-Slot Sorted Set</b> ({@code entities:{timestamp/1000}}) — groups entities into
 *       1-second buckets, scored by widthStart for efficient spatial lookup</li>
 * </ul>
 *
 * <h3>Scalability Features</h3>
 * <ul>
 *   <li>{@link #publishBatch} — 3 Redis RTTs for N entities (vs 3*N naive)</li>
 *   <li>{@link #scanKeys} — non-blocking SCAN replaces blocking KEYS command</li>
 *   <li>Pipelined cleanup — batch fetch + batch delete in 2 RTTs</li>
 * </ul>
 */
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

    // ────────────────────────────────────────────────────────────────────
    //  SCAN-based key lookup (replaces blocking KEYS command)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Incrementally scans Redis for keys matching the given pattern.
     *
     * <p>Uses the {@code SCAN} command with {@code COUNT 100} instead of
     * {@code KEYS}, which is O(N) and blocks the Redis event loop for the
     * entire duration. SCAN yields the event loop between cursor iterations.
     *
     * @param pattern glob-style pattern (e.g. {@code "entity:*"})
     * @return all matching keys (may be empty, never null)
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(
                        ScanOptions.scanOptions().match(pattern).count(100).build())) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.debug("scanKeys error: {}", e.getMessage());
        }
        return keys;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Hash conversion helpers
    // ────────────────────────────────────────────────────────────────────

    /**
     * Converts an entity to a {@code Map<String, Object>} for HSET.
     * All values are stored as Strings for consistent deserialization.
     */
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

    /**
     * Converts an entity to {@code Map<Object, Object>} for local merge candidate tracking.
     * Same data as {@link #entityToHash} but with Object keys to match Redis hash response type.
     */
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

    // ────────────────────────────────────────────────────────────────────
    //  Single-entity publish (retained for backward compatibility)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Publishes a single entity to Redis, checking for merge candidates.
     *
     * <p>Prefer {@link #publishBatch} for multiple entities — it reduces
     * Redis round-trips from 3*N to 3 total.
     *
     * @param entity the newly detected entity
     * @return message indicating "new" or "merged"
     */
    public EntityMessage publish(DetectedEntity entity) {
        try {
            throughputService.recordEntity();

            long timeSlot = entity.getStartTime() / 1000;
            String slotKey = "entities:" + timeSlot;

            // Check nearby time slots for merge candidates (2 RTTs: fetch slots + fetch hashes)
            DetectedEntity merged = findMergeCandidate(entity, timeSlot);
            if (merged != null) {
                wsHandler.broadcast("merge-start", buildMergeStartData(merged, entity));

                // Expand merged entity to encompass both
                merged.setStartTime(Math.min(merged.getStartTime(), entity.getStartTime()));
                merged.setEndTime(Math.max(merged.getEndTime(), entity.getEndTime()));
                merged.setWidthStart(Math.min(merged.getWidthStart(), entity.getWidthStart()));
                merged.setWidthEnd(Math.max(merged.getWidthEnd(), entity.getWidthEnd()));
                merged.setAmplitude(Math.max(merged.getAmplitude(), entity.getAmplitude()));

                saveEntity(merged, slotKey);
                log.debug("Merged entity {} into {}", entity.getEntityId(), merged.getEntityId());

                wsHandler.broadcast("merge-end", buildMergeEndData(merged, entity));
                return new EntityMessage("merged", merged);
            }

            // No merge — insert as new
            saveEntity(entity, slotKey);
            log.debug("New entity {} at width [{}, {}]",
                    entity.getEntityId(), entity.getWidthStart(), entity.getWidthEnd());
            return new EntityMessage("new", entity);

        } catch (Exception e) {
            log.error("Failed to publish entity {}: {}", entity.getEntityId(), e.getMessage());
            return new EntityMessage("new", entity);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Batch entity publish — 3 RTTs total instead of 3 * N
    // ────────────────────────────────────────────────────────────────────

    /**
     * Publishes a batch of entities to Redis using exactly 3 pipelined round-trips.
     *
     * <p><b>Pipeline strategy:</b>
     * <ol>
     *   <li><b>RTT 1</b> — Fetch all relevant time-slot sorted sets (ZRANGE x3 per unique slot)</li>
     *   <li><b>RTT 2</b> — Fetch all candidate entity hashes (HGETALL for each candidate)</li>
     *   <li><b>Local</b> — Evaluate merges in-memory (color + time proximity + width overlap)</li>
     *   <li><b>RTT 3</b> — Save all entities + update sorted sets (HSET + EXPIRE + ZADD + EXPIRE)</li>
     * </ol>
     *
     * <p>For 50 entities/tick, this reduces RTTs from ~150 (naive) to 3.
     *
     * @param entities list of newly detected entities to publish
     * @return list of messages indicating "new" or "merged" for each entity
     */
    @SuppressWarnings("unchecked")
    public List<EntityMessage> publishBatch(List<DetectedEntity> entities) {
        if (entities.isEmpty()) return Collections.emptyList();

        List<EntityMessage> results = new ArrayList<>(entities.size());

        try {
            for (DetectedEntity e : entities) throughputService.recordEntity();

            // ── RTT 1: Fetch all relevant time-slot sorted sets ──────
            // Collect unique slot keys needed (current ± 1 second for each entity)
            Set<String> slotKeysNeeded = new LinkedHashSet<>();
            for (DetectedEntity entity : entities) {
                long ts = entity.getStartTime() / 1000;
                slotKeysNeeded.add("entities:" + (ts - 1));
                slotKeysNeeded.add("entities:" + ts);
                slotKeysNeeded.add("entities:" + (ts + 1));
            }
            List<String> slotKeyList = new ArrayList<>(slotKeysNeeded);

            List<Object> slotResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String sk : slotKeyList) {
                        operations.opsForZSet().range(sk, 0, -1);
                    }
                    return null;
                }
            });

            // Build lookup: slotKey -> set of entity IDs in that slot
            Map<String, Set<Object>> slotMemberMap = new HashMap<>();
            for (int i = 0; i < slotKeyList.size(); i++) {
                Object r = slotResults.get(i);
                if (r instanceof Set) {
                    slotMemberMap.put(slotKeyList.get(i), new LinkedHashSet<>((Set<Object>) r));
                }
            }

            // Gather all unique candidate entity IDs across all slots
            Set<Object> allCandidateIds = new LinkedHashSet<>();
            for (Set<Object> members : slotMemberMap.values()) {
                allCandidateIds.addAll(members);
            }

            // ── RTT 2: Fetch all candidate entity hashes ────────────
            Map<String, Map<Object, Object>> candidateHashMap = new HashMap<>();
            if (!allCandidateIds.isEmpty()) {
                List<String> candidateIdList = new ArrayList<>();
                List<String> candidateKeyList = new ArrayList<>();
                for (Object id : allCandidateIds) {
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
            // Track entities modified during this batch for intra-batch merging
            Map<String, DetectedEntity> modifiedEntities = new HashMap<>();
            // Deduped map of entities to save (last state wins if same entity merged multiple times)
            Map<String, DetectedEntity> toSave = new LinkedHashMap<>();
            Map<String, String> entitySlotKeys = new HashMap<>();

            for (DetectedEntity entity : entities) {
                long timeSlot = entity.getStartTime() / 1000;
                String slotKey = "entities:" + timeSlot;

                // Collect candidate IDs from adjacent time slots
                Set<Object> candidates = new LinkedHashSet<>();
                for (long ts = timeSlot - 1; ts <= timeSlot + 1; ts++) {
                    Set<Object> m = slotMemberMap.get("entities:" + ts);
                    if (m != null) candidates.addAll(m);
                }

                // Evaluate merge using only local data (no Redis calls)
                DetectedEntity merged = findMergeCandidateLocal(
                        entity, candidates, candidateHashMap, modifiedEntities);

                if (merged != null) {
                    wsHandler.broadcast("merge-start", buildMergeStartData(merged, entity));

                    // Expand merged entity boundaries
                    merged.setStartTime(Math.min(merged.getStartTime(), entity.getStartTime()));
                    merged.setEndTime(Math.max(merged.getEndTime(), entity.getEndTime()));
                    merged.setWidthStart(Math.min(merged.getWidthStart(), entity.getWidthStart()));
                    merged.setWidthEnd(Math.max(merged.getWidthEnd(), entity.getWidthEnd()));
                    merged.setAmplitude(Math.max(merged.getAmplitude(), entity.getAmplitude()));

                    // Update local tracking for subsequent entities in this batch
                    modifiedEntities.put(merged.getEntityId(), merged);
                    candidateHashMap.put(merged.getEntityId(), entityToObjectMap(merged));

                    toSave.put(merged.getEntityId(), merged);
                    entitySlotKeys.put(merged.getEntityId(), slotKey);

                    wsHandler.broadcast("merge-end", buildMergeEndData(merged, entity));
                    results.add(new EntityMessage("merged", merged));
                } else {
                    // New entity — add to local tracking for intra-batch merging
                    candidateHashMap.put(entity.getEntityId(), entityToObjectMap(entity));
                    slotMemberMap.computeIfAbsent(slotKey, k -> new LinkedHashSet<>())
                            .add(entity.getEntityId());

                    toSave.put(entity.getEntityId(), entity);
                    entitySlotKeys.put(entity.getEntityId(), slotKey);

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
                        String slotKey = entitySlotKeys.get(e.getEntityId());

                        Map<String, Object> hash = entityToHash(e);
                        operations.opsForHash().putAll(entityKey, hash);
                        operations.expire(entityKey, entityTtlSeconds, TimeUnit.SECONDS);
                        operations.opsForZSet().add(slotKey, e.getEntityId(), e.getWidthStart());
                        operations.expire(slotKey, entityTtlSeconds, TimeUnit.SECONDS);
                    }
                    return null;
                }
            });

            // Broadcast entity events to WebSocket clients
            for (EntityMessage msg : results) {
                wsHandler.broadcast("entity", msg);
            }

        } catch (Exception e) {
            log.error("Batch publish failed: {}", e.getMessage());
            // Graceful degradation — return "new" for all entities
            for (DetectedEntity entity : entities) {
                results.add(new EntityMessage("new", entity));
            }
        }

        return results;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Local merge candidate lookup (zero Redis calls)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Searches for a merge candidate among pre-fetched data (no Redis calls).
     *
     * <p>Checks three criteria in order:
     * <ol>
     *   <li><b>Color match</b> — only same-color entities merge</li>
     *   <li><b>Time proximity</b> — startTime within {@code mergeTimeThresholdMs}</li>
     *   <li><b>Width overlap</b> — overlap / min(widths) >= {@code mergeWidthOverlapPercent}</li>
     * </ol>
     *
     * @param incoming         the new entity to find a merge target for
     * @param candidateIds     entity IDs from nearby time slots
     * @param candidateHashMap pre-fetched hash data keyed by entity ID
     * @param modifiedEntities entities already modified in the current batch
     * @return the merge target entity, or null if no match
     */
    @SuppressWarnings("unchecked")
    private DetectedEntity findMergeCandidateLocal(DetectedEntity incoming, Set<Object> candidateIds,
            Map<String, Map<Object, Object>> candidateHashMap, Map<String, DetectedEntity> modifiedEntities) {

        String incomingColor = incoming.getColor() != null ? incoming.getColor() : "green";

        for (Object candidateId : candidateIds) {
            String idStr = candidateId.toString();

            // Prefer in-batch modified state over the Redis snapshot
            DetectedEntity modified = modifiedEntities.get(idStr);
            if (modified != null) {
                if (isMergeEligible(incoming, incomingColor, modified.getColor(),
                        modified.getStartTime(), modified.getWidthStart(), modified.getWidthEnd())) {
                    return modified;
                }
                continue;
            }

            // Fall back to pre-fetched Redis hash data
            Map<Object, Object> hash = candidateHashMap.get(idStr);
            if (hash == null || hash.isEmpty()) continue;

            try {
                String existColor = hash.get("color") != null ? hash.get("color").toString() : "green";
                double existWidthStart = Double.parseDouble(hash.get("widthStart").toString());
                double existWidthEnd = Double.parseDouble(hash.get("widthEnd").toString());
                long existStartTime = Long.parseLong(hash.get("startTime").toString());

                if (isMergeEligible(incoming, incomingColor, existColor,
                        existStartTime, existWidthStart, existWidthEnd)) {
                    // Reconstruct the full entity from hash data
                    DetectedEntity existing = new DetectedEntity();
                    existing.setEntityId(hash.get("entityId").toString());
                    existing.setDetectionTime(Long.parseLong(hash.get("detectionTime").toString()));
                    existing.setStartTime(existStartTime);
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
     * Evaluates merge eligibility based on color, time proximity, and width overlap.
     *
     * @return true if the incoming entity should merge with the candidate
     */
    private boolean isMergeEligible(DetectedEntity incoming, String incomingColor,
            String candidateColor, long candidateStartTime,
            double candidateWidthStart, double candidateWidthEnd) {
        // 1. Color must match
        if (!incomingColor.equals(candidateColor)) return false;

        // 2. Time proximity check
        if (Math.abs(incoming.getStartTime() - candidateStartTime) > mergeTimeThresholdMs) return false;

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
    //  Merge event builders
    // ────────────────────────────────────────────────────────────────────

    /** Builds the WebSocket payload for a merge-start event (pre-merge state). */
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

    /** Builds the WebSocket payload for a merge-end event (post-merge result). */
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
    //  Private helpers (single-entity path)
    // ────────────────────────────────────────────────────────────────────

    /** Saves an entity hash + sorted set entry in a single pipelined round-trip. */
    @SuppressWarnings("unchecked")
    private void saveEntity(DetectedEntity entity, String slotKey) {
        String entityKey = "entity:" + entity.getEntityId();
        Map<String, Object> hash = entityToHash(entity);

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

    /**
     * Finds a merge candidate for a single entity by querying Redis directly.
     * Uses 2 pipelined RTTs: one for sorted sets, one for candidate hashes.
     */
    @SuppressWarnings("unchecked")
    private DetectedEntity findMergeCandidate(DetectedEntity incoming, long timeSlot) {
        // Pipeline: fetch 3 adjacent time-slot sorted sets
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

        // Collect all candidate entity IDs
        Set<Object> allMembers = new LinkedHashSet<>();
        for (Object result : slotResults) {
            if (result instanceof Set) {
                allMembers.addAll((Set<Object>) result);
            }
        }
        if (allMembers.isEmpty()) return null;

        // Pipeline: fetch all candidate hashes
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

        // Evaluate each candidate
        String incomingColor = incoming.getColor() != null ? incoming.getColor() : "green";

        for (Object hashResult : hashResults) {
            if (!(hashResult instanceof Map)) continue;
            Map<Object, Object> hash = (Map<Object, Object>) hashResult;
            if (hash.isEmpty()) continue;

            try {
                String existColor = (String) hash.get("color");
                double existWidthStart = Double.parseDouble((String) hash.get("widthStart"));
                double existWidthEnd = Double.parseDouble((String) hash.get("widthEnd"));
                long existStartTime = Long.parseLong((String) hash.get("startTime"));

                if (isMergeEligible(incoming, incomingColor, existColor,
                        existStartTime, existWidthStart, existWidthEnd)) {
                    DetectedEntity existing = new DetectedEntity();
                    existing.setEntityId((String) hash.get("entityId"));
                    existing.setDetectionTime(Long.parseLong((String) hash.get("detectionTime")));
                    existing.setStartTime(existStartTime);
                    existing.setEndTime(Long.parseLong((String) hash.get("endTime")));
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

    // ────────────────────────────────────────────────────────────────────
    //  Scheduled tasks
    // ────────────────────────────────────────────────────────────────────

    /**
     * Cleans up expired time-slot sorted sets and their associated entity hashes.
     *
     * <p>Runs every 5 seconds. Uses SCAN (non-blocking) to find slot keys,
     * then pipelined batch fetch + batch delete for efficiency.
     * Redis TTL handles most expiry automatically; this catches orphaned sorted set entries.
     */
    @SuppressWarnings("unchecked")
    @Scheduled(fixedRate = 5000)
    public void cleanup() {
        try {
            Set<String> keys = scanKeys("entities:*");
            if (keys.isEmpty()) return;
            long now = System.currentTimeMillis() / 1000;

            // Identify expired time slots
            List<String> expiredSlotKeys = new ArrayList<>();
            for (String key : keys) {
                try {
                    long slot = Long.parseLong(key.split(":")[1]);
                    if (now - slot > entityTtlSeconds) {
                        expiredSlotKeys.add(key);
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (expiredSlotKeys.isEmpty()) return;

            // Pipeline: fetch all members from expired slots
            List<Object> memberResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String key : expiredSlotKeys) {
                        operations.opsForZSet().range(key, 0, -1);
                    }
                    return null;
                }
            });

            List<String> expiredEntityKeys = new ArrayList<>();
            for (Object result : memberResults) {
                if (result instanceof Set) {
                    for (Object m : (Set<?>) result) {
                        expiredEntityKeys.add("entity:" + m);
                    }
                }
            }

            // Pipeline: batch delete all expired keys
            if (!expiredEntityKeys.isEmpty() || !expiredSlotKeys.isEmpty()) {
                redisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    public Object execute(RedisOperations operations) throws DataAccessException {
                        for (String key : expiredEntityKeys) {
                            operations.delete(key);
                        }
                        for (String key : expiredSlotKeys) {
                            operations.delete(key);
                        }
                        return null;
                    }
                });
            }
        } catch (Exception e) {
            log.debug("Cleanup cycle: {}", e.getMessage());
        }
    }

    /**
     * Broadcasts the complete Redis entity state to all WebSocket clients.
     * Runs every 500ms to keep the merged entity waterfall in sync.
     */
    @Scheduled(fixedRate = 500)
    public void broadcastMergedEntities() {
        try {
            List<Map<String, String>> all = getQueueState();
            wsHandler.broadcast("redis-entities", all);
        } catch (Exception e) {
            log.debug("broadcastMergedEntities: {}", e.getMessage());
        }
    }

    /**
     * Returns all entities currently stored in Redis.
     * Uses SCAN for key lookup and pipelined HGETALL for hash retrieval.
     */
    public List<Map<String, String>> getQueueState() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            Set<String> keys = scanKeys("entity:*");
            if (keys.isEmpty()) return result;

            // Pipeline: fetch all entity hashes in one round-trip
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
