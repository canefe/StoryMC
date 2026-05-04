# Event Ledger Design

**Date:** 2026-05-04
**Status:** Approved for implementation planning
**Scope:** Cross-process append-only event ledger for Story (sim, story-go, story-mcp, Story plugin)

---

## Overview

A unified, append-only event ledger that is the single source of truth for "what happened" across every process in the Story stack. JetStream is the durable write path; story-go indexes the stream into Mongo and exposes a query API. Every process — story-sim, story-go, story-mcp, Story plugin — publishes typed envelope events into one stream, and any process can query the ledger by entity, cause, time range, or type.

This is infrastructure, not a feature. Decisions, perceptions, NPC speech, sessions — all consequential cross-process events flow through the ledger. Downstream consumers (memory, perception broadcast, NPC reactions, future replay tooling) become subscribers of one stream instead of bespoke listeners on bespoke channels.

---

## Goals

1. **Cross-process source of truth.** One append-only stream all processes write to and read from. No more "check sim logs and story-go logs and story-mcp Mongo" to reconstruct what happened.
2. **Decoupled consequences.** Decision outcomes (and other events) have a destination. Consumers subscribe; producers don't hardcode downstream effects.
3. **Causal traceability.** Every event records which prior events caused it (`causedBy`), enabling backwards walks: "why did Valen distrust the party?"
4. **Durable replay.** New consumers can be added later and replay the full history from JetStream (or query Mongo for archival reads).

---

## Non-Goals (this spec)

- Rollback / time-travel (ledger is append-only; reversing is a higher-level concern)
- Strongly-typed payload schemas per event type (free-form `payload_json` for now; graduation path defined below)
- Cross-environment / multi-world federation
- Authn/authz on the query API (assumes trusted internal network)
- Stream-level dedup (Mongo upsert handles idempotency)

---

## Architecture

```
                ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌────────────┐
                │  story-sim   │   │   story-go   │   │  story-mcp   │   │   Story    │
                │  (producer)  │   │ (producer +  │   │  (producer)  │   │ (producer) │
                │              │   │  indexer)    │   │              │   │            │
                └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   └─────┬──────┘
                       │ publish          │ publish          │ publish         │ publish
                       │                  │                  │                 │
                       ▼                  ▼                  ▼                 ▼
                ┌─────────────────────────────────────────────────────────────────────┐
                │  NATS JetStream — stream "ledger" binding subjects "ledger.>"       │
                │  Subject pattern: ledger.<source>.<type>                            │
                │  Retention: forever (MaxAge=0, MaxBytes=0) — revisit when disk hurts│
                └─────────────────────────────────────────────────────────────────────┘
                       │                                                ▲
                       │ durable pull consumer                          │ future:
                       │ "ledger-indexer"                               │ story-mcp as
                       ▼                                                │ second consumer
                ┌──────────────────┐                                    │ for Qdrant
                │ story-go         │  upsert by eventId                 │ semantic search
                │ indexer goroutine├──────────────┐                     │
                └──────────────────┘              ▼                     │
                                           ┌─────────────┐              │
                                           │ Mongo       │              │
                                           │ ledger_events│             │
                                           └──────┬──────┘              │
                                                  │                     │
                ┌─────────────────────────────────┴───────────────────┐ │
                │ story-go HTTP query API                              │ │
                │  GET /ledger/event/{id}                              │ │
                │  GET /ledger/by-subject/{subject}                    │ │
                │  GET /ledger/by-cause/{eventId}                      │ │
                │  GET /ledger/range?fromTick&toTick&type              │ │
                └──────────────────────────────────────────────────────┘ │
                                                                          │
                (later) ─────────────────────────────────────────────────┘
```

**Component summary:**

- **Producers (4):** story-sim, story-go, story-mcp, Story plugin. Each holds a small helper library that constructs the envelope and publishes to NATS.
- **Transport:** NATS JetStream, one stream named `ledger`, subjects `ledger.<source>.<type>`.
- **Indexer:** A goroutine inside story-go. Durable pull consumer named `ledger-indexer` subscribed to `ledger.>`. Decodes the envelope, upserts into Mongo by `eventId`, ACKs.
- **Storage:** Mongo collection `ledger_events`, single collection across all sources/types.
- **Query API:** HTTP endpoints exposed by story-go.
- **Future consumer:** story-mcp can join as a second durable consumer to embed event summaries into Qdrant for semantic search. Out of scope here.

---

## Event Envelope

The envelope is proto-defined in `story-proto` as `LedgerEvent`. The payload is free-form JSON-encoded bytes for now; stable event types graduate to typed `oneof` payloads in a later iteration.

```proto
// story-proto/ledger/ledger.proto
syntax = "proto3";
package story.ledger;

message LedgerEvent {
  string event_id    = 1; // UUIDv7 — time-ordered
  int64  timestamp   = 2; // wall-clock unix millis (UTC)
  int64  game_time   = 3; // game tick at time of publish
  string source      = 4; // "story-go" | "story-sim" | "story-mcp" | "story"
  string type        = 5; // dotted, e.g. "decision.resolved"
  repeated string subjects   = 6; // entity refs: "char:valen", "decision:abc"
  repeated string caused_by  = 7; // upstream event_ids
  bytes  payload_json = 8; // JSON-encoded free-form payload
}
```

### Field semantics

- **`event_id`** — UUIDv7. Time-ordered, naturally sorts by creation. Used as Mongo `_id` for upsert idempotency.
- **`timestamp`** — wall-clock unix millis. For real-time correlation with logs / Grafana.
- **`game_time`** — game tick. For in-story chronology (a session may pause real-world but not game-world, or vice versa).
- **`source`** — process name. Compile-time constant in each producer's helper. Authoritative for subject routing.
- **`type`** — dotted hierarchical type: `decision.resolved`, `perception.emitted`, `npc.spoke`. Producers MUST keep this stable across versions (it's part of the subject and queried directly).
- **`subjects`** — colon-separated `kind:id` entity references this event is *about*. Indexed in Mongo. Examples: `char:valen`, `decision:abc123`, `location:lys-athara`, `player:turkiyecraft`.
- **`caused_by`** — list of `event_id`s that directly caused this event. Forms a DAG over time. Empty for root events (e.g. session start).
- **`payload_json`** — UTF-8 JSON bytes. Schema is per-`type` and lives in the producer's domain. The indexer does not parse it; the query API returns it as-is.

### Subject routing

Each event publishes to subject `ledger.<source>.<type>`, e.g. `ledger.story-go.decision.resolved`. The single JetStream stream binds `ledger.>`. Consumers that only want a slice (future feature) subscribe to filtered subjects like `ledger.*.decision.>`. The indexer subscribes to `ledger.>`.

---

## JetStream Stream Configuration

```
Name:         ledger
Subjects:     ledger.>
Retention:    Limits (default)
Storage:      File
MaxAge:       0  (forever)
MaxBytes:     0  (forever)
MaxMsgs:      0  (forever)
Replicas:     1  (initially; bump to 3 when clustered)
Discard:      Old (not relevant given no limits, but set defensively)
Duplicates:   2m (NATS default; we do not rely on it for correctness)
```

**Retention rationale.** Disk is cheap and "I can replay from event zero" is a high-leverage debugging affordance early. We will revisit when disk pressure becomes real (no fixed trigger; revisit on monitoring signal). At that point, switch to a bounded window (`MaxAge=30d`) and trust Mongo as the long-term archive.

---

## Indexer

A goroutine inside story-go.

**Configuration:**

- Durable consumer name: `ledger-indexer`
- Filter subject: `ledger.>`
- Delivery policy: `DeliverAll` (reads from sequence 1 on first run)
- Ack policy: `AckExplicit`
- Ack wait: 30s
- Max in-flight: 256
- Pull batch size: 64

**Loop:**

```
for {
    msgs := pull(batch=64, timeout=5s)
    for msg in msgs {
        evt := decode(msg.Data)            // proto unmarshal
        doc := toMongoDoc(evt)
        mongo.UpdateOne(
            {_id: evt.EventId},
            {$setOnInsert: doc},
            upsert=true,
        )
        msg.Ack()
    }
}
```

**Idempotency.** `_id == event_id`. Redelivery after a crash mid-batch upserts to the same document; `$setOnInsert` ensures we never overwrite existing data. ACK only after Mongo write returns success.

**Failure handling.**

- Mongo write error → do NOT ack. Log, sleep with backoff, message redelivers after AckWait.
- Decode error → log + ack with terminal poison. (A bad message should not block the stream forever.) Poison messages are written to `ledger_events_poison` with the raw bytes for forensic inspection.
- Indexer crash → durable cursor preserves position; on restart it resumes from the last unacked message.

**Lag observability.** Story-go exposes `/ledger/health` returning `{streamSeq, consumerSeq, lag}`. Lag = streamSeq − consumerSeq. Alert threshold: lag > 1000 sustained for >1 minute.

---

## Mongo Schema

Single collection `ledger_events`. The facets *are* the index — no need for per-source/per-type sharding at this scale.

```js
{
  _id:         "<eventId UUIDv7>",
  timestamp:   <int64 millis>,
  gameTime:    <int64 tick>,
  source:      "story-go",
  type:        "decision.resolved",
  subjects:    ["char:valen", "decision:abc123"],
  causedBy:    ["perception:xyz789"],
  payload:     { ...parsed JSON object... }   // stored as Mongo doc, not raw bytes
}
```

**Indexes:**

```js
db.ledger_events.createIndex({ subjects: 1, gameTime: -1 })   // by-subject, newest first
db.ledger_events.createIndex({ causedBy: 1 })                  // by-cause forward walk
db.ledger_events.createIndex({ gameTime: -1, type: 1 })        // range queries
db.ledger_events.createIndex({ timestamp: -1 })                // wall-clock fallback
```

`_id` is automatically indexed, covers `/event/{id}`.

The indexer parses `payload_json` into a Mongo subdocument before writing. This makes payload fields incidentally queryable via Mongo even if the HTTP API doesn't expose those queries on day one.

---

## Query API (story-go HTTP)

All endpoints return JSON. Pagination uses `?limit=` (default 50, max 500) and `?cursor=` (opaque, encodes last `gameTime` + `_id` for stable paging).

### `GET /ledger/event/{eventId}`

Returns a single event, or 404.

### `GET /ledger/by-subject/{subject}`

Returns events whose `subjects` array contains `{subject}`, newest `gameTime` first.

Query params: `limit`, `cursor`, optional `type=decision.*` (Mongo regex on type).

Example: `GET /ledger/by-subject/char:valen?type=decision.*&limit=20`

### `GET /ledger/by-cause/{eventId}`

Returns events whose `causedBy` array contains `{eventId}`, newest first. (Forward causal walk.)

### `GET /ledger/range?fromTick=X&toTick=Y[&type=...][&source=...]`

Returns events in `[fromTick, toTick]`, optionally filtered by type pattern and source. Newest first. Paginated.

### `GET /ledger/health`

Returns indexer health: stream sequence, consumer sequence, lag, last-ack timestamp.

---

## Producer Helpers

Each producing process holds a small helper library that constructs the envelope and publishes. Helpers are NOT a separate repo — they live in their host process. They share the proto definition from `story-proto`.

### Go helper (in story-go)

```go
// story-go/internal/ledger/publisher.go
package ledger

func Publish(
    ctx context.Context,
    eventType string,
    subjects []string,
    causedBy []string,
    payload any,
) (eventID string, err error) {
    payloadBytes, _ := json.Marshal(payload)
    evt := &storyledger.LedgerEvent{
        EventId:     uuidv7.New().String(),
        Timestamp:   time.Now().UnixMilli(),
        GameTime:    GameClock.Tick(),    // atomic, fed by world.tick subject
        Source:      "story-go",
        Type:        eventType,
        Subjects:    subjects,
        CausedBy:    causedBy,
        PayloadJson: payloadBytes,
    }
    data, _ := proto.Marshal(evt)
    subject := fmt.Sprintf("ledger.story-go.%s", eventType)
    _, err = jsClient.Publish(ctx, subject, data)
    return evt.EventId, err
}
```

### Kotlin helper (in Story plugin, `bridge/` package)

```kotlin
// src/main/kotlin/com/canefe/story/bridge/ledger/LedgerPublisher.kt
object LedgerPublisher {
    fun publish(
        type: String,
        subjects: List<String> = emptyList(),
        causedBy: List<String> = emptyList(),
        payload: Any,
    ): String {
        val event = LedgerEvent.newBuilder()
            .setEventId(UuidV7.next())
            .setTimestamp(System.currentTimeMillis())
            .setGameTime(GameClock.tick())
            .setSource("story")
            .setType(type)
            .addAllSubjects(subjects)
            .addAllCausedBy(causedBy)
            .setPayloadJson(ByteString.copyFromUtf8(json.encodeToString(payload)))
            .build()
        natsConn.publish("ledger.story.$type", event.toByteArray())
        return event.eventId
    }
}
```

### Python helper (in story-mcp)

```python
# story-mcp/ledger/publisher.py
async def publish(
    event_type: str,
    subjects: list[str] | None = None,
    caused_by: list[str] | None = None,
    payload: Any = None,
) -> str:
    evt = LedgerEvent(
        event_id=str(uuid7()),
        timestamp=int(time.time() * 1000),
        game_time=game_clock.tick(),
        source="story-mcp",
        type=event_type,
        subjects=subjects or [],
        caused_by=caused_by or [],
        payload_json=json.dumps(payload).encode("utf-8") if payload is not None else b"{}",
    )
    await js.publish(f"ledger.story-mcp.{event_type}", evt.SerializeToString())
    return evt.event_id
```

### story-sim helper

Same pattern, source `"story-sim"`, subject `ledger.story-sim.<type>`.

---

## Game Clock Distribution

`gameTime` (tick) needs to be available to every producer at publish time. Story-sim is the authoritative clock.

**Mechanism:**

1. story-sim publishes the current tick on subject `world.tick` at every tick boundary (or at a configurable subsample, e.g. every Nth tick) as a tiny NATS message: `{ "tick": <int64> }`.
2. Each producer process maintains a `GameClock` singleton with an atomic `lastKnownTick`.
3. On startup, each `GameClock` subscribes to `world.tick` (regular core NATS, not JetStream — last-write-wins semantics are fine, no need for replay).
4. `GameClock.tick()` returns `lastKnownTick`. If never received, returns 0 and logs a warning at startup.

**Why core NATS, not JetStream:** the world tick is a *current value*, not a historical record. We don't care about replay; we only ever want the latest. Core NATS pub/sub is the right primitive.

**Clock skew note.** Producers may stamp events with a slightly stale tick (one tick behind) under load. Acceptable: a 50ms skew on a tick that advances every Nms is fine for ledger ordering. If finer accuracy is needed later, story-sim can stamp `gameTime` directly on events it forwards.

---

## Initial Event Types (v1)

These are the event types in scope for the initial implementation. Each lists its source, typical subjects, and payload sketch. Payload schemas live with their producers; they are not centralized.

| Type | Source | Subjects (typical) | Payload sketch |
|---|---|---|---|
| `session.started` | story-go | `session:<id>` | sessionId, players, startedAt |
| `session.ended` | story-go | `session:<id>` | sessionId, endedAt, summaryId? |
| `decision.prompted` | story-go | `decision:<id>`, all targeted `char:*` / `player:*` | decisionId, mode, options, npcVoices |
| `decision.resolved` | story-go | `decision:<id>`, all targeted `char:*` / `player:*` | decisionId, choiceId or freeformText, voteTally |
| `perception.emitted` | story-go (or story-sim) | involved `char:*`, `location:*` | perception kind + variant payload |
| `npc.spoke` | story-go (Story relays via story-go) | `char:<npc>`, present `char:*`/`player:*` | text, deliveryStyle, conversationId |

Anything else that arises during implementation (e.g. `combat.tick`, `worldevent.published`) follows the same envelope rules and may be added without a spec amendment, *provided* the type is dotted, stable, and producers reuse the helper.

---

## Implementation Phases

The implementation plan (next document) will lay out the exact phasing, but the natural order is:

1. **Proto + envelope** — define `LedgerEvent` in `story-proto`, regenerate Go/Kotlin/Python bindings.
2. **JetStream stream provisioning** — add `ledger` stream to whatever bootstraps NATS in dev/prod.
3. **Game clock subject** — story-sim publishes `world.tick`; helper class in each language consumes it.
4. **Producer helpers** — Go, Kotlin, Python. Tests: publish a fake event, read it raw from JetStream.
5. **Indexer in story-go** — durable consumer, Mongo upsert, poison handling, health endpoint.
6. **Query API** — four HTTP endpoints + integration tests against a real Mongo.
7. **Wire first producer** — `decision.resolved` from story-go's `finalize()`. Verify end-to-end: publish → JetStream → indexer → Mongo → query API.
8. **Wire remaining v1 event types** — sessions, perceptions, npc speech.

---

## Risks & Mitigations

- **Schema drift between languages.** Mitigated by single proto source of truth in `story-proto`; CI regen check.
- **Indexer becomes a bottleneck.** Mitigated by stateless design — can be horizontally scaled by running multiple replicas as a queue group on the same durable consumer (later).
- **Mongo unavailable.** Indexer does not ACK; messages redeliver. Query API returns 503. JetStream is unaffected.
- **Disk growth.** Monitored, not preempted. Revisit retention when it bites.
- **`payload_json` drift over time.** Producer-owned; the cost of looseness. When a payload type stabilizes and consumers rely on it, graduate it to a typed `oneof` in proto.
- **Cross-process clock skew on `gameTime`.** Acceptable at current granularity. If it matters, switch to authoritative-tick stamping on the events forwarded through sim.

---

## Future Work (out of scope here)

- Story-mcp as a second JetStream consumer that embeds event summaries into Qdrant for semantic search ("find decisions where the party was outnumbered").
- Typed payload graduation: stable event types move from `payload_json` to `oneof` proto messages.
- Full causal-graph traversal endpoint (`GET /ledger/causal-chain/{eventId}?depth=N`).
- Aggregations / counts over time windows.
- Retention bounded window + Mongo-as-archive.
- Authn/authz on the query API.
- Multi-replica indexer with queue-group semantics.
- Decision system migration to consume ledger events directly instead of bespoke bridge messages.
