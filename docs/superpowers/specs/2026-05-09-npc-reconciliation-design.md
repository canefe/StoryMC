# NPC Reconciliation — Design Spec

**Date**: 2026-05-09
**Status**: Approved (awaiting implementation plan)

## Problem

The polling/reconnect-dump respawn loops have been removed:

- StoryMC `PositionBroadcaster.respawnMissing()` and `lastKnownPositions` cache (commit `5a41dd2b`).
- story-go `RespawnAll` and `StartNearbyRespawnChecker` (commit `b1df94c`).

Without them, a character whose MythicMob entity does not exist on disk (died, was despawned, world wipe, never-spawned) cannot reappear in the world except via:

- `handleSimInit` bulk-spawn on sim startup.
- `HandleCharacterPosition` first-sight branch — only triggers on plugin-initiated position events for `online=false` characters whose entity is already in `npcRegistry`. Cannot bootstrap missing characters.
- Operator commands (`/story char spawn`, `/story npc mmnpc spawn`).

## Goal

Replace the deleted loops with an event-driven reconciliation model where StoryMC asks story-go for spawn data when it detects a character should be present but isn't.

## Core principle

- **StoryMC** owns "is this NPC alive in my world." It decides when to request spawns based on its own world events (player join, chunk load, NPC death).
- **story-go** owns canonical character data. It answers spawn queries by reading Mongo. It does not track plugin world state.
- **sim** is unchanged in this spec.

## Scope

### In scope

- New request/reply protocol over the existing WebSocket bus.
- StoryMC reconciliation service triggered on player-join, chunk-load (debounced), and NPC death.
- story-go spawn-query handler that reads `character_positions` joined with `characters`, filtered by `status == "alive"`.
- Schema addition: `status` field on the character document.
- Config additions for radius, response timeout, post-death respawn delay.

### Out of scope

- Sim emitting entity lifecycle events (separate spec).
- Removing `handleSimInit` bulk-spawn or `HandleCharacterPosition` first-sight branch.
- Operator command for `status` field (a separate task; the read contract is enough here).
- Operator command to force `Boot → Running` transition without a frontend ack.
- Multi-frontend ready coordination.
- Cross-world reconciliation. Sweep is per-world.

## Sim-state dependence

Reconciliation only spawns NPCs when sim is active. Sim is the authority for the simulated world; spawning Minecraft entities sim doesn't know about creates orphan NPCs that sim cannot drive or perceive — the exact desync this work eliminates.

**Rule**: the story-go spawn-query handler early-returns with an empty intent list when `!h.state.IsActive()`. Same gating pattern as `HandlePerceptionStimulus`, `HandleAffordanceSpawn`, `ForwardDamageToSim`. Plugin remains sim-state-agnostic — it requests, gets empty back, no NPCs appear.

When sim later comes online, `handleSimInit` bulk-spawns into the plugin and subsequent sweeps return populated lists.

### Boot state and frontend-ready handshake

`active` is not enough. Sim can be running (process up, NATS subscribed, entities loaded) but **not yet ready to tick** — characters' positions in MC haven't been broadcast yet, so sim's spatial model lags behind reality. Ticking in this window means NPCs make decisions on stale data: walk toward where StoryMC last said they were minutes ago, perceive characters that have since moved, etc.

To prevent this, sim adds an explicit boot state and only resumes time when a frontend declares ready.

#### story-sim — `SimPhase`

New Bevy resource:

```rust
enum SimPhase {
    Boot,    // entities loaded, time paused, awaiting frontend ready
    Running, // time ticks normally
}
```

Bevy `Time<Virtual>` is paused on entry to `Boot` (`time.pause()`) and unpaused on entry to `Running` (`time.unpause()`).

Initial state: `Boot`. Set after `handleSimInit` completes loading entities.

Transition `Boot → Running` is triggered by the message `frontend_ready` (see below).

Existing `time_scale_system` continues to work — `set_relative_speed` is independent of pause state.

#### Wire protocol — frontend ready

**StoryMC → story-go (WebSocket)**: `FrontendReadyEvent`
| Field    | Type   | Notes                                  |
|----------|--------|----------------------------------------|
| `world`  | String | Bukkit world name (informational)      |

Sent by StoryMC after **both** of the following have completed:
1. Plugin has issued an initial reconciliation sweep on its first online player (or, if no players, immediately after server start once `handleSimInit` has bulk-populated the registry).
2. `PositionBroadcaster` has emitted at least one full tick so story-go has fresh positions.

If no player is ever online, StoryMC still sends `FrontendReadyEvent` after a configurable startup delay so sim isn't paused forever in headless test scenarios.

**story-go → story-sim (NATS)**: `frontend_ready` message
- Forwarded by story-go on receipt of `FrontendReadyEvent`. Idempotent: story-go suppresses duplicates within the same sim session (tracked via `sim.init` resetting the flag).

**story-sim**: receives via existing stream-handler dispatch in `src/plugins/stream_handlers/`. Handler transitions `SimPhase::Boot → SimPhase::Running` and unpauses `Time<Virtual>`. If already `Running`, no-op.

#### story-go: SimState extensions

`pkg/sim/state.go` (or wherever `IsActive` lives) gains a `Phase` field:
- `PhaseUnknown` — no sim contact yet (current "inactive").
- `PhaseBoot` — sim has emitted `sim.init` but no `frontend_ready` ack yet.
- `PhaseRunning` — `frontend_ready` has been forwarded.

`IsActive()` keeps current semantics (sim process is reachable). The spawn-query handler reads `Phase`:
- `PhaseUnknown` → return empty (matches existing inactive-gate behavior).
- `PhaseBoot` → **return populated intents**. NPCs need to spawn during boot so StoryMC can broadcast their positions; that's what the handshake completes.
- `PhaseRunning` → return populated intents.

Death-respawn timers, perception forwarding, etc. remain gated on `IsActive()` (process reachability), not on phase.

#### Why allow spawn during Boot

Boot exists to keep sim from *ticking* on stale data. Spawning entities into MC during boot is fine and necessary — it's how MC populates `PositionBroadcaster`'s view, which is what closes the boot window in the first place. The handshake is precisely: "spawn, broadcast positions, then I'll tick."

#### sim.init resets the cycle

When sim restarts and emits `sim.init`, story-go transitions `Phase` back to `PhaseBoot` and clears the duplicate-suppression flag. StoryMC's `FrontendReadyEvent` flow runs again. This handles the live sim-restart case without dragging stale `Running` state across restarts.

### Failure modes (boot)

| Scenario | Behavior |
|----------|----------|
| StoryMC never sends `FrontendReadyEvent` | Sim stays paused indefinitely. Operator-visible (sim logs phase). Operator can force-resume via debug command (out of scope: documented as future). |
| `FrontendReadyEvent` arrives before `sim.init` | story-go buffers it; emits `frontend_ready` to NATS once sim becomes reachable. (Or simpler: drops it; StoryMC re-sends on next reconciliation. Pick during plan phase.) |
| Multiple frontends (future) | story-go sends `frontend_ready` once any frontend reports ready. Multi-frontend coordination out of scope. |

## Bridge protocol — generic request/reply

The current bridge is fire-and-forget pub/emit. This spec introduces correlation via `requestId`. The pattern is generic and reusable for future request/reply needs.

### `NpcSpawnQueryEvent` — StoryMC → story-go

| Field       | Type   | Notes                                  |
|-------------|--------|----------------------------------------|
| `requestId` | String | UUID, generated by StoryMC             |
| `world`     | String | Bukkit world name                      |
| `x`         | Double | Center X (live player position)        |
| `y`         | Double | Center Y                               |
| `z`         | Double | Center Z                               |
| `radius`    | Double | Sweep radius in blocks                 |

### `NpcSpawnQueryResponseEvent` — story-go → StoryMC

| Field       | Type                  | Notes                              |
|-------------|-----------------------|------------------------------------|
| `requestId` | String                | Echo of request                    |
| `intents`   | List<NpcSpawnIntent>  | Existing struct; one per character |

`NpcSpawnIntent` is the existing event type — unchanged.

### Wire types

- StoryMC: add to `bridge/DomainEvents.kt`, register in `WebSocketTransport`.
- story-go: add to `pkg/events/events.go` (constants + structs).

Event type strings: `npc.spawn_query`, `npc.spawn_query_response`.

## StoryMC: ReconciliationService

New class: `src/main/kotlin/com/canefe/story/npc/ReconciliationService.kt`.

### Responsibilities

- Track pending requests in `ConcurrentHashMap<String, PendingRequest>` with a 5-second timeout.
- Emit `NpcSpawnQueryEvent` and apply intents from response via existing `IntentExecutor.executeNpcSpawnIntent`.
- Filter response intents to charIds *not* already in `npcRegistry` (defensive — race between sweeps).
- Per-player debounce map `Map<UUID, Int>` (taskId) for chunk-load coalescing.

### Public API

```kotlin
class ReconciliationService(private val plugin: Story) {
    fun requestNearby(world: String, x: Double, y: Double, z: Double, radius: Double)
    fun start()  // registers response listener
    fun stop()
}
```

### Response handling

Listens to `NpcSpawnQueryResponseEvent`. Looks up `requestId` in pending map; if present, removes it, cancels timeout task, and dispatches each intent via `executeNpcSpawnIntent`. Logs `(requestId, total intents, applied count)`. If `requestId` not in pending, logs warning and drops.

Timeout task runs after `reconcileResponseTimeoutMillis`. If still pending, logs warning and removes.

## Triggers (StoryMC)

### 1. Player join (+40 ticks)

Extend the existing `MythicMobNPCFactory.onPlayerJoin` callback that already runs at +40 ticks (lines 286–305). After the rehydrate sweep completes, call:

```kotlin
reconciliationService.requestNearby(
    world = player.world.name,
    x = player.location.x,
    y = player.location.y,
    z = player.location.z,
    radius = config.npc.reconcileRadius,
)
```

The 40-tick delay lets persisted MythicMob entities rehydrate first, so the sweep only requests *truly missing* characters.

### 2. Chunk load (debounced)

New listener: `src/main/kotlin/com/canefe/story/npc/ChunkLoadReconciler.kt`. Listens to `ChunkLoadEvent`.

Logic:

1. Find the nearest online player to the loaded chunk (chunk center, ignoring Y).
2. If no player within 8 chunks (128 blocks), ignore.
3. Look up the player's pending debounce task in `Map<UUID, Int>`. If present, cancel it.
4. Schedule a new task 20 ticks later that:
   - Reads the player's current live position.
   - Calls `reconciliationService.requestNearby(...)` with `radius = config.npc.reconcileRadius`.
   - Removes the entry from the debounce map.

Per-player debouncing collapses chunk-streaming bursts into one request per second.

### 3. NPC death (delayed sweep)

Extend `MythicMobNPCFactory.onMythicMobDeath`. After `npcRegistry.unregister(...)`:

```kotlin
val location = entity.location  // capture before scheduler call
Bukkit.getScheduler().runTaskLater(plugin, Runnable {
    reconciliationService.requestNearby(
        world = location.world.name,
        x = location.x,
        y = location.y,
        z = location.z,
        radius = config.npc.reconcileShortRadius,
    )
}, config.npc.respawnDelaySeconds * 20L)
```

`reconcileShortRadius` (e.g. 32) ensures we only respawn if a player is still near the death location. story-go's `status == "alive"` filter handles permadeath transparently — no plugin-side state needed.

## story-go: spawn-query handler

### Wiring

In `internal/sim/handler.go` (or a new `internal/reconcile` package — TBD during implementation), register a handler for the `npc.spawn_query` message type. Routed via the existing message dispatch in `Handler.Handle`.

### Logic

1. Parse `world, x, y, z, radius` from request data.
2. Query `character_positions` for entries with `world == request.world` AND distance ≤ radius. Distance is computed in 3D Euclidean blocks.
3. Look up matching characters from `characters` collection.
4. Filter by `status == "alive"`.
5. Build `NpcSpawnIntent { characterId, name, race, world, x, y, z }` for each.
6. Send `NpcSpawnQueryResponseEvent { requestId, intents }` back through the same WebSocket frontend that sent the query.

A new method `FindAliveNearby(world string, x, y, z, radius float64) ([]events.NpcSpawnIntent, error)` lives on a service that has access to both `position.Store` and the character resolver. Implementation can either (a) load all positions for the world and filter in Go, or (b) use a Mongo aggregation with `$geoNear` if the collection has a 2dsphere index. Default to (a) for simplicity; revisit if profiling shows hot path.

### Per-frontend response routing

`NpcSpawnQueryResponseEvent` must reach the *originating* WebSocket client, not be broadcast to all frontends. The bridge `sender` already supports per-frontend send. The handler signature must include the originating frontend ID (currently `Handle` passes `BridgeMessage`; we may need to extend it or use a wrapped sender). Implementation detail — flagged for the plan phase.

## Schema: character status

### Mongo: `characters` collection

New field:

| Field    | Type   | Default   | Notes                                  |
|----------|--------|-----------|----------------------------------------|
| `status` | String | `"alive"` | Values: `"alive"`, `"dead"`. Future: `"hidden"`. |

Migration: existing documents lack the field. The spawn-query filter uses `status != "dead"`, which matches both `"alive"` and missing-field documents. No migration script required.

### story-go: NPCCharacter struct

`pkg/character/resolver.go`:

```go
type NPCCharacter struct {
    ID           string
    Name         string
    Race         string
    LocationName string
    Status       string  // "alive" (default), "dead"
}
```

`ListNPCCharacters` reads the field via BSON unmarshal. No filter applied at this method (current callers want all NPCs). Spawn-query handler filters separately.

## Config additions

`run/plugins/Story/config.yml`:

```yaml
npc:
  respawnDelaySeconds: 300        # Delay before post-death sweep
  reconcileRadius: 128            # Player-join and chunk-load sweep radius (blocks)
  reconcileShortRadius: 32        # Post-death sweep radius (blocks)
  reconcileResponseTimeoutMillis: 5000  # Pending request timeout
  chunkLoadDebounceTicks: 20      # Per-player chunk-load debounce window
```

`ConfigService` reads these into a typed config holder.

## Observability

- StoryMC `ReconciliationService`: log every `NpcSpawnQueryEvent` at info with `(requestId, trigger source, world, x, y, z, radius)`. Log every response at info with `(requestId, total intents, applied count, dropped-because-already-spawned count)`. Log timeouts at warn.
- story-go handler: log query at info with `(requestId, world, radius, candidate position count, alive after filter, response intent count)`.

## Failure modes

| Scenario | Behavior |
|----------|----------|
| Bridge disconnected | `requestNearby` no-ops (`WebSocketTransport.publish` early-returns). Pending map gets no entry. Player sees no NPCs until reconnect, then next trigger fires. |
| Sim inactive | story-go handler early-returns with empty intent list. Response arrives with `intents: []`. No spawns. World stays empty until sim comes online. |
| story-go responds with stale position (NPC was moved by sim since last broadcast) | Spawn happens at stale coords; next sim `npc.state` intent corrects via `executeNpcStateIntent`. |
| Multiple sweeps overlap (player join + chunk load fire close together) | Each gets its own `requestId`. Both responses applied. `executeNpcSpawnIntent` already idempotent — `existing?.isSpawned == true` early-returns. |
| Response arrives after timeout | Logged warning, dropped. No spawn. Next trigger fires fresh. |

## Test plan

### Unit

- `ReconciliationService`: pending map lifecycle (add → response → remove; add → timeout → remove). Filtering of already-spawned charIds.
- `ChunkLoadReconciler`: debounce coalesces multiple events into one task per player per window.
- story-go handler: distance filter; status filter; per-frontend response routing.

### Integration

- StoryMC test plugin run with bridge mocked: trigger `PlayerJoinEvent`, observe `NpcSpawnQueryEvent` emitted at +40 ticks, feed back response, assert spawns occur.
- Death → respawn flow with `respawnDelaySeconds` reduced to 1s. Death triggers spawn at the death location after the delay if a player is within `reconcileShortRadius`.

### Manual

1. Start fresh sim + story-go + StoryMC, no NPCs spawned.
2. Player joins at world spawn. Within 2s of join, NPCs near spawn appear.
3. Player walks 200 blocks. Within 1–2s of streaming new chunks, NPCs in the new area appear.
4. Player kills an NPC. After `respawnDelaySeconds`, the NPC reappears (player still nearby).
5. Operator runs `db.characters.updateOne({_id: id}, {$set: {status: "dead"}})`. Player kills the NPC. After delay, NPC does not reappear. Triggering chunk-load sweeps also do not bring it back.

## Open questions / flagged for plan phase

1. `Handler.Handle` signature — does it carry the originating frontend ID? If not, plumb it. (Implementation detail.)
2. `FindAliveNearby` placement — `internal/sim/handler.go` or a new `internal/reconcile/` package? Lean toward new package since the handler doesn't depend on sim state.
3. ID-collision for `requestId` across multiple frontends — must be globally unique. UUID v4 is fine; documented.
4. Exact StoryMC trigger for `FrontendReadyEvent`. Candidate: after the first reconciliation sweep response is applied **and** at least one `PositionBroadcaster` tick has fired post-`sim.init`. Plan phase decides the event source (e.g. observe `SimStatusEvent(running=true)` then arm a one-shot post-conditions check).
5. Behavior when `FrontendReadyEvent` arrives at story-go before `sim.init`. Buffer-and-forward vs. drop-and-rely-on-resend. Lean toward drop — StoryMC's reconciliation triggers fire on every player join / chunk load, so a re-arm naturally happens.

## Files touched (preview)

### StoryMC
- `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` — add `NpcSpawnQueryEvent`, `NpcSpawnQueryResponseEvent`.
- `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — register new event types.
- `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` — no change (existing `executeNpcSpawnIntent` is the apply path).
- `src/main/kotlin/com/canefe/story/npc/ReconciliationService.kt` — new.
- `src/main/kotlin/com/canefe/story/npc/ChunkLoadReconciler.kt` — new.
- `src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobNPCFactory.kt` — extend `onPlayerJoin` and `onMythicMobDeath`.
- `src/main/kotlin/com/canefe/story/Story.kt` — register `ReconciliationService`, `ChunkLoadReconciler`.
- `src/main/kotlin/com/canefe/story/config/ConfigService.kt` — new config keys.
- `run/plugins/Story/config.yml` — defaults.

### story-go
- `pkg/events/events.go` — add event constants and structs (including `frontend.ready`).
- `pkg/character/resolver.go` — add `Status` to `NPCCharacter`.
- `internal/reconcile/handler.go` (new) — `FindAliveNearby` logic.
- `internal/sim/handler.go` (or `cmd/server/main.go`) — wire query handler; forward `frontend_ready` to NATS; reset phase on `sim.init`.
- `internal/sim/state.go` — add `Phase` field, accessors, transitions.
- `internal/bridge/sender.go` — verify per-frontend send; extend if needed.

### story-sim
- `src/plugins/sim_phase.rs` (new) — `SimPhase` resource, transition logic, `Time<Virtual>` pause/unpause.
- `src/plugins/stream_handlers/frontend_ready.rs` (new) — handler that flips `SimPhase::Boot → Running`.
- `src/plugins/stream_handlers/mod.rs` — register new handler.
- `src/main.rs` — initial `SimPhase::Boot` insert; pause `Time<Virtual>` at startup.
