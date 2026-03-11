# Signal Queue — Real-Time Entity Detection with Dual-Service Failover

A real-time signal processing and entity detection system with dual-service active/standby failover. Features tree-search entity merging via Redis sorted set range queries, configurable merge windows up to 120s, live waterfall visualization, batch-pipelined Redis operations, and interactive crash/recovery testing.

![Java](https://img.shields.io/badge/Java-17-blue?style=flat-square) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green?style=flat-square) ![Docker](https://img.shields.io/badge/Docker-Compose-blue?style=flat-square) ![Redis](https://img.shields.io/badge/Redis-Standalone-red?style=flat-square) ![Lettuce](https://img.shields.io/badge/Lettuce-Connection_Pool-orange?style=flat-square)

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) (app-a, active) or [http://localhost:8081](http://localhost:8081) (app-b, standby). Four containers start automatically.

The top waterfall shows raw signal blocks, the bottom shows detected and merged entities from Redis. The sidebar provides controls for signal generation, detection parameters, merge window tuning, and service instance crash/recovery.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                    Docker Compose (4 containers)                    │
│                                                                    │
│  ┌──────────────┐              ┌──────────────┐                   │
│  │  redis-a      │              │  redis-b      │                   │
│  │  :6379        │              │  :6380        │                   │
│  └──────┬───────┘              └──────┬───────┘                   │
│         │                              │                           │
│  ┌──────┴───────┐  HTTP sync   ┌──────┴───────┐                  │
│  │  app-a        │◄────────────►│  app-b        │                  │
│  │  :8080        │  entities    │  :8081        │                  │
│  │  (active)     │  + health    │  (standby)    │                  │
│  └──────────────┘              └──────────────┘                   │
│                                                                    │
│  Active instance: generates signals, detects entities, writes to   │
│  its own Redis AND forwards entities to the standby peer.          │
│  Standby instance: receives forwarded entities, writes to its      │
│  own Redis, ready to promote if the active instance fails.         │
└────────────────────────────────────────────────────────────────────┘
```

| Container | Role | Port |
|-----------|------|------|
| `redis-a` | Standalone Redis for app-a | 6379 |
| `redis-b` | Standalone Redis for app-b | 6380 |
| `app-a` | Spring Boot app (default: active) | 8080 |
| `app-b` | Spring Boot app (default: standby) | 8081 |

Both app containers have the Docker CLI installed and the host's Docker socket mounted, enabling crash/recovery testing via the UI.

---

## How It Works

### Signal Pipeline

```
SignalGenerator                          (configurable tick rate: 1-1000 t/s)
    │  Generates random signal blocks
    │  (gated: only runs on the ACTIVE instance)
    │
    ▼
DetectorService                          (sliding window across frequency axis)
    │  Scans blocks with configurable window width & overlap
    │  Hands off via ArrayBlockingQueue (decoupled from tick)
    │
    ▼
QueueService.publishBatch()              (3 Redis RTTs for entire batch)
    │  RTT 1: ZRANGEBYSCORE on entities:by_time + entities:by_freq
    │  RTT 2: Pipeline HGETALL for intersection of both sets
    │  Local:  Evaluate merges (color + center freq + width overlap)
    │  RTT 3: Pipeline HSET + ZADD (both sorted sets) per entity
    │
    ├──→ Forward entities to peer via POST /api/sync/entities
    │
    ▼
SignalWebSocketHandler.broadcast()       (async per-session queues)
    │  Non-blocking offer to bounded queues (64 msg)
    │  Dedicated sender threads drain per session
    │
    ▼
Browser (index.html + waterfall.js)      (Canvas rendering @ 60fps)
    Two stacked waterfalls: raw signals + merged entities
```

### Failover Sequence

```
Normal operation:
  app-a (ACTIVE)  ──heartbeat──→  app-b (STANDBY)
  app-a generates signals, detects entities
  app-a writes to redis-a, forwards entities to app-b
  app-b writes forwarded entities to redis-b

Crash app-a:
  1. app-b heartbeat fails (peer unreachable)
  2. After 3s timeout → app-b promotes to ACTIVE
  3. app-b starts signal generation + detection
  4. app-b writes to redis-b (its own Redis)

Recover app-a:
  1. app-a restarts with default role (active)
  2. Both instances detect split-brain via heartbeat
  3. Lower instance ID wins → app-a stays ACTIVE, app-b demotes
  4. app-b syncs state from app-a via GET /api/sync/snapshot
  5. Normal operation resumes
```

### WebSocket Messages

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
| `instance-status` | On change | Instance role (active/standby) |

---

## Tree Search Merge Algorithm

### The Problem

The sliding detector window generates overlapping detections. Without merging, a single signal source produces many duplicate entities. Entities should merge when they have the **same color**, **close center frequency**, and **similar width** — within a configurable time window.

### Redis Data Model

Two global sorted sets enable fast 2D range queries:

```
entities:by_time   — score = detectionTime (epoch ms), member = entity ID
entities:by_freq   — score = center frequency, member = entity ID
entity:{id}        — hash with all entity properties (no per-key TTL)
```

### How Tree Search Works

For each new entity, find merge candidates via intersection of two range queries:

```
1. Time range:  ZRANGEBYSCORE entities:by_time (now - mergeWindow) now
   → All entities detected within the merge window (1-120 seconds)

2. Freq range:  ZRANGEBYSCORE entities:by_freq (centerFreq - width) (centerFreq + width)
   → All entities with similar center frequency

3. Intersect both sets → candidates that match in BOTH time and frequency

4. For each candidate, evaluate:
   a. Color match?        incoming.color == candidate.color
   b. Width overlap?      overlap / min(widths) >= mergeWidthOverlapPercent

5. MERGE: Expand existing entity's time and frequency boundaries
   OR INSERT: Add as new entity to both sorted sets + hash
```

This replaces the old 1-second time-slot bucket approach. Instead of being limited to merging within a 1s window, the tree search enables merge windows from 1 to 120 seconds while maintaining O(log N) query performance via Redis sorted sets.

### Batch Merge — 3 RTTs for N Entities

```
          App                           Redis
           │                              │
           │── Pipeline: ZRANGEBYSCORE ──→│  RTT 1: Time range + freq range
           │←── Candidate IDs ───────────│
           │                              │
           │── Pipeline: HGETALL ×M ─────→│  RTT 2: Fetch candidate hashes
           │←── Hash data ───────────────│
           │                              │
           │  (local merge evaluation)    │  0 RTTs
           │                              │
           │── Pipeline: HSET+ZADD ×K ──→│  RTT 3: Save entities + update indexes
           │←── OK ─────────────────────│
           │                              │
           Total: 3 RTTs regardless of batch size
```

### Cleanup

No SCAN needed. The sorted set IS the index:

```
ZRANGEBYSCORE entities:by_time 0 (now - ttl)  → expired entity IDs
Pipeline: ZREM both sorted sets + DEL hashes
```

---

## Dual Service Failover

### FailoverService

Each instance runs a `FailoverService` that:
- Heartbeats the peer every 1 second via `GET /api/health`
- If peer unreachable for 3 seconds and this instance is standby → **promote to active**
- Split-brain resolution: **lower instance ID wins** (app-a < app-b)
- On promotion: enables signal generation + WebSocket broadcasts
- On demotion: disables signal generation, syncs state from new active peer

### Entity Forwarding

When the active instance detects entities:
1. Publishes to its own Redis via `QueueService.publishBatch()`
2. Forwards the raw entity list to the peer via `POST /api/sync/entities`
3. The peer publishes to its own Redis (same merge logic, same entity IDs)

Both Redis instances maintain consistent entity state.

### State Recovery

When a crashed instance comes back:
1. It starts as standby (or resolves split-brain via ID comparison)
2. Calls peer's `GET /api/sync/snapshot` to get all current entities
3. Restores entities into its own Redis
4. Begins receiving forwarded entities from the active peer

---

## Performance

### Batch Publishing

| Approach | RTTs | At 50 entities/tick |
|----------|------|---------------------|
| Naive (per-entity) | 3×N | 150 RTTs |
| Pipelined per-entity | N | 50 RTTs |
| **Batch publish** | **3 total** | **3 RTTs** |

### Additional Optimizations

| Feature | Approach |
|---------|----------|
| Key scanning | Sorted set index replaces SCAN |
| WebSocket broadcast | Async bounded queues (64 msg/session) |
| Tick/detect decoupling | ArrayBlockingQueue handoff |
| Redis connections | 16-connection Lettuce pool |
| Cleanup | ZRANGEBYSCORE on time index (no SCAN) |

At default settings (10 ticks/sec, ~25 entities/tick), throughput is ~250 entities/sec. At max tick rate (1000 t/s), throughput reaches 1,500-2,500 entities/sec.

---

## UI Controls

### Service Instances Panel

- **Instance role**: Shows which instance this browser is connected to and its role
- **Node indicators**: Green = running, Red = down
- **Role labels**: `active`, `standby`, `master`, or `down` per node
- **CRASH/START buttons**: Per-node control via Docker API
- **CRASH ALL / RECOVER ALL**: Full cluster control

### Settings Panel

| Setting | Range | Default | Description |
|---------|-------|---------|-------------|
| Max width | 100-100,000 | 10,000 | Frequency axis range (Hz) |
| Retention | 1-120s | 30s | How long blocks remain visible |
| Blocks/tick | 0-500 | 50 | Signal blocks generated per tick |
| Min duration | 100ms+ | 500ms | Minimum signal block lifetime |
| Max duration | Min+ | 5,000ms | Maximum signal block lifetime |
| Min block width | 5+ | 20 | Minimum frequency band width |
| Max block width | Min-Max | 300 | Maximum frequency band width |
| Detector width % | 1-100% | 10% | Detector window width |
| Overlap % | 0-90% | 50% | Detector window step overlap |
| Det. time window | 100-30,000ms | 1,000ms | Detection lookback window |
| Detection prob. | 0-100% | 100% | Detection probability |
| Entity TTL | 5-3,600s | 30s | Redis persistence lifetime |
| Merge Window | 1-120s | 30s | Time range for merge candidates |
| Tick rate | 1-1,000 t/s | 10 t/s | Signal generation speed |

---

## API Reference

### Health & Settings

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Instance health: `{instanceId, active, timestamp}` |
| `/api/settings` | GET | All current configuration values |
| `/api/settings` | POST | Update any subset of settings |

### Node Control

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/redis/stop/{node}` | POST | Stop a container (e.g., `app-a`, `redis-b`) |
| `/api/redis/start/{node}` | POST | Start a stopped container |
| `/api/redis/stop-all` | POST | Stop all nodes |
| `/api/redis/start-all` | POST | Start all nodes |
| `/api/redis/status` | GET | Current state of all nodes |

### Sync (Peer Communication)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/sync/snapshot` | GET | Dump all entities from this instance's Redis |
| `/api/sync/restore` | POST | Flush local Redis and repopulate from snapshot |
| `/api/sync/entities` | POST | Receive forwarded entity batch from active peer |

### WebSocket — `ws://localhost:8080/ws`

All messages are JSON: `{"type": "<type>", "data": <payload>}`.

---

## Configuration

### `application.yml`

```yaml
signal:
  tick-interval-ms: 100
  max-width: 10000
  max-blocks-per-tick: 50
  min-block-duration-ms: 500
  max-block-duration-ms: 5000
  min-block-width: 20
  max-block-width: 300
  retention-ms: 30000

detector:
  window-width-percent: 10
  overlap-percent: 50
  time-window-ms: 1000
  detection-probability: 100

queue:
  entity-ttl-seconds: 30
  merge-window-seconds: 30          # 1-120s merge candidate time range
  merge-width-overlap-percent: 50

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 3000ms

server:
  port: ${SERVER_PORT:8080}

failover:
  instance-id: ${INSTANCE_ID:app-a}
  instance-role: ${INSTANCE_ROLE:active}
  peer-url: ${PEER_URL:http://localhost:8081}

redis:
  nodes: ${REDIS_NODES:redis-a,redis-b,app-a,app-b}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | localhost | Redis server hostname |
| `REDIS_PORT` | 6379 | Redis server port |
| `SERVER_PORT` | 8080 | Spring Boot HTTP port |
| `INSTANCE_ID` | app-a | Unique instance identifier |
| `INSTANCE_ROLE` | active | Initial role: `active` or `standby` |
| `PEER_URL` | http://localhost:8081 | Peer instance base URL |
| `REDIS_NODES` | redis-a,redis-b,app-a,app-b | Container names for status polling |

---

## Project Structure

```
├── docker-compose.yml                  # 4-container dual-service layout
├── Dockerfile                          # Multi-stage build (JDK 17 + Docker CLI)
├── build.gradle.kts                    # Dependencies
├── src/main/
│   ├── java/com/df/queue/
│   │   ├── QueueApplication.java       # Entry point (@EnableScheduling)
│   │   ├── config/
│   │   │   ├── RedisConfig.java        # Lettuce pooling + RestTemplate
│   │   │   └── WebSocketConfig.java    # Registers /ws endpoint
│   │   ├── model/
│   │   │   ├── SignalBlock.java        # Raw signal (freq band + amplitude + color)
│   │   │   ├── DetectedEntity.java     # Entity from detection
│   │   │   └── EntityMessage.java      # WebSocket wrapper (new/merged)
│   │   ├── service/
│   │   │   ├── SignalGenerator.java    # Tick-based signal generation (active only)
│   │   │   ├── DetectorService.java    # Sliding window detection + peer forwarding
│   │   │   ├── QueueService.java       # Tree search merge, sorted set indexes
│   │   │   ├── FailoverService.java    # Active/standby health checks + promotion
│   │   │   ├── ThroughputService.java  # Entities/sec metrics broadcast
│   │   │   └── RedisStatusService.java # Docker container polling
│   │   └── web/
│   │       ├── SignalWebSocketHandler.java  # Async per-session broadcast queues
│   │       ├── SettingsController.java      # GET/POST /api/settings
│   │       ├── HealthController.java        # GET /api/health
│   │       ├── SyncController.java          # Snapshot/restore/entity sync
│   │       └── RedisNodeController.java     # Crash/recovery REST API
│   └── resources/
│       ├── application.yml             # All configurable properties
│       └── static/
│           ├── index.html              # UI layout + CSS
│           └── js/waterfall.js         # Canvas rendering, WS client, controls
```

---

## Testing Resilience

### 1. Basic Failover

1. Start: `docker compose up --build`
2. Confirm all 4 nodes are green in the Service Instances panel
3. Click **CRASH** on `app-a`
4. Within ~3-5s, app-b promotes to active
5. Open [http://localhost:8081](http://localhost:8081) — signals and merges continue
6. Click **START** on `app-a` — it recovers, split-brain resolves, app-a resumes as active

### 2. Redis Crash

1. Click **CRASH** on `redis-a`
2. app-a loses its Redis but app-b is unaffected as standby
3. Click **START** on `redis-a` — app-a reconnects, entities flow again

### 3. Full Crash

1. Click **CRASH ALL** — all nodes go down
2. Click **START** on `redis-a` then `app-a` — app-a starts as active
3. Start remaining nodes — app-b syncs from app-a

### 4. Merge Window Testing

1. Set Merge Window slider to 5s — entities only merge within 5-second windows
2. Set to 120s — entities merge across 2-minute windows (much larger merged entities)
3. Watch the merged waterfall to see the difference in entity consolidation

---

## Requirements

- **Docker** and **Docker Compose** (v2)
- **Ports available**: 8080 (app-a), 8081 (app-b), 6379 (redis-a), 6380 (redis-b)
- **Docker socket access**: Both app containers mount `/var/run/docker.sock`
- Approximately **256MB RAM** for all 4 containers

---

## License

MIT
