# Signal Queue — Separate Sim/Detector + Dual Queue Service with Zero Data Loss

A real-time signal processing and entity detection system with a dedicated simulation service and dual queue services for guaranteed zero data loss. Features tree-search entity merging via Redis sorted set 2D range queries, configurable merge windows up to 120s, live waterfall visualization, batch-pipelined Redis operations, and interactive crash/recovery testing.

![Java](https://img.shields.io/badge/Java-17-blue?style=flat-square) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green?style=flat-square) ![Docker](https://img.shields.io/badge/Docker-Compose-blue?style=flat-square) ![Redis](https://img.shields.io/badge/Redis-Standalone-red?style=flat-square) ![Lettuce](https://img.shields.io/badge/Lettuce-Connection_Pool-orange?style=flat-square)

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) (app-a) or [http://localhost:8081](http://localhost:8081) (app-b). Five containers start automatically: `sim`, `app-a`, `redis-a`, `app-b`, `redis-b`.

The top waterfall shows raw signal blocks, the bottom shows detected and merged entities from Redis. The sidebar provides controls for signal generation, detection parameters, merge window tuning, and service instance crash/recovery.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Docker Compose (5 containers)                      │
│                                                                       │
│                      ┌──────────────┐                                │
│                      │     sim       │                                │
│                      │  :8082        │                                │
│                      │  SignalGen +  │                                │
│                      │  Detector     │                                │
│                      └──────┬───────┘                                │
│                             │                                         │
│              ┌──────────────┼──────────────┐                         │
│              │ POST /api/sync/entities     │                         │
│              │ POST /api/sim/blocks        │                         │
│              │ POST /api/sim/detector      │                         │
│              ▼                              ▼                         │
│  ┌──────────────┐              ┌──────────────┐                     │
│  │  app-a        │  HTTP sync   │  app-b        │                     │
│  │  :8080        │◄────────────►│  :8081        │                     │
│  │  (active)     │  recovery    │  (standby)    │                     │
│  └──────┬───────┘              └──────┬───────┘                     │
│         │                              │                              │
│  ┌──────┴───────┐              ┌──────┴───────┐                     │
│  │  redis-a      │              │  redis-b      │                     │
│  │  :6379        │              │  :6380        │                     │
│  └──────────────┘              └──────────────┘                     │
│                                                                       │
│  sim: Generates signals, detects entities, forwards to BOTH apps.     │
│  Both apps: Receive identical entities, publish to own Redis.         │
│  Zero data loss: If either app crashes, the other has all data.       │
└──────────────────────────────────────────────────────────────────────┘
```

| Container | Mode | Role | Port |
|-----------|------|------|------|
| `sim` | sim | Signal generation + entity detection | 8082 |
| `app-a` | queue | Redis queue service (default: active) | 8080 |
| `redis-a` | — | Standalone Redis for app-a | 6379 |
| `app-b` | queue | Redis queue service (default: standby) | 8081 |
| `redis-b` | — | Standalone Redis for app-b | 6380 |

All services use the **same JAR**, differentiated by `APP_MODE` environment variable (`sim` or `queue`). Spring's `@ConditionalOnProperty` activates the appropriate beans per mode.

---

## Zero Data Loss Guarantee

The sim service forwards every detected entity to **both** app-a and app-b simultaneously:

```
sim detects entity → POST to app-a/api/sync/entities (fire and forget)
                   → POST to app-b/api/sync/entities (fire and forget)
```

**Why this guarantees zero data loss:**

1. Both apps receive every entity independently from sim
2. If app-a crashes, app-b continues receiving from sim — no gap
3. If app-b crashes, app-a continues receiving — no gap
4. When a crashed app recovers, it syncs missed entities from the peer via snapshot
5. If sim crashes, both apps retain all previously received data; new entity generation pauses until sim recovers

The only scenario with data loss is simultaneous failure of **both** apps (dual failure).

---

## How It Works

### Signal Pipeline

```
sim container:
  SignalGenerator                          (configurable tick rate: 1-1000 t/s)
      │  Generates random signal blocks
      │  (always active in sim mode)
      │
      ▼
  DetectorService                          (sliding window across frequency axis)
      │  Scans blocks with configurable window width & overlap
      │  Hands off via ArrayBlockingQueue (decoupled from tick)
      │
      ▼
  SimForwardingService                     (HTTP POST to both apps)
      │  Forward blocks → POST /api/sim/blocks
      │  Forward detector → POST /api/sim/detector
      │  Forward entities → POST /api/sync/entities
      │  Fire-and-forget: if one app is down, the other still receives
      │
      ▼
app-a / app-b containers:
  SimReceiveController                     (receives blocks + detector data)
      │  Broadcasts to WebSocket clients
      │
  SyncController                           (receives entities)
      │  → QueueService.publishBatch()
      │
      ▼
  QueueService                             (3 Redis RTTs for entire batch)
      │  RTT 1: ZRANGEBYSCORE on entities:by_time + entities:by_freq
      │  RTT 2: Pipeline HGETALL for intersection of both sets
      │  Local:  Evaluate merges (color + center freq + width overlap)
      │  RTT 3: Pipeline HSET + ZADD (both sorted sets) per entity
      │
      ▼
  SignalWebSocketHandler.broadcast()       (async per-session queues)
      │  Non-blocking offer to bounded queues (64 msg)
      │
      ▼
  Browser (index.html + waterfall.js)      (Canvas rendering @ 60fps)
```

### Failover & Recovery

```
Normal operation:
  sim → entities → app-a (writes to redis-a)
  sim → entities → app-b (writes to redis-b)
  app-a ←heartbeat→ app-b (peer monitoring)

Crash app-a:
  1. sim continues sending to app-b (zero data loss)
  2. app-b detects peer unreachable after 3s → promotes to ACTIVE
  3. User reconnects to http://localhost:8081

Recover app-a:
  1. app-a restarts, syncs state from app-b via GET /api/sync/snapshot
  2. Split-brain resolves: lower ID wins (app-a resumes as ACTIVE)
  3. Both apps resume receiving from sim

Crash sim:
  1. No new entities generated (both apps retain existing Redis data)
  2. Restart sim → entities flow again to both apps
```

### Settings Proxy

The frontend always talks to `/api/settings` on the connected app. The app proxies signal/detector settings to sim, keeping the UI simple:

```
Browser → POST /api/settings → app-a → applies queue settings locally
                                      → forwards signal settings to sim
```

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

---

## WebSocket Messages

| Message | Source | Frequency | Content |
|---------|--------|-----------|---------|
| `blocks` | sim→app | Every tick | All visible signal blocks + axis config |
| `detector` | sim→app | Every tick | Detector window position/width |
| `entity` | QueueService | Per detection | New or merged entity event |
| `entities` | sim→app | Every tick | Active entity count from detector |
| `merge-start` | QueueService | Per merge | Pre-merge state of both entities |
| `merge-end` | QueueService | Per merge | Post-merge result |
| `redis-entities` | QueueService | Every 500ms | Full entity snapshot from Redis |
| `redis-status` | RedisStatusService | Every 2s | Node running/role status (5 nodes) |
| `throughput` | ThroughputService | Every 1s | Entities/sec + total count |
| `instance-status` | FailoverService | On change | Instance role (active/standby) |

---

## API Reference

### Health & Settings

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Instance health: `{instanceId, active, timestamp}` |
| `/api/settings` | GET | All settings (queue merges with sim settings) |
| `/api/settings` | POST | Update settings (queue applies local, forwards to sim) |

### Node Control (queue mode only)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/redis/stop/{node}` | POST | Stop a container (e.g., `sim`, `app-a`, `redis-b`) |
| `/api/redis/start/{node}` | POST | Start a stopped container |
| `/api/redis/stop-all` | POST | Stop all nodes |
| `/api/redis/start-all` | POST | Start all nodes |
| `/api/redis/status` | GET | Current state of all 5 nodes |

### Sync (queue mode only)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/sync/snapshot` | GET | Dump all entities from this instance's Redis |
| `/api/sync/restore` | POST | Flush local Redis and repopulate from snapshot |
| `/api/sync/entities` | POST | Receive entity batch (from sim or peer) |

### Sim Receive (queue mode only)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/sim/blocks` | POST | Receive signal blocks from sim → broadcast to WS |
| `/api/sim/detector` | POST | Receive detector state from sim → broadcast to WS |
| `/api/sim/entity-count` | POST | Receive entity count from sim → broadcast to WS |

---

## Configuration

### `application.yml`

```yaml
app:
  mode: ${APP_MODE:queue}           # 'sim' or 'queue'

signal:
  tick-interval-ms: 100
  max-width: 10000
  max-blocks-per-tick: 50

detector:
  window-width-percent: 10
  overlap-percent: 50
  time-window-ms: 1000
  detection-probability: 100

queue:
  entity-ttl-seconds: 30
  merge-window-seconds: 30          # 1-120s merge candidate time range
  merge-width-overlap-percent: 50

sim:
  targets: ${SIM_TARGETS:http://app-a:8080,http://app-b:8080}
  url: ${SIM_URL:http://sim:8080}

failover:
  instance-id: ${INSTANCE_ID:app-a}
  instance-role: ${INSTANCE_ROLE:active}
  peer-url: ${PEER_URL:http://localhost:8081}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_MODE` | queue | Service mode: `sim` or `queue` |
| `INSTANCE_ID` | app-a | Unique instance identifier |
| `INSTANCE_ROLE` | active | Initial role: `active` or `standby` |
| `PEER_URL` | http://localhost:8081 | Peer queue instance URL |
| `SIM_TARGETS` | http://app-a:8080,... | Comma-separated queue instance URLs (sim mode) |
| `SIM_URL` | http://sim:8080 | Sim service URL (queue mode, for settings proxy) |
| `REDIS_HOST` | localhost | Redis server hostname |
| `REDIS_PORT` | 6379 | Redis server port |

---

## Project Structure

```
├── docker-compose.yml                  # 5-container layout (sim + 2 apps + 2 Redis)
├── Dockerfile
├── build.gradle.kts
├── src/main/
│   ├── java/com/df/queue/
│   │   ├── QueueApplication.java       # Entry point (@EnableScheduling)
│   │   ├── config/
│   │   │   ├── AppConfig.java          # RestTemplate (both modes)
│   │   │   ├── RedisConfig.java        # Lettuce pooling (queue mode only)
│   │   │   └── WebSocketConfig.java    # Registers /ws endpoint
│   │   ├── model/
│   │   │   ├── SignalBlock.java
│   │   │   ├── DetectedEntity.java
│   │   │   └── EntityMessage.java
│   │   ├── service/
│   │   │   ├── SignalGenerator.java    # Tick-based signal gen (sim mode only)
│   │   │   ├── DetectorService.java    # Sliding window detection (sim mode only)
│   │   │   ├── SimForwardingService.java  # Forwards to both apps (sim mode only)
│   │   │   ├── QueueService.java       # Tree search merge (queue mode only)
│   │   │   ├── FailoverService.java    # Peer monitoring + recovery (queue mode only)
│   │   │   ├── ThroughputService.java  # Entities/sec metrics (queue mode only)
│   │   │   └── RedisStatusService.java # Docker container polling (queue mode only)
│   │   └── web/
│   │       ├── SignalWebSocketHandler.java  # Async per-session broadcast
│   │       ├── HealthController.java        # Health (both modes)
│   │       ├── SettingsController.java      # Settings proxy (queue mode)
│   │       ├── SimSettingsController.java   # Signal settings (sim mode)
│   │       ├── SimReceiveController.java    # Receives from sim (queue mode)
│   │       ├── SyncController.java          # Snapshot/restore/entity sync (queue mode)
│   │       └── RedisNodeController.java     # Crash/recovery API (queue mode)
│   └── resources/
│       ├── application.yml
│       └── static/
│           ├── index.html
│           └── js/waterfall.js
```

---

## Testing Resilience

### 1. Basic Failover (App Crash)

1. Start: `docker compose up --build`
2. Confirm all 5 nodes are green in the Service Instances panel
3. Click **CRASH** on `app-a`
4. app-b continues receiving entities from sim (zero data loss)
5. Within ~3s, app-b promotes to active
6. Open [http://localhost:8081](http://localhost:8081) — signals and merges continue
7. Click **START** on `app-a` — it recovers, syncs from app-b, resumes

### 2. Sim Crash

1. Click **CRASH** on `sim`
2. Both apps retain all Redis data, no new entities generated
3. Click **START** on `sim` — entity generation resumes, both apps receive

### 3. Redis Crash

1. Click **CRASH** on `redis-a`
2. app-a loses its Redis, but app-b is unaffected
3. Click **START** on `redis-a` — app-a reconnects

### 4. Full Recovery

1. Click **CRASH ALL** — all nodes go down
2. Start `redis-a`, then `app-a` — app-a starts receiving from sim
3. Start remaining nodes — all resume

### 5. Merge Window Testing

1. Set Merge Window slider to 5s — entities only merge within 5-second windows
2. Set to 120s — entities merge across 2-minute windows
3. Watch the merged waterfall to see the difference in entity consolidation

---

## Requirements

- **Docker** and **Docker Compose** (v2)
- **Ports available**: 8080 (app-a), 8081 (app-b), 8082 (sim), 6379 (redis-a), 6380 (redis-b)
- **Docker socket access**: App containers mount `/var/run/docker.sock`
- Approximately **384MB RAM** for all 5 containers

---

## License

MIT
