# Signal Queue — Real-Time Entity Detection with Redis Sentinel

A real-time signal processing and entity detection system backed by a 3-node Redis Sentinel cluster. Features a live waterfall visualization, intelligent entity merging, batch-pipelined Redis operations, and interactive crash/recovery testing to demonstrate Redis failover in action.

![Architecture](https://img.shields.io/badge/Redis-Sentinel_Cluster-red?style=flat-square) ![Java](https://img.shields.io/badge/Java-17-blue?style=flat-square) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green?style=flat-square) ![Docker](https://img.shields.io/badge/Docker-Compose-blue?style=flat-square) ![Lettuce](https://img.shields.io/badge/Lettuce-Connection_Pool-orange?style=flat-square)

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) in your browser. That's it — 8 containers start automatically.

You'll see two stacked waterfall displays: the top shows raw signal blocks, the bottom shows detected and merged entities flowing through Redis. A sidebar gives you full control over signal generation, detection parameters, and Redis node crash/recovery.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Docker Compose (8 containers)             │
│                                                                  │
│  ┌──────────────┐  replicaof   ┌─────────────────────────────┐  │
│  │ redis-master  │◄────────────│ redis-replica-1              │  │
│  │  :6379        │◄────────────│ redis-replica-2              │  │
│  └──────┬───────┘              └─────────────────────────────┘  │
│         │ monitored by                                           │
│  ┌──────┴────────────────────────────────────────────────────┐  │
│  │  sentinel-1 (:26379)                                       │  │
│  │  sentinel-2 (:26380)     quorum = 2 of 3                  │  │
│  │  sentinel-3 (:26381)                                       │  │
│  └──────┬────────────────────────────────────────────────────┘  │
│         │ Lettuce discovers master via Sentinel                  │
│  ┌──────┴───────┐                                               │
│  │  app (Spring  │  Docker socket mounted for                    │
│  │  Boot :8080)  │  crash/recovery control                       │
│  └──────────────┘                                               │
└──────────────────────────────────────────────────────────────────┘
```

| Container | Role | Port |
|-----------|------|------|
| `redis-master` | Primary Redis node (writes + reads) | 6379 |
| `redis-replica-1` | Replica (reads, failover candidate) | 6380 |
| `redis-replica-2` | Replica (reads, failover candidate) | 6381 |
| `sentinel-1` | Monitors master, votes on failover | 26379 |
| `sentinel-2` | Monitors master, votes on failover | 26380 |
| `sentinel-3` | Monitors master, votes on failover | 26381 |
| `app` | Spring Boot application + WebSocket server | 8080 |

The app container also has the Docker CLI installed and the host's Docker socket mounted (`/var/run/docker.sock`), enabling it to stop/start Redis containers for crash testing.

---

## How It Works

### Signal Pipeline

```
SignalGenerator                          (configurable tick rate: 1-1000 t/s)
    │  Generates random signal blocks
    │  (frequency band + amplitude + duration + color)
    │
    ▼
DetectorService                          (sliding window across frequency axis)
    │  Scans blocks with configurable window width & overlap
    │  Detects entities with configurable probability
    │  Hands off via ArrayBlockingQueue (decoupled from tick)
    │
    ▼
QueueService.publishBatch()              (3 Redis RTTs for entire batch)
    │  RTT 1: Fetch all time-slot sorted sets
    │  RTT 2: Fetch all candidate entity hashes
    │  Local:  Evaluate merges (color + time + width overlap)
    │  RTT 3: Save all entities + update sorted sets
    │
    ▼
SignalWebSocketHandler.broadcast()       (async per-session queues)
    │  Non-blocking offer to bounded queues
    │  Dedicated sender threads drain per session
    │
    ▼
Browser (index.html + waterfall.js)      (Canvas rendering @ 60fps)
    Two stacked waterfalls: raw signals + merged entities
```

### WebSocket Message Flow

The server pushes these message types in real-time:

| Message | Frequency | Content |
|---------|-----------|---------|
| `blocks` | Every tick | All visible signal blocks + axis config |
| `detector` | Every tick | Detector window position/width |
| `entity` | Per detection | New or merged entity event |
| `entities` | Every tick | Active entity count |
| `merge-start` | Per merge | Pre-merge state of both entities |
| `merge-end` | Per merge | Post-merge result |
| `redis-entities` | Every 500ms | Full entity snapshot from Redis |
| `redis-status` | Every 2s | Node running/role status |
| `throughput` | Every 1s | Entities/sec + total count |

---

## Redis Deep Dive

### Why Redis?

Redis is an in-memory data structure server. For this system, it provides:

- **Sub-millisecond latency**: All data lives in RAM. Hash lookups and sorted set operations are O(1) or O(log N).
- **100K+ operations/sec on a single node**: Redis uses a single-threaded event loop — no lock contention, no context switching. This sounds limiting but actually enables extreme throughput because there's zero overhead from thread synchronization.
- **Native data structures**: Hashes for entity storage, sorted sets for time-bucketed lookups — no serialization/deserialization of entire objects.
- **Built-in TTL**: Keys expire automatically. No application-side garbage collection.
- **Pipelining**: Batch N commands into a single network round-trip, amortizing TCP overhead.
- **Pub/Sub + Sentinel**: Automatic failover notification to clients.

### Redis vs. Alternatives

| Feature | Redis | Memcached | Kafka | PostgreSQL |
|---------|-------|-----------|-------|------------|
| Latency | ~0.1ms | ~0.1ms | ~5ms | ~1-5ms |
| Data structures | Hashes, sorted sets, lists, streams | Key-value only | Append-only log | Tables (SQL) |
| TTL expiry | Built-in per-key | Built-in per-key | Retention policy | Manual cleanup |
| Throughput (single node) | 100K+ ops/sec | 100K+ ops/sec | 100K+ msgs/sec | ~10K queries/sec |
| High availability | Sentinel / Cluster | None built-in | Broker replication | Streaming replication |
| Pipelining | Yes (batch N cmds) | Multi-get only | Batched produce | Prepared statements |
| Best for | Real-time state + TTL | Simple caching | Event streaming | ACID transactions |

**Why not Memcached?** No sorted sets, no hashes, no pipelining of mixed commands. We need structured data + range queries.
**Why not Kafka?** Kafka is append-only — great for event logs, wrong model for mutable entity state with TTL.
**Why not PostgreSQL?** 10-50x higher latency for point lookups. No native TTL. Connection overhead at high concurrency.

### Data Model

Each detected entity is stored using two Redis structures:

#### Entity Hash — `entity:{id}`

Stores all properties with a configurable TTL (default 30s):

```
HSET entity:E-a1b2c3d4
    entityId     "E-a1b2c3d4"
    detectionTime "1710000000000"
    startTime     "1710000000000"
    endTime       "1710000005000"
    widthStart    "1500.0"
    widthEnd      "1800.0"
    amplitude     "0.75"
    color         "yellow"
EXPIRE entity:E-a1b2c3d4 30
```

#### Time-Slot Sorted Set — `entities:{timestamp/1000}`

Groups entities into 1-second buckets. Score = `widthStart` for spatial ordering:

```
ZADD entities:1710000000 1500.0 "E-a1b2c3d4"
ZADD entities:1710000000 3200.0 "E-f5e6d7c8"
EXPIRE entities:1710000000 30
```

This enables the merge algorithm to quickly find nearby entities: query 3 sorted sets (current ±1 second), then batch-fetch all candidate hashes.

### TTL-Based Expiry

- Every entity hash and time-slot sorted set gets a Redis TTL
- Redis automatically evicts expired keys — **zero application-side garbage collection**
- A background cleanup task (`@Scheduled(fixedRate = 5000)`) catches orphaned sorted set entries using `SCAN` (non-blocking)
- No `DEL` storms: expiry is amortized across Redis's lazy + active expiry mechanisms

### Pipelining

Without pipelining, each Redis command requires a full network round-trip (RTT ≈ 0.1-0.5ms local, 1-5ms network). Pipelining batches multiple commands into a single RTT:

```
Without pipelining:          With pipelining:
  HSET  →  ack (1 RTT)        HSET  ┐
  EXPIRE → ack (1 RTT)        EXPIRE│
  ZADD  →  ack (1 RTT)        ZADD  ├── 1 RTT for all 4
  EXPIRE → ack (1 RTT)        EXPIRE┘
  = 4 RTTs                    = 1 RTT
```

Our batch publish goes further — for N entities per tick:

| Approach | RTTs | At 50 entities/tick |
|----------|------|---------------------|
| Naive (per-entity) | 3×N | 150 RTTs |
| Pipelined per-entity | N | 50 RTTs |
| **Batch publish** | **3 total** | **3 RTTs** |

### How Redis Handles 100K+ ops/sec

Redis achieves this through its architecture:

1. **Single-threaded event loop**: All commands execute sequentially on one CPU core. No locks, no mutexes, no thread synchronization.
2. **Non-blocking I/O multiplexing**: Uses `epoll`/`kqueue` to handle thousands of connections without thread-per-connection overhead.
3. **In-memory**: All reads/writes go to RAM. No disk I/O in the hot path (AOF writes are async).
4. **Efficient data structures**: Hashes use zipmaps for small cardinality, sorted sets use skip lists — all cache-friendly.
5. **Pipelining**: Clients send batches, reducing per-command syscall overhead.

### Scaling Redis

| Scale | Approach | Throughput |
|-------|----------|------------|
| **Vertical** | Bigger instance (more RAM, faster CPU) | Millions of ops/sec on modern hardware |
| **Sentinel** (what we use) | 1 master + N replicas, automatic failover | Read scaling across replicas, ~100K+ writes/sec on master |
| **Cluster** | Horizontal sharding across N masters | N × 100K+ ops/sec (linear scaling) |

**Vertical scaling** is often underestimated — a single Redis instance on a modern server (fast CPU, plenty of RAM) can handle millions of simple operations per second. For most use cases, this is enough.

**Sentinel** (our approach) adds high availability without sharding complexity. Reads distribute across replicas via `ReadFrom.REPLICA_PREFERRED`. If the master dies, a replica is promoted automatically.

**Cluster** is for when a single node's memory isn't enough, or you need to exceed single-node write throughput. Data is sharded across 16,384 hash slots distributed among masters.

---

## Redis Sentinel & Failover

### Why Sentinel, Not Cluster?

| | Sentinel | Cluster |
|---|---------|---------|
| Data distribution | Full dataset on every node | Sharded across nodes |
| Complexity | Low — just add sentinels | High — hash slots, resharding, multi-key restrictions |
| Use case | HA + read scaling | Horizontal scaling beyond single-node limits |
| Our dataset size | Fits easily in one node | Sharding not needed |

Since our entity queue fits in a single node's memory (entities TTL out in 30s), Sentinel gives us failover without hash slot complexity.

### Failover Sequence

```
1. Master goes down (you click STOP)
   │
2. Sentinels detect failure
   │  Each sentinel pings master every 100ms (default)
   │  After 1000ms with no response → SDOWN (subjectively down)
   │
3. Quorum reached (2 of 3 sentinels agree)
   │  Master is marked ODOWN (objectively down)
   │
4. Leader election among sentinels
   │  One sentinel wins via Raft-like election
   │  This sentinel will orchestrate the failover
   │
5. Replica promotion
   │  The leader picks the best replica:
   │    • Highest replication offset (most data)
   │    • Lower replica ID as tiebreaker
   │  Issues REPLICAOF NO ONE to promote it
   │
6. Reconfiguration
   │  Other replicas are told to replicate from the new master
   │  parallel-syncs=2 means both replicas resync simultaneously
   │
7. Application reconnects
   Lettuce receives Sentinel pub/sub notification
   ReadFrom.REPLICA_PREFERRED re-routes reads to available replicas
   Writes go to the new master — total downtime: ~2-4 seconds
```

### Read Distribution

The app uses `ReadFrom.REPLICA_PREFERRED`:

```
                    ┌─── Read ───→ replica-1  (preferred)
Client request ─────┤
                    ├─── Read ───→ replica-2  (preferred)
                    │
                    └─── Read ───→ master     (fallback)

All writes ─────────────────────→ master      (always)
```

- **Reads** prefer replicas, distributing load away from the master
- **Writes** always go to the master (Redis replication is async, only master accepts writes)
- During failover, reads continue on surviving replicas while the new master is elected

### Crash Scenarios

| Scenario | Result | Data Loss? |
|----------|--------|------------|
| Stop master | Sentinel promotes a replica in ~2-4s. App reconnects automatically. | Possible loss of last ~1s of async-replicated writes |
| Stop 1 replica | Reads continue on remaining replica + master. No impact. | None |
| Stop 2 replicas | All reads go to master. Still fully functional. | None |
| Stop all 3 Redis nodes | App loses Redis connectivity. Signal generation continues, entities fail to persist. | Entities in Redis are lost; new ones created on recovery |
| Stop master + 1 replica | Remaining replica promoted. Single-node operation. | Same as master stop |
| Stop all sentinels | No failover capability, but current master keeps working. | None (until master dies) |

### Sentinel Configuration Explained

```conf
# sentinel/sentinel.conf

sentinel monitor mymaster redis-master 6379 2
#                 ↑ name   ↑ host       ↑port ↑ quorum (2 of 3 must agree for ODOWN)

sentinel down-after-milliseconds mymaster 1000
#        ↑ How long before SDOWN (1 second — aggressive for demo; production: 5000-30000)

sentinel failover-timeout mymaster 3000
#        ↑ Max time for entire failover (3 seconds — aggressive; production: 60000-180000)

sentinel parallel-syncs mymaster 2
#        ↑ How many replicas resync simultaneously (2 = both at once)

sentinel resolve-hostnames yes
sentinel announce-hostnames yes
#        ↑ Required for Docker — containers use hostnames, not IPs
```

---

## Entity Merging Algorithm

### Problem

The sliding detector window generates overlapping detections for the same physical signal. Without merging, a single signal block could produce dozens of duplicate entities.

### Merge Logic (Step by Step)

```
For each new detected entity:
│
├─ 1. Compute time slot: floor(startTime / 1000)
│
├─ 2. Query Redis sorted sets for slots [t-1, t, t+1]
│     → Returns all entity IDs in those 3-second windows
│
├─ 3. Fetch all candidate entity hashes from Redis
│     → Returns full properties for each candidate
│
├─ 4. For each candidate, check merge criteria:
│     │
│     ├─ a. Color match?  (only same colors merge)
│     │     incoming.color == candidate.color
│     │
│     ├─ b. Time proximity?
│     │     |incoming.startTime - candidate.startTime| ≤ mergeTimeThresholdMs
│     │
│     └─ c. Width overlap?
│           overlapStart = max(incoming.widthStart, candidate.widthStart)
│           overlapEnd   = min(incoming.widthEnd,   candidate.widthEnd)
│           overlap      = max(0, overlapEnd - overlapStart)
│           minWidth     = min(incoming width, candidate width)
│           overlap / minWidth × 100 ≥ mergeWidthOverlapPercent?
│
├─ 5a. MERGE: Expand existing entity's boundaries
│      merged.startTime  = min(both startTimes)
│      merged.endTime    = max(both endTimes)
│      merged.widthStart = min(both widthStarts)
│      merged.widthEnd   = max(both widthEnds)
│      merged.amplitude  = max(both amplitudes)
│
└─ 5b. NO MATCH: Insert as new entity
```

### Visual Example

```
Before merge:                After merge:
Hz                           Hz
5000 ┤                       5000 ┤
     │  ████ Entity A             │  ██████████ Entity A
3000 ┤  ████                 3000 ┤  ██████████ (expanded)
     │      ████ Entity B         │
1000 ┤      ████             1000 ┤
     └──────────── time           └──────────── time
     Entity B absorbed into Entity A
     (same color, overlapping time & frequency)
```

### Batch Merge — Redis Data Flow

With batch publishing, the entire merge evaluation for N entities uses exactly 3 Redis round-trips:

```
          App                           Redis
           │                              │
           │── Pipeline: ZRANGE ×3 ──────→│  RTT 1: Fetch time-slot sorted sets
           │←── All entity IDs ───────────│
           │                              │
           │── Pipeline: HGETALL ×M ─────→│  RTT 2: Fetch all candidate hashes
           │←── All hash data ────────────│
           │                              │
           │  (local merge evaluation)    │  0 RTTs
           │                              │
           │── Pipeline: HSET+EXPIRE ×K ─→│  RTT 3: Save all results
           │←── OK ──────────────────────│
           │                              │
           Total: 3 RTTs regardless of N
```

---

## Performance & Scalability

### Current Throughput

With the scalability improvements in place:

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Redis RTTs per tick (50 entities) | ~150 | **3** | **50x fewer** |
| Key scanning (cleanup/broadcast) | `KEYS` O(N) blocking | `SCAN` non-blocking | No more event loop stalls |
| WebSocket broadcast | Synchronous per-session | Async bounded queues | Non-blocking tick thread |
| Tick → Detect coupling | Synchronous call | `ArrayBlockingQueue` handoff | Ticks never stall on slow Redis |
| Redis connections | Single shared connection | **16-connection pool** | Better concurrency |

At default settings (10 ticks/sec, ~25 entities/tick), the system comfortably handles ~250 entities/sec. At maximum tick rate (1000 t/s), throughput reaches **1,500-2,500 entities/sec** depending on merge rate.

### Bottleneck Analysis & Improvements

#### 1. Batch Entity Publishing (HIGH IMPACT)

**Before**: Each entity required 3 Redis round-trips (fetch slots → fetch candidates → save). At 50 entities/tick, that's 150 RTTs per tick.

**After**: `publishBatch()` collects all time slots from all entities, fetches all sorted sets in 1 pipeline (1 RTT), fetches all candidate hashes in 1 pipeline (1 RTT), evaluates merges locally (0 RTTs), and saves all entities in 1 pipeline (1 RTT). **3 RTTs total regardless of batch size.**

#### 2. SCAN Instead of KEYS (MEDIUM IMPACT)

**Before**: `redisTemplate.keys("entities:*")` is O(N) and **blocks the Redis event loop** for the entire scan. Called every 500ms and every 5s.

**After**: Uses `SCAN` with `COUNT 100` hint. The cursor iterates incrementally without blocking other commands. Cleanup also uses pipelined batch deletes instead of individual `DELETE` calls.

#### 3. Async WebSocket Broadcasting (MEDIUM IMPACT)

**Before**: `broadcast()` serialized JSON, then sent to each session synchronously with `synchronized(session)`. If one client was slow, it blocked the tick thread.

**After**: Each WebSocket session gets a bounded queue (64 messages). `broadcast()` does a non-blocking `offer()` into each queue. Dedicated sender threads drain per session. If a client can't keep up, messages are dropped (not backed up).

#### 4. Decoupled Tick from Detection (MEDIUM IMPACT)

**Before**: `tick()` called `detect()` synchronously. If Redis was slow during entity publishing, ticks would back up and the scheduler would fall behind.

**After**: `tick()` offers `(blocks, timestamp)` onto an `ArrayBlockingQueue<DetectRequest>(32)`. A dedicated drain thread processes detection independently. If detection can't keep up, requests are dropped — ticks never stall.

#### 5. Lettuce Connection Pool (LOW IMPACT)

**Before**: Default Lettuce uses a single connection per connection type. Concurrent threads share it.

**After**: `commons-pool2` provides a pool of 16 connections (8 idle). Concurrent pipelines don't contend for the same connection.

### Scaling Roadmap

#### 10x Scale (~25,000 entities/sec)

- Increase tick rate + blocks/tick
- Redis single node handles it easily (100K+ ops/sec)
- May need to increase WebSocket queue depth
- Consider adding more replicas for read distribution

#### 100x Scale (~250,000 entities/sec)

- **Redis Cluster**: Shard entities across 3-6 masters (each handling 50K-100K ops/sec)
- **Multiple app instances**: Load-balanced behind nginx, sticky WebSocket sessions
- **Kafka ingest**: Decouple signal generation from detection with a Kafka topic
- **Batch size tuning**: Larger batches = fewer RTTs = higher throughput

#### Redis Scaling Numbers

| Configuration | Throughput |
|--------------|------------|
| Single node (what we use) | 100K-200K ops/sec |
| Single node + pipelining | 500K-1M ops/sec |
| Cluster (3 masters) | 300K-600K ops/sec |
| Cluster (6 masters) | 600K-1.2M ops/sec |
| Cluster (3 masters) + pipelining | 1.5M-3M ops/sec |

---

## UI Controls

### Redis Cluster Panel

- **Node indicators**: Green = running, Red = down
- **Role labels**: Shows `master` or `slave` for each node (updates after failover)
- **STOP/START buttons**: Per-node crash/recovery via Docker API
- **CRASH ALL / RECOVER ALL**: Full cluster control
- **Entities/sec**: Live throughput counter
- **Total Entities**: Cumulative entity count

### Settings Panel

| Setting | Range | Default | Description |
|---------|-------|---------|-------------|
| Max width | 100–100,000 | 10,000 | Frequency axis range (Hz) |
| Retention | 1–120s | 30s | How long blocks remain visible |
| Blocks/tick | 0–500 | 50 | Signal blocks generated per tick |
| Min duration | 100ms+ | 500ms | Minimum signal block lifetime |
| Max duration | Min duration+ | 5,000ms | Maximum signal block lifetime |
| Min block width | 5+ | 20 | Minimum frequency band width |
| Max block width | Min width – Max width | 300 | Maximum frequency band width |
| Detector width % | 1–100% | 10% | Detector window width as % of max width |
| Overlap % | 0–90% | 50% | Detector window step overlap |
| Det. time window | 100–30,000ms | 1,000ms | How far back the detector looks |
| Detection prob. | 0–100% | 100% | Probability of detecting an intersecting block |
| Entity TTL | 5–3,600s | 30s | How long entities persist in Redis |
| Tick rate | 1–1,000 t/s | 10 t/s | Signal generation speed |
| Merge time threshold | Config only | 1,000ms | Max time gap for merge eligibility |
| Merge width overlap | Config only | 50% | Min width overlap for merge |

### Pause Button

Pauses signal generation on the server. The waterfall freezes. Click RESUME to continue. Useful for examining a snapshot.

---

## API Reference

### Settings

**`GET /api/settings`** — Returns all current configuration values.

```json
{
  "maxBlocksPerTick": 50,
  "minBlockDurationMs": 500,
  "maxBlockDurationMs": 5000,
  "minBlockWidth": 20.0,
  "maxBlockWidth": 300.0,
  "detectorWindowWidthPercent": 10,
  "detectorOverlapPercent": 50,
  "detectorTimeWindowMs": 1000,
  "detectionProbability": 100,
  "maxWidth": 10000,
  "retentionMs": 30000,
  "entityTtlSeconds": 30,
  "tickIntervalMs": 100,
  "paused": false
}
```

**`POST /api/settings`** — Update any subset of settings. Returns the full updated config.

```bash
curl -X POST http://localhost:8080/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"maxBlocksPerTick": 100, "tickIntervalMs": 10}'
```

### Redis Node Control

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/redis/stop/{node}` | POST | Stop a Redis container (e.g., `redis-master`) |
| `/api/redis/start/{node}` | POST | Start a stopped container |
| `/api/redis/stop-all` | POST | Stop all 3 Redis nodes |
| `/api/redis/start-all` | POST | Start all 3 Redis nodes |
| `/api/redis/status` | GET | Current state of all nodes |

**Status response example:**

```json
[
  {"name": "redis-master", "running": true, "status": "running", "role": "master"},
  {"name": "redis-replica-1", "running": true, "status": "running", "role": "slave"},
  {"name": "redis-replica-2", "running": false, "status": "exited", "role": "down"}
]
```

### WebSocket — `ws://localhost:8080/ws`

All messages are JSON with the structure `{"type": "<type>", "data": <payload>}`.

| Type | Direction | Payload Schema |
|------|-----------|----------------|
| `blocks` | Server→Client | `{blocks: [{id, startTime, endTime, widthStart, widthEnd, amplitude, color}], maxWidth, retentionMs}` |
| `detector` | Server→Client | `{position, width, timeWindowMs}` |
| `entity` | Server→Client | `{type: "new"|"merged", entity: {entityId, startTime, endTime, widthStart, widthEnd, amplitude, color}}` |
| `entities` | Server→Client | `[{entityId, startTime, endTime, widthStart, widthEnd, amplitude, color}]` |
| `merge-start` | Server→Client | `{targetId, incomingId, targetWidthStart, targetWidthEnd, targetStartTime, targetEndTime, incomingWidthStart, incomingWidthEnd, incomingStartTime, incomingEndTime, color}` |
| `merge-end` | Server→Client | `{entityId, widthStart, widthEnd, startTime, endTime, amplitude, absorbedId, color}` |
| `redis-entities` | Server→Client | `[{entityId, startTime, endTime, widthStart, widthEnd, amplitude, color}]` |
| `redis-status` | Server→Client | `[{name, running, status, role}]` |
| `throughput` | Server→Client | `{entitiesPerSecond, totalEntities}` |

---

## Configuration Reference

### `application.yml`

```yaml
signal:
  tick-interval-ms: 100         # Milliseconds between ticks (1-1000)
  max-width: 10000              # Frequency axis range
  max-blocks-per-tick: 50       # Max new signal blocks per tick (0-500)
  min-block-duration-ms: 500    # Min block lifetime in ms
  max-block-duration-ms: 5000   # Max block lifetime in ms
  min-block-width: 20           # Min frequency band width
  max-block-width: 300          # Max frequency band width
  retention-ms: 30000           # How long blocks stay visible (ms)

detector:
  window-width-percent: 10      # Detector window as % of max-width (1-100)
  overlap-percent: 50           # Window step overlap (0-90)
  time-window-ms: 1000          # Detection lookback window (ms)
  detection-probability: 100    # Detection probability (0-100%)

queue:
  entity-ttl-seconds: 30        # Redis TTL for entities (5-3600)
  merge-time-threshold-ms: 1000 # Max time gap for merge
  merge-width-overlap-percent: 50  # Min overlap % for merge

spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: localhost:26379,localhost:26380,localhost:26381
      timeout: 3000ms

redis:
  nodes: redis-master,redis-replica-1,redis-replica-2  # Docker container names
```

### `sentinel/sentinel.conf`

```conf
port 26379
sentinel monitor mymaster redis-master 6379 2
sentinel down-after-milliseconds mymaster 1000
sentinel failover-timeout mymaster 3000
sentinel parallel-syncs mymaster 2
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
```

---

## Project Structure

```
├── docker-compose.yml                  # 8-container orchestration
├── Dockerfile                          # Multi-stage build (JDK 17 + Docker CLI)
├── build.gradle.kts                    # Dependencies (Spring Boot, Redis, Pool, WebSocket)
├── sentinel/
│   └── sentinel.conf                   # Sentinel monitoring config
├── src/main/
│   ├── java/com/df/queue/
│   │   ├── QueueApplication.java       # Entry point (@EnableScheduling)
│   │   ├── config/
│   │   │   ├── RedisConfig.java        # Lettuce pooling + Sentinel + ReadFrom config
│   │   │   └── WebSocketConfig.java    # Registers /ws endpoint
│   │   ├── model/
│   │   │   ├── SignalBlock.java        # Raw signal (freq band + amplitude + color)
│   │   │   ├── DetectedEntity.java     # Entity from detection (extends block)
│   │   │   └── EntityMessage.java      # WebSocket wrapper (new/merged + entity)
│   │   ├── service/
│   │   │   ├── SignalGenerator.java    # Tick-based random signal generation
│   │   │   ├── DetectorService.java    # Sliding window detection + drain thread
│   │   │   ├── QueueService.java       # Redis storage, batch publish, SCAN, merging
│   │   │   ├── ThroughputService.java  # Entities/sec metrics broadcast
│   │   │   └── RedisStatusService.java # Docker container polling (2s interval)
│   │   └── web/
│   │       ├── SignalWebSocketHandler.java  # Async per-session broadcast queues
│   │       ├── SettingsController.java      # GET/POST /api/settings
│   │       └── RedisNodeController.java     # Crash/recovery REST API
│   └── resources/
│       ├── application.yml             # All configurable properties
│       └── static/
│           ├── index.html              # UI layout + CSS (sidebar, canvases)
│           └── js/waterfall.js         # Canvas rendering, WS client, controls
```

---

## Testing Resilience

### 1. Basic Failover

1. Start the system: `docker compose up --build`
2. Confirm all 3 nodes are green in the Redis Cluster panel
3. Click **STOP** on `redis-master`
4. Watch: indicator goes red → a replica's role changes to `master` (~2-4s)
5. Signal processing continues uninterrupted
6. Click **START** on the stopped node — it rejoins as a replica

### 2. Full Cluster Crash

1. Click **CRASH ALL** — all nodes go red
2. Signal generation continues but entities fail to persist (Redis unavailable)
3. Click **RECOVER ALL** — nodes restart, Sentinel re-establishes replication
4. App reconnects automatically, entities flow again

### 3. Cascading Failure

1. Stop the master → wait for failover (~2-4s)
2. Stop the new master → second failover
3. Only one node remains — it becomes master
4. Start both stopped nodes — they rejoin as replicas

### 4. Replica Failure

1. Stop `redis-replica-1` → reads shift to `replica-2` + master
2. Stop `redis-replica-2` → all reads go to master
3. Start both replicas → reads distribute again

### 5. Network Partition (simulated)

1. Stop 2 of 3 sentinels → failover can't happen (no quorum)
2. Stop the master → sentinels see SDOWN but can't reach ODOWN
3. Start a sentinel back → quorum restored, failover proceeds

---

## Requirements

- **Docker** and **Docker Compose** (v2)
- **Ports available**: 8080 (app), 6379-6381 (Redis), 26379-26381 (Sentinels)
- **Docker socket access**: The app container mounts `/var/run/docker.sock` for crash testing
- Approximately **512MB RAM** for all 8 containers

---

## License

MIT
