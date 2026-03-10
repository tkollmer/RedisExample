# Signal Queue — Redis Sentinel Cluster with Crash/Recovery Testing

A real-time signal processing and entity detection system backed by a 3-node Redis Sentinel cluster. Features a live waterfall visualization, intelligent entity merging, and interactive crash/recovery testing to demonstrate Redis failover in action.

![Architecture](https://img.shields.io/badge/Redis-Sentinel_Cluster-red?style=flat-square) ![Java](https://img.shields.io/badge/Java-17-blue?style=flat-square) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green?style=flat-square) ![Docker](https://img.shields.io/badge/Docker-Compose-blue?style=flat-square)

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) in your browser. That's it — 8 containers start automatically.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Docker Compose                        │
│                                                         │
│  ┌──────────────┐  replicaof  ┌──────────────────┐     │
│  │ redis-master  │◄───────────│ redis-replica-1   │     │
│  │  :6379        │◄───────────│ redis-replica-2   │     │
│  └──────┬───────┘             └──────────────────┘     │
│         │ monitored by                                  │
│  ┌──────┴───────────────────────────────────────┐      │
│  │  sentinel-1 (:26379)                          │      │
│  │  sentinel-2 (:26380)   quorum = 2/3           │      │
│  │  sentinel-3 (:26381)                          │      │
│  └──────────────────────────────────────────────┘      │
│         │ discovered via                                │
│  ┌──────┴───────┐                                      │
│  │  app (Spring  │  Docker socket mounted for           │
│  │  Boot :8080)  │  crash/recovery control              │
│  └──────────────┘                                      │
└─────────────────────────────────────────────────────────┘
```

**8 containers total**: 1 master + 2 replicas + 3 sentinels + 1 app

## How It Works

### Signal Processing Pipeline

```
SignalGenerator (configurable tick rate)
    │
    ▼
Generates random signal blocks (frequency bands with amplitude/color)
    │
    ▼
DetectorService (sliding window across frequency axis)
    │
    ▼
Detects entities from overlapping blocks
    │
    ▼
QueueService → Redis (entity storage + merge logic)
    │
    ▼
WebSocket → Browser (real-time waterfall visualization)
```

### Redis Data Model

Each detected entity is stored in Redis using two structures:

- **Entity Hash** (`entity:{id}`): Stores all entity properties (position, time range, amplitude, color). Each hash has a configurable TTL (5s to 1 hour).
- **Time-Slot Sorted Set** (`entities:{timestamp/1000}`): Groups entities into 1-second buckets. The sorted set score is `widthStart`, enabling efficient range queries for merge candidate lookups.

### Entity Merging Algorithm

When a new entity is detected, the system searches for merge candidates:

1. **Time proximity**: Searches the current time slot and adjacent slots (±1 second)
2. **Color match**: Only entities of the same color can merge
3. **Width overlap**: Calculates the overlap ratio between frequency ranges. If overlap exceeds the threshold (default 50% of the smaller entity), they merge
4. **Merge action**: The existing entity's boundaries expand to encompass both entities. The new entity is absorbed.

```
Before merge:     After merge:
[Entity A    ]    [  Entity A (expanded)  ]
    [Entity B]    Entity B absorbed
```

This prevents duplicate detections from cluttering the queue while preserving the full spatial extent of detected signals.

### TTL-Based Expiry

- Every entity hash gets a Redis TTL (configurable from 5 seconds to 1 hour)
- Redis automatically evicts expired entities — no application-side cleanup needed for hashes
- A background task cleans up orphaned time-slot sorted set entries every 5 seconds

## Redis Sentinel: How Failover Works

### Why Sentinel (not Cluster)?

This system uses **Redis Sentinel** for high availability, not Redis Cluster:

- **Sentinel** provides automatic failover with master-replica replication. All data lives on every node. Best for: availability, read scaling, resilience testing.
- **Cluster** provides sharding across nodes. Data is split. Best for: horizontal scaling beyond single-node memory limits.

Since our entity queue fits in a single node's memory, Sentinel gives us failover without the complexity of hash slots and resharding.

### The Failover Sequence

```
1. Master goes down (you click STOP)
    │
2. Sentinels detect failure (1 second down-after-milliseconds)
    │  Each sentinel independently marks master as SDOWN (subjectively down)
    │
3. Quorum reached (2 of 3 sentinels agree)
    │  Master is marked ODOWN (objectively down)
    │
4. Leader election among sentinels
    │  One sentinel is elected to perform the failover
    │
5. Replica promotion
    │  The elected sentinel picks the best replica:
    │    - Highest replication offset (most up-to-date)
    │    - Lower replica ID as tiebreaker
    │  Issues SLAVEOF NO ONE to promote it
    │
6. Reconfiguration
    │  Other replicas are told to replicate from the new master
    │  Sentinels update their config
    │
7. Application reconnects
    Lettuce (Redis client) is notified via Sentinel pub/sub
    ReadFrom.REPLICA_PREFERRED routes reads to available replicas
```

**Timing**: With our aggressive demo settings, failover completes in ~2-4 seconds:
- 1s detection (`down-after-milliseconds`)
- 3s max failover timeout
- Near-instant Lettuce reconnection (auto-reconnect with 2s command timeout)

### Read Distribution

The app uses `ReadFrom.REPLICA_PREFERRED`:
- **Writes** always go to the master
- **Reads** prefer replicas, falling back to master if no replica is available
- This distributes load and means reads continue working even during master failover

### What Happens When You Crash Nodes

| Scenario | Result |
|----------|--------|
| Stop master | Sentinel promotes a replica in ~15s. App reconnects automatically. |
| Stop one replica | Reads continue on remaining replica + master. No data loss. |
| Stop two replicas | All reads go to master. Still fully functional. |
| Stop all three | App loses Redis connectivity. Entities queue in memory. Recovery restores everything. |
| Stop master + 1 replica | Remaining replica gets promoted. Single-node operation. |

## UI Controls

### Redis Cluster Panel
- **Node indicators**: Green = running, Red = down
- **Role labels**: Shows `master` or `slave` for each node (updates after failover)
- **STOP/START buttons**: Per-node crash/recovery via Docker API
- **CRASH ALL / RECOVER ALL**: Full cluster control
- **Entities/sec**: Live throughput counter
- **Total Entities**: Cumulative entity count

### Settings Panel (click to expand)

| Setting | Range | Description |
|---------|-------|-------------|
| Max width | 1K–100K | Frequency axis range |
| Retention | 5–120s | How long blocks remain visible |
| Blocks/tick | 0–500 | Signal generation rate per tick |
| Min/Max duration | 100ms–15s | Signal block lifetime range |
| Min/Max block width | 5–1000 | Frequency band width range |
| Detector width | 10–10000 | Detector window size (default: full width) |
| Overlap % | 0–90% | Detector window step overlap |
| Det. time window | 100–10000ms | How far back the detector looks |
| Detection prob. | 0–100% | Probability of detecting an intersecting block |
| Entity TTL | 5s–1hr | How long entities persist in Redis (text input for exact values) |
| Tick rate | 1–100 t/s | Signal generation speed (ticks per second) |

### Pause Button
Pauses signal generation on the server. The waterfall freezes. Click RESUME to continue.

## Project Structure

```
├── docker-compose.yml              # 8-container orchestration
├── Dockerfile                      # Multi-stage build + Docker CLI
├── sentinel/sentinel.conf          # Sentinel config template
├── src/main/
│   ├── java/com/df/queue/
│   │   ├── QueueApplication.java   # Entry point
│   │   ├── config/
│   │   │   ├── RedisConfig.java    # Sentinel + ReadFrom config
│   │   │   └── WebSocketConfig.java
│   │   ├── model/
│   │   │   ├── SignalBlock.java    # Raw signal data
│   │   │   ├── DetectedEntity.java # Detected entity
│   │   │   └── EntityMessage.java  # WebSocket wrapper
│   │   ├── service/
│   │   │   ├── SignalGenerator.java     # Generates random signals
│   │   │   ├── DetectorService.java     # Sliding window detection
│   │   │   ├── QueueService.java        # Redis storage + merging
│   │   │   ├── ThroughputService.java   # Entities/sec metrics
│   │   │   └── RedisStatusService.java  # Docker node polling
│   │   └── web/
│   │       ├── SignalWebSocketHandler.java  # WS broadcast
│   │       ├── SettingsController.java      # GET/POST /api/settings
│   │       └── RedisNodeController.java     # Crash/recovery REST API
│   └── resources/
│       ├── application.yml
│       └── static/
│           ├── index.html
│           └── js/waterfall.js     # Canvas rendering + UI
```

## API Reference

### Settings
- `GET /api/settings` — Current configuration
- `POST /api/settings` — Update settings (JSON body with any subset of fields)

### Redis Node Control
- `POST /api/redis/stop/{node}` — Stop a Redis container (e.g., `redis-master`)
- `POST /api/redis/start/{node}` — Start a stopped container
- `POST /api/redis/stop-all` — Stop all 3 Redis nodes
- `POST /api/redis/start-all` — Start all 3 Redis nodes
- `GET /api/redis/status` — Current state of all nodes

### WebSocket Messages (ws://localhost:8080/ws)

| Type | Direction | Payload |
|------|-----------|---------|
| `blocks` | Server→Client | Active signal blocks + maxWidth/retentionMs |
| `detector` | Server→Client | Detector position, width, time window |
| `entity` | Server→Client | New/merged entity event |
| `entities` | Server→Client | Active entity count |
| `merge-start` | Server→Client | Pre-merge state |
| `merge-end` | Server→Client | Post-merge result |
| `redis-entities` | Server→Client | All entities currently in Redis |
| `redis-status` | Server→Client | Node running/role status (every 2s) |
| `throughput` | Server→Client | Entities/sec + total count (every 1s) |

## Testing Resilience

### Basic Failover Test
1. Start the system: `docker compose up --build`
2. Observe all 3 nodes green in the Redis Cluster panel
3. Click **STOP** on `redis-master`
4. Watch the indicator go red, then a replica's role changes to `master` (~15s)
5. Signal processing continues uninterrupted
6. Click **START** on the stopped node — it rejoins as a replica

### Full Cluster Test
1. Click **CRASH ALL** — all nodes go red, signal processing pauses (no Redis)
2. Click **RECOVER ALL** — nodes restart, Sentinel re-establishes replication
3. App reconnects automatically

### Cascading Failure
1. Stop the master
2. Wait for failover to complete
3. Stop the new master
4. Only one replica remains — it gets promoted
5. Start both stopped nodes — they rejoin as replicas

## Requirements

- Docker and Docker Compose
- Port 8080 available (app UI)
- Ports 6379-6381 available (Redis nodes)
- Ports 26379-26381 available (Sentinels)

## License

MIT
