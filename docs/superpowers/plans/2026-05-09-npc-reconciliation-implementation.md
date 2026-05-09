# NPC Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the deleted respawn loops with an event-driven reconciliation model: StoryMC asks story-go for spawn data when its triggers fire (player join, chunk load, NPC death). Add a Boot/Running phase to story-sim so it does not tick on stale positions, gated by a frontend-ready handshake.

**Architecture:** New request/reply protocol over the existing WebSocket bus (`npc.spawn_query` / `npc.spawn_query_response`) with a UUID `requestId` and per-frontend response routing. story-go gains an `internal/reconcile` package that joins `character_positions` with `characters` filtered by `status != "dead"`. story-sim gains a `SimPhase` resource (Boot → Running) that pauses `Time<Virtual>` until a `frontend_ready` NATS message arrives; story-go forwards that message after StoryMC emits `FrontendReadyEvent`.

**Tech Stack:** Kotlin/Paper + kotlinx.serialization (StoryMC), Go + gorilla/websocket + MongoDB (story-go), Rust/Bevy + serde + NATS (story-sim).

**Spec:** `docs/superpowers/specs/2026-05-09-npc-reconciliation-design.md`

**Repos:**
- StoryMC (Kotlin, this repo): `/Users/canefe/Projects/personal/Story` — build: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`; tests: `./gradlew test`
- story-go (Go): `/Users/canefe/Projects/personal/story-go` — currently on detached HEAD; build: `go build ./...`; vet: `go vet ./...`; tests: `go test ./...`
- story-sim (Bevy/Rust): `/Users/canefe/Projects/personal/story-sim` — build: `cargo build`; tests: `cargo test`

**Commits:** All commits via `jj`. StoryMC uses Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`). No `Co-Authored-By` lines. Each task ends in a commit.

## Open question resolutions

1. **Originating frontend ID plumbing.** `bridge.Hub.onMessage` currently passes only `BridgeMessage`. Extend the callback signature to `func(msg BridgeMessage, reply ReplyFn)` where `ReplyFn = func(events.BridgeMessage)` sends back to the originating client only. `MultiSender` continues to drive broadcast. Keep `Hub.Broadcast` unchanged. The gRPC bridge's `routeMessage` shim passes a `nil` reply (gRPC never originates `npc.spawn_query`). The reconcile handler asserts a non-nil reply; if nil, logs warn and returns.
2. **`FindAliveNearby` placement.** New `internal/reconcile/` package, `Service` constructor takes `*character.Resolver` and `*position.Store`. Wire from `cmd/server/main.go`. Handler in `internal/reconcile/handler.go` does the dispatch.
3. **`requestId` uniqueness.** StoryMC generates `java.util.UUID.randomUUID().toString()` per request. story-go treats it as opaque; no validation. Documented in the wire schema. Globally unique by birthday-bound; collisions ignored.
4. **`FrontendReadyEvent` trigger.** New `FrontendReadinessTracker` (StoryMC) tracks three flags reset on `SimStatusEvent(running=false)`:
   - `simRunning` (set on `SimStatusEvent(running=true)`),
   - `positionsTickedSinceSim` (set on first `PositionBroadcaster.tick()` after `simRunning`),
   - `reconcileResponseApplied` (set on first response delivered through `ReconciliationService`, *or* a fallback timer fires after `frontendReadyFallbackMs` if no players ever connect).
   When all three become true, emit `FrontendReadyEvent` exactly once (atomic CAS). Reset to false when `SimStatusEvent(running=false)` arrives.
5. **`FrontendReadyEvent` arrives at story-go before `sim.init`.** Drop. story-go's `frontend.ready` handler forwards to NATS only when `Phase == PhaseBoot` (i.e. after `sim.init`). If `PhaseUnknown`, log warn and drop. StoryMC re-arms naturally because its tracker requires `SimStatusEvent(running=true)`, which only arrives after sim is up.

---

## File structure

### StoryMC (new files)
- `src/main/kotlin/com/canefe/story/npc/ReconciliationService.kt` — pending-request map, sends `NpcSpawnQueryEvent`, applies responses.
- `src/main/kotlin/com/canefe/story/npc/ChunkLoadReconciler.kt` — `ChunkLoadEvent` listener with per-player debounce.
- `src/main/kotlin/com/canefe/story/bridge/FrontendReadinessTracker.kt` — three-flag readiness tracker, emits `FrontendReadyEvent` exactly once per sim session.

### StoryMC (modified files)
- `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` — add `NpcSpawnQueryEvent`, `NpcSpawnQueryResponseEvent`, `FrontendReadyEvent`.
- `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — register new event types in `serializeEvent` and `deserializeEvent`.
- `src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobNPCFactory.kt` — extend `onPlayerJoin` (post-rehydrate sweep), extend `onMythicMobDeath` (delayed sweep).
- `src/main/kotlin/com/canefe/story/npc/PositionBroadcaster.kt` — call `plugin.frontendReadinessTracker.markPositionsTicked()` once per tick.
- `src/main/kotlin/com/canefe/story/Story.kt` — wire `ReconciliationService`, `ChunkLoadReconciler`, `FrontendReadinessTracker`; bind `SimStatusEvent` to tracker.
- `src/main/kotlin/com/canefe/story/config/ConfigService.kt` — add config keys + load.
- `run/plugins/Story/config.yml` — add config defaults.

### story-go (new files)
- `internal/reconcile/service.go` — `Service` with `FindAliveNearby(world string, x, y, z, radius float64) ([]events.NpcSpawnIntent, error)`.
- `internal/reconcile/handler.go` — message handler entrypoint, parses query and replies via per-client send.

### story-go (modified files)
- `pkg/events/events.go` — add `NpcSpawnQuery`, `NpcSpawnQueryResponse`, `FrontendReady` event constants and structs.
- `pkg/character/resolver.go` — add `Status` field to `NPCCharacter` and decode in `ListNPCCharacters`. Add `ListAliveNPCCharacterIds()` returning a set, or a per-id resolver `IsAlive(id) bool`.
- `internal/sim/state.go` — add `Phase` enum + accessors; transition logic.
- `internal/sim/handler.go` — `Handle` switch: `sim.init` resets phase to `PhaseBoot`; new `frontend.ready` case forwards to NATS once.
- `internal/bridge/sender.go` — new `PerClientSender` interface; `MultiSender` is unaffected.
- `internal/bridge/ws.go` — extend `onMessage` callback to `func(msg BridgeMessage, reply ReplyFn)`; expose per-client send via a `ReplyFn` closure that targets the originating `client.id`.
- `internal/grpc/server.go` — adapt to new `onMessage` signature (passes `nil` reply).
- `cmd/server/main.go` — instantiate `reconcile.Service`, register new dispatch, plumb reply, forward `frontend.ready`.

### story-sim (new files)
- `src/plugins/sim_phase.rs` — `SimPhase` resource + plugin that pauses `Time<Virtual>` on entry to `Boot`.
- `src/plugins/stream_handlers/frontend_ready.rs` — handler that flips `Boot → Running` and unpauses time.

### story-sim (modified files)
- `src/plugins/stream_handlers/mod.rs` — register `frontend_ready` handler.
- `src/plugins/mod.rs` (or `src/main.rs`) — register `SimPhasePlugin` and pause `Time<Virtual>` at startup.
- `src/main.rs` — add `SimPhasePlugin` to `App`.

---

## Phasing

- **Phase 1**: Schema + bridge protocol types (no behavior change). Both repos compile, no runtime effect.
- **Phase 2**: story-go reconcile package + per-frontend response routing. Independently testable via a fake WebSocket client sending `npc.spawn_query` and asserting the response.
- **Phase 3**: StoryMC `ReconciliationService` + triggers (player join, chunk load, NPC death). Live integration with phase 2.
- **Phase 4**: story-sim `SimPhase` + `frontend_ready` handler + `Time<Virtual>` pause. Sim boots paused; manually publish `frontend_ready` over NATS to verify unpause.
- **Phase 5**: story-go phase tracking + `frontend.ready` forwarding + StoryMC `FrontendReadinessTracker` emitting `FrontendReadyEvent`. End-to-end handshake works.

Each phase ends with a green build in every touched repo. Phase 2/3 can be smoke-tested without phase 4/5 because the spawn-query path doesn't depend on phase state.

---

# Phase 1 — Schema and bridge protocol types

## Task 1: Add `status` field to `NPCCharacter` (story-go)

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/pkg/character/resolver.go:184-189`, `:208-213`, `:217-222`

- [ ] **Step 1: Modify `NPCCharacter` struct**

In `pkg/character/resolver.go`, replace the struct definition starting around line 184:

```go
// NPCCharacter holds the minimal data needed to spawn an NPC into the sim.
type NPCCharacter struct {
	ID           string
	Name         string
	Race         string
	LocationName string
	Status       string // "alive" (default), "dead". Empty string treated as alive.
}
```

- [ ] **Step 2: Decode the field in `ListNPCCharacters`**

In the same file, in the inner `var doc struct { ... }` of `ListNPCCharacters`, add the `Status` field:

```go
var doc struct {
	ID           string `bson:"_id"`
	Name         string `bson:"name"`
	Race         string `bson:"race"`
	LocationName string `bson:"locationName"`
	Status       string `bson:"status"`
}
```

And in the append, set Status:

```go
out = append(out, NPCCharacter{
	ID:           doc.ID,
	Name:         doc.Name,
	Race:         doc.Race,
	LocationName: doc.LocationName,
	Status:       doc.Status,
})
```

- [ ] **Step 3: Build**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./...`
Expected: success, no output.

- [ ] **Step 4: Vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go vet ./...`
Expected: success.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(character): add Status field to NPCCharacter"
```

---

## Task 2: Add reconcile event constants and structs (story-go)

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/pkg/events/events.go`

- [ ] **Step 1: Add event type constants**

Append to the `Sim event type constants` const block (after `FrontendIntent`):

```go
const (
	NpcSpawnQuery         = "npc.spawn_query"
	NpcSpawnQueryResponse = "npc.spawn_query_response"
	FrontendReady         = "frontend.ready"
)
```

- [ ] **Step 2: Add wire structs**

Add at the bottom of `pkg/events/events.go`:

```go
// NpcSpawnQueryEvent is sent by a frontend asking story-go to enumerate
// alive NPCs near a point and reply with NpcSpawnQueryResponseEvent.
type NpcSpawnQueryEvent struct {
	RequestID string  `json:"requestId"`
	World     string  `json:"world"`
	X         float64 `json:"x"`
	Y         float64 `json:"y"`
	Z         float64 `json:"z"`
	Radius    float64 `json:"radius"`
}

// NpcSpawnQueryResponseEvent is the reply, addressed to the originating
// frontend only. Intents are NpcSpawnIntent values usable directly by the
// plugin's IntentExecutor.executeNpcSpawnIntent.
type NpcSpawnQueryResponseEvent struct {
	RequestID string           `json:"requestId"`
	Intents   []NpcSpawnIntent `json:"intents"`
}

// FrontendReadyEvent is sent by a frontend when it has completed initial
// reconciliation and broadcast at least one position tick. story-go forwards
// it to story-sim as a NATS `frontend_ready` message.
type FrontendReadyEvent struct {
	World string `json:"world"`
}
```

- [ ] **Step 3: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(events): add npc.spawn_query, response, and frontend.ready types"
```

---

## Task 3: Add bridge protocol types (StoryMC)

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt`

- [ ] **Step 1: Add the three new event classes**

Append to `DomainEvents.kt` (after `FrontendIntentEvent`):

```kotlin
/**
 * Plugin → Go: ask story-go for alive NPCs near a point. Response carries
 * `NpcSpawnIntent`s for each character that should be in-world.
 */
@Serializable
data class NpcSpawnQueryEvent(
    val requestId: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.spawn_query"
}

/**
 * Go → Plugin: reply to a NpcSpawnQueryEvent, addressed to the originating
 * frontend. `requestId` echoes the request.
 */
@Serializable
data class NpcSpawnQueryResponseEvent(
    val requestId: String,
    val intents: List<NpcSpawnIntent> = emptyList(),
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.spawn_query_response"
}

/**
 * Plugin → Go → sim: declare that the plugin has finished initial
 * reconciliation and at least one position tick has been broadcast.
 * story-go forwards this as a NATS `frontend_ready` message that
 * unpauses story-sim's `Time<Virtual>`.
 */
@Serializable
data class FrontendReadyEvent(
    val world: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "frontend.ready"
}
```

- [ ] **Step 2: Wire them into `WebSocketTransport.serializeEvent`**

In `WebSocketTransport.kt`, in the `serializeEvent` `when (event)` block, add (in alphabetical-ish order with the others):

```kotlin
            is NpcSpawnQueryEvent -> json.encodeToJsonElement(event)
            is NpcSpawnQueryResponseEvent -> json.encodeToJsonElement(event)
            is FrontendReadyEvent -> json.encodeToJsonElement(event)
```

- [ ] **Step 3: Wire them into `WebSocketTransport.deserializeEvent`**

In the same file, in `deserializeEvent`'s `when (message.type)` block, add:

```kotlin
                "npc.spawn_query" -> json.decodeFromString<NpcSpawnQueryEvent>(data)
                "npc.spawn_query_response" -> json.decodeFromString<NpcSpawnQueryResponseEvent>(data)
                "frontend.ready" -> json.decodeFromString<FrontendReadyEvent>(data)
```

- [ ] **Step 4: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(bridge): add NpcSpawnQuery, NpcSpawnQueryResponse, FrontendReady event types"
```

---

# Phase 2 — story-go reconcile package + per-frontend response routing

## Task 4: Add per-client send to the WebSocket Hub

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/bridge/ws.go`
- Modify: `/Users/canefe/Projects/personal/story-go/internal/bridge/sender.go`

**Why:** Currently `Hub.onMessage(msg)` doesn't carry the originating client id, and `Sender.Broadcast` fans out to everyone. Spawn-query responses must reach only the originating frontend. We add a `ReplyFn` closure that captures the originating `client.id` and a `Hub.SendTo(id, msg)` method.

- [ ] **Step 1: Define `ReplyFn` and a per-client send helper in `sender.go`**

In `internal/bridge/sender.go`, append:

```go
// ReplyFn sends a message back to the originating client of an inbound
// message. It is supplied to onMessage callbacks and may be nil for
// transports without a notion of a single originator (e.g. gRPC).
type ReplyFn func(msg events.BridgeMessage)
```

- [ ] **Step 2: Change `Hub.onMessage` callback signature in `ws.go`**

Edit `internal/bridge/ws.go`:

Change the field:

```go
type Hub struct {
	clients   map[string]*Client
	mu        sync.RWMutex
	onMessage func(msg events.BridgeMessage, reply ReplyFn)
	onConnect func()
}
```

Change `NewHub`:

```go
func NewHub(onMessage func(msg events.BridgeMessage, reply ReplyFn)) *Hub {
	return &Hub{
		clients:   make(map[string]*Client),
		onMessage: onMessage,
	}
}
```

Change the dispatch inside `readPump`:

```go
		if h.onMessage != nil {
			originID := client.id
			reply := func(out events.BridgeMessage) {
				h.SendTo(originID, out)
			}
			h.onMessage(msg, reply)
		}
```

- [ ] **Step 3: Add `Hub.SendTo`**

Append to `ws.go` after `Broadcast`:

```go
// SendTo sends a message to the single client identified by clientID.
// If the client has disconnected the message is dropped silently.
func (h *Hub) SendTo(clientID string, msg events.BridgeMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		log.Errorf("[WS] Failed to marshal message: %v", err)
		return
	}
	h.mu.RLock()
	client, ok := h.clients[clientID]
	h.mu.RUnlock()
	if !ok {
		log.Warnf("[WS] SendTo: no client '%s'", clientID)
		return
	}
	select {
	case client.send <- data:
	default:
	}
}
```

- [ ] **Step 4: Adapt the gRPC bridge**

Open `/Users/canefe/Projects/personal/story-go/internal/grpc/server.go`. Find `routeMessage` field assignment and call sites. Update the field type to `func(events.BridgeMessage, bridge.ReplyFn)` and pass `nil` for reply at every call site inside the gRPC server. (gRPC bridge does not originate `npc.spawn_query`.)

If `routeMessage` is invoked as `s.onMessage(msg)`, change to `s.onMessage(msg, nil)`.

- [ ] **Step 5: Adapt `cmd/server/main.go` `routeMessage`**

In `cmd/server/main.go` change the closure signature:

```go
routeMessage := func(msg events.BridgeMessage, reply bridge.ReplyFn) {
```

(Add `bridge` to the imports if necessary — it's already imported.)

Pass-through: leave existing dispatches unchanged for now. We will use `reply` in Task 7 for `npc.spawn_query`.

- [ ] **Step 6: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 7: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "refactor(bridge): plumb originating-client ReplyFn through onMessage"
```

---

## Task 5: Write unit test for `Hub.SendTo` selecting only the originating client

**Files:**
- Create: `/Users/canefe/Projects/personal/story-go/internal/bridge/ws_sendto_test.go`

- [ ] **Step 1: Write the failing test**

Create the file with content:

```go
package bridge

import (
	"testing"

	"github.com/canefe/story-go/pkg/events"
)

func TestSendToOnlyDeliversToTargetClient(t *testing.T) {
	h := NewHub(nil)

	// Insert two fake clients with buffered send channels.
	a := &Client{id: "A", send: make(chan []byte, 1)}
	b := &Client{id: "B", send: make(chan []byte, 1)}
	h.clients["A"] = a
	h.clients["B"] = b

	h.SendTo("A", events.BridgeMessage{Type: "test"})

	select {
	case <-a.send:
		// ok
	default:
		t.Fatalf("expected message on client A")
	}

	select {
	case <-b.send:
		t.Fatalf("client B should not have received message")
	default:
		// ok
	}
}

func TestSendToUnknownClientIsNoop(t *testing.T) {
	h := NewHub(nil)
	h.SendTo("missing", events.BridgeMessage{Type: "test"}) // should not panic
}
```

- [ ] **Step 2: Run the test**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./internal/bridge/...`
Expected: PASS (the implementation from Task 4 already satisfies it).

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "test(bridge): cover Hub.SendTo per-client routing"
```

---

## Task 6: Add the `internal/reconcile` service

**Files:**
- Create: `/Users/canefe/Projects/personal/story-go/internal/reconcile/service.go`

- [ ] **Step 1: Write the service**

Create `internal/reconcile/service.go`:

```go
// Package reconcile answers spawn queries from frontends. It joins
// character_positions with characters and filters by status, returning
// NpcSpawnIntent values ready for the plugin's IntentExecutor.
package reconcile

import (
	"math"

	"github.com/canefe/story-go/pkg/character"
	"github.com/canefe/story-go/pkg/events"
	"github.com/canefe/story-go/pkg/log"
	"github.com/canefe/story-go/pkg/position"
)

// Service holds the dependencies needed to answer spawn queries.
// resolver and positions may be nil; in that case FindAliveNearby
// returns an empty list and logs.
type Service struct {
	resolver  *character.Resolver
	positions *position.Store
}

func NewService(resolver *character.Resolver, positions *position.Store) *Service {
	return &Service{resolver: resolver, positions: positions}
}

// FindAliveNearby returns NpcSpawnIntent values for every alive NPC whose
// last-known position is within `radius` blocks of (x, y, z) in `world`.
// Distance is 3-D Euclidean. Characters with status == "dead" are excluded;
// missing-status documents are treated as alive.
func (s *Service) FindAliveNearby(world string, x, y, z, radius float64) ([]events.NpcSpawnIntent, error) {
	if s == nil || s.resolver == nil || s.positions == nil {
		log.Warn("[Reconcile] FindAliveNearby: service not fully wired")
		return nil, nil
	}
	all, err := s.positions.GetAll()
	if err != nil {
		return nil, err
	}
	npcs, err := s.resolver.ListNPCCharacters()
	if err != nil {
		return nil, err
	}
	byID := make(map[string]character.NPCCharacter, len(npcs))
	for _, n := range npcs {
		byID[n.ID] = n
	}

	r2 := radius * radius
	out := make([]events.NpcSpawnIntent, 0)
	for _, p := range all {
		if p.World != "" && p.World != world {
			continue
		}
		dx := p.X - x
		dy := p.Y - y
		dz := p.Z - z
		if dx*dx+dy*dy+dz*dz > r2 {
			continue
		}
		npc, ok := byID[p.CharacterID]
		if !ok {
			continue // position belongs to a non-NPC (e.g. player)
		}
		if npc.Status == "dead" {
			continue
		}
		race := npc.Race
		if race == "" {
			race = "human"
		}
		out = append(out, events.NpcSpawnIntent{
			CharacterID: npc.ID,
			Name:        npc.Name,
			Race:        race,
			MobTemplate: "Character",
			X:           p.X,
			Y:           p.Y,
			Z:           p.Z,
		})
	}
	log.Infof("[Reconcile] FindAliveNearby world=%s center=(%.1f,%.1f,%.1f) r=%.0f → %d/%d",
		world, x, y, z, radius, len(out), len(all))
	_ = math.Sqrt // keep import if compiler optimizes
	return out, nil
}
```

(Drop the unused `math` import if `go vet` complains; the `_ = math.Sqrt` line is just a placeholder — remove both.)

- [ ] **Step 2: Tidy unused import**

Edit `service.go`: remove `"math"` from the import block and remove the `_ = math.Sqrt` line. The squared comparison avoids `math.Sqrt`.

- [ ] **Step 3: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(reconcile): add Service.FindAliveNearby"
```

---

## Task 7: Wire spawn-query handler into route dispatch

**Files:**
- Create: `/Users/canefe/Projects/personal/story-go/internal/reconcile/handler.go`
- Modify: `/Users/canefe/Projects/personal/story-go/cmd/server/main.go`

- [ ] **Step 1: Write the handler**

Create `internal/reconcile/handler.go`:

```go
package reconcile

import (
	"encoding/json"
	"time"

	"github.com/canefe/story-go/internal/bridge"
	"github.com/canefe/story-go/pkg/events"
	"github.com/canefe/story-go/pkg/log"
)

// Handle parses an npc.spawn_query message and replies via reply with an
// npc.spawn_query_response. If reply is nil, logs and drops (the message
// arrived from a transport without per-client reply semantics).
//
// simActive controls the empty-list early return: when sim is not active,
// the handler returns an empty intents list (mirrors HandlePerceptionStimulus
// gating). The boot-vs-running phase distinction is *not* applied here —
// during Boot we want spawns to populate so the plugin can broadcast
// positions and complete the handshake.
func (s *Service) Handle(msg events.BridgeMessage, reply bridge.ReplyFn, simActive bool) {
	if reply == nil {
		log.Warn("[Reconcile] Handle: nil reply, dropping spawn query")
		return
	}
	data, err := json.Marshal(msg.Data)
	if err != nil {
		log.Warnf("[Reconcile] failed to marshal data: %v", err)
		return
	}
	var q events.NpcSpawnQueryEvent
	if err := json.Unmarshal(data, &q); err != nil {
		log.Warnf("[Reconcile] failed to parse npc.spawn_query: %v", err)
		return
	}
	if q.RequestID == "" {
		log.Warn("[Reconcile] npc.spawn_query missing requestId, dropping")
		return
	}

	var intents []events.NpcSpawnIntent
	if !simActive {
		log.Infof("[Reconcile] sim inactive — returning empty for requestId=%s", q.RequestID)
	} else {
		intents, err = s.FindAliveNearby(q.World, q.X, q.Y, q.Z, q.Radius)
		if err != nil {
			log.Warnf("[Reconcile] FindAliveNearby failed: %v", err)
			intents = nil
		}
	}

	resp := events.NpcSpawnQueryResponseEvent{
		RequestID: q.RequestID,
		Intents:   intents,
	}
	respData, _ := toMap(resp)
	reply(events.BridgeMessage{
		Type:      events.NpcSpawnQueryResponse,
		Data:      respData,
		Timestamp: time.Now().UnixMilli(),
		Source:    "story-go",
	})
	log.Infof("[Reconcile] sent response requestId=%s intents=%d", q.RequestID, len(intents))
}

func toMap(v interface{}) (map[string]interface{}, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	var m map[string]interface{}
	err = json.Unmarshal(b, &m)
	return m, err
}
```

- [ ] **Step 2: Wire into `cmd/server/main.go`**

In `cmd/server/main.go`:

Add to imports (alongside existing `internal/sim`, `internal/bridge`):

```go
	"github.com/canefe/story-go/internal/reconcile"
```

After `positionStore` is initialised (~line 146), add:

```go
	reconcileSvc := reconcile.NewService(charResolver, positionStore)
```

Inside `routeMessage`, before the `// Decision response` block, add:

```go
		if msg.Type == events.NpcSpawnQuery && reconcileSvc != nil {
			active := simHandler != nil && simHandler.IsActive()
			reconcileSvc.Handle(msg, reply, active)
			return
		}
```

- [ ] **Step 3: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(reconcile): handle npc.spawn_query with per-frontend reply"
```

---

## Task 8: Test reconcile.Service.FindAliveNearby distance + status filtering

**Files:**
- Create: `/Users/canefe/Projects/personal/story-go/internal/reconcile/service_test.go`

**Why:** The service has no Mongo dependency in its hot path beyond the resolver/positions interfaces, but those are concrete types. We test by constructing a Service with nil deps to confirm the nil guard. Real Mongo testing is integration-only.

- [ ] **Step 1: Write the test**

```go
package reconcile

import "testing"

func TestFindAliveNearbyNilService(t *testing.T) {
	var s *Service
	out, err := s.FindAliveNearby("world", 0, 0, 0, 10)
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if len(out) != 0 {
		t.Fatalf("expected empty out, got %v", out)
	}
}

func TestFindAliveNearbyMissingResolverReturnsEmpty(t *testing.T) {
	s := NewService(nil, nil)
	out, err := s.FindAliveNearby("world", 0, 0, 0, 10)
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if len(out) != 0 {
		t.Fatalf("expected empty out")
	}
}
```

- [ ] **Step 2: Run**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./internal/reconcile/...`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "test(reconcile): nil-safety for FindAliveNearby"
```

---

# Phase 3 — StoryMC ReconciliationService + triggers

## Task 9: Add reconciliation config keys

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/config/ConfigService.kt`
- Modify: `/Users/canefe/Projects/personal/Story/run/plugins/Story/config.yml`

- [ ] **Step 1: Add fields to `ConfigService`**

In `ConfigService.kt`, in the NPC settings region (after `randomPathingCooldown`), add:

```kotlin
    // NPC reconciliation
    var reconcileRadius: Double = 128.0
    var reconcileShortRadius: Double = 32.0
    var reconcileResponseTimeoutMillis: Long = 5000L
    var chunkLoadDebounceTicks: Long = 20L
    var npcRespawnDelaySeconds: Int = 300
    var frontendReadyFallbackMillis: Long = 30000L
```

- [ ] **Step 2: Load them in `loadConfigValues()`**

In `loadConfigValues`, in the NPC behavior block, add:

```kotlin
        reconcileRadius = config.getDouble("npc.reconcileRadius", 128.0)
        reconcileShortRadius = config.getDouble("npc.reconcileShortRadius", 32.0)
        reconcileResponseTimeoutMillis = config.getLong("npc.reconcileResponseTimeoutMillis", 5000L)
        chunkLoadDebounceTicks = config.getLong("npc.chunkLoadDebounceTicks", 20L)
        npcRespawnDelaySeconds = config.getInt("npc.respawnDelaySeconds", 300)
        frontendReadyFallbackMillis = config.getLong("npc.frontendReadyFallbackMillis", 30000L)
```

- [ ] **Step 3: Add defaults to `config.yml`**

In `run/plugins/Story/config.yml`, under the `npc:` section (create it if absent), append:

```yaml
  reconcileRadius: 128
  reconcileShortRadius: 32
  reconcileResponseTimeoutMillis: 5000
  chunkLoadDebounceTicks: 20
  respawnDelaySeconds: 300
  frontendReadyFallbackMillis: 30000
```

- [ ] **Step 4: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(config): add NPC reconciliation config keys"
```

---

## Task 10: Add `ReconciliationService`

**Files:**
- Create: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/npc/ReconciliationService.kt`

- [ ] **Step 1: Write the service**

```kotlin
package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.IntentExecutor
import com.canefe.story.bridge.NpcSpawnQueryEvent
import com.canefe.story.bridge.NpcSpawnQueryResponseEvent
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends NpcSpawnQueryEvents to story-go and applies the resulting
 * NpcSpawnIntents. Tracks pending requests in a concurrent map keyed by
 * requestId; entries time out after [ConfigService.reconcileResponseTimeoutMillis].
 */
class ReconciliationService(private val plugin: Story) {

    private data class PendingRequest(val timeoutTaskId: Int)

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    /** Set when at least one response has been applied; read by FrontendReadinessTracker. */
    @Volatile
    var hasAppliedAnyResponse: Boolean = false
        private set

    fun start() {
        plugin.eventBus.on<NpcSpawnQueryResponseEvent> { handleResponse(it) }
    }

    fun stop() {
        // ConcurrentHashMap is left for GC; no listener teardown needed.
    }

    fun requestNearby(world: String, x: Double, y: Double, z: Double, radius: Double, source: String) {
        val requestId = UUID.randomUUID().toString()
        plugin.logger.info(
            "[Reconcile] requestNearby src=$source requestId=$requestId world=$world " +
                "pos=($x,$y,$z) radius=$radius"
        )
        val timeoutMs = plugin.configService.reconcileResponseTimeoutMillis
        val timeoutTaskId = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (pending.remove(requestId) != null) {
                    plugin.logger.warning("[Reconcile] timeout requestId=$requestId src=$source")
                }
            },
            (timeoutMs / 50L),
        ).taskId
        pending[requestId] = PendingRequest(timeoutTaskId)

        plugin.eventBus.emit(
            NpcSpawnQueryEvent(
                requestId = requestId,
                world = world,
                x = x,
                y = y,
                z = z,
                radius = radius,
            ),
        )
    }

    private fun handleResponse(event: NpcSpawnQueryResponseEvent) {
        val entry = pending.remove(event.requestId)
        if (entry == null) {
            plugin.logger.warning("[Reconcile] response for unknown requestId=${event.requestId}, dropping")
            return
        }
        Bukkit.getScheduler().cancelTask(entry.timeoutTaskId)

        var applied = 0
        var skipped = 0
        for (intent in event.intents) {
            val existing = if (plugin.isNpcRegistryReady) {
                plugin.npcRegistry.all().firstOrNull {
                    plugin.characterRegistry.getCharacterIdForNPC(it) == intent.characterId
                }
            } else null
            if (existing?.isSpawned == true) {
                skipped++
                continue
            }
            IntentExecutor.executeNpcSpawnIntent(plugin, intent)
            applied++
        }
        hasAppliedAnyResponse = true
        plugin.logger.info(
            "[Reconcile] response requestId=${event.requestId} total=${event.intents.size} " +
                "applied=$applied alreadySpawned=$skipped"
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(npc): add ReconciliationService for spawn-query request/reply"
```

---

## Task 11: Wire `ReconciliationService` into `Story.kt`

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/Story.kt`

- [ ] **Step 1: Add field**

Near the other `lateinit var` declarations (around the `positionBroadcaster` declaration, ~line 264):

```kotlin
    lateinit var reconciliationService: ReconciliationService
```

Add the import at the top of the file:

```kotlin
import com.canefe.story.npc.ReconciliationService
```

- [ ] **Step 2: Initialise after `positionBroadcaster.start()`**

Around line 439 (after `positionBroadcaster.start()`), add:

```kotlin
        reconciliationService = ReconciliationService(this)
        reconciliationService.start()
```

- [ ] **Step 3: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(plugin): wire ReconciliationService"
```

---

## Task 12: Trigger reconciliation on player join

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobNPCFactory.kt`

- [ ] **Step 1: Extend `onPlayerJoin`**

In `MythicMobNPCFactory.onPlayerJoin` (lines 286–305), after the `for (entity in player.world.getNearbyEntities(box))` loop (still inside the +40-tick `Runnable`), add:

```kotlin
                plugin.reconciliationService.requestNearby(
                    world = player.world.name,
                    x = player.location.x,
                    y = player.location.y,
                    z = player.location.z,
                    radius = plugin.configService.reconcileRadius,
                    source = "player_join",
                )
```

- [ ] **Step 2: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(npc): request reconciliation sweep on player join"
```

---

## Task 13: Add `ChunkLoadReconciler` with per-player debounce

**Files:**
- Create: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/npc/ChunkLoadReconciler.kt`
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/Story.kt`

- [ ] **Step 1: Write the listener**

```kotlin
package com.canefe.story.npc

import com.canefe.story.Story
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens to ChunkLoadEvent. Finds the nearest online player; if within
 * 8 chunks of the loaded chunk, schedules a debounced reconciliation
 * sweep at the player's live position. Only one pending task per player
 * at a time — new chunk loads cancel and reschedule, coalescing
 * chunk-stream bursts into one request per debounce window.
 */
class ChunkLoadReconciler(private val plugin: Story) : Listener {

    private val pending = ConcurrentHashMap<UUID, Int>() // playerId → taskId

    private companion object {
        const val MAX_DISTANCE_CHUNKS = 8
        const val MAX_DISTANCE_BLOCKS = MAX_DISTANCE_CHUNKS * 16
        const val MAX_DISTANCE_BLOCKS_SQ = MAX_DISTANCE_BLOCKS * MAX_DISTANCE_BLOCKS
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        val world = chunk.world
        val cx = chunk.x * 16 + 8
        val cz = chunk.z * 16 + 8

        val nearest = Bukkit.getOnlinePlayers()
            .filter { it.world == world }
            .minByOrNull {
                val dx = it.location.x - cx
                val dz = it.location.z - cz
                dx * dx + dz * dz
            } ?: return

        val ndx = nearest.location.x - cx
        val ndz = nearest.location.z - cz
        if (ndx * ndx + ndz * ndz > MAX_DISTANCE_BLOCKS_SQ) return

        val playerId = nearest.uniqueId
        pending[playerId]?.let { Bukkit.getScheduler().cancelTask(it) }

        val task = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                pending.remove(playerId)
                val player = Bukkit.getPlayer(playerId) ?: return@Runnable
                if (!player.isOnline) return@Runnable
                plugin.reconciliationService.requestNearby(
                    world = player.world.name,
                    x = player.location.x,
                    y = player.location.y,
                    z = player.location.z,
                    radius = plugin.configService.reconcileRadius,
                    source = "chunk_load",
                )
            },
            plugin.configService.chunkLoadDebounceTicks,
        )
        pending[playerId] = task.taskId
    }
}
```

- [ ] **Step 2: Register listener in `Story.kt`**

Near other `server.pluginManager.registerEvents(...)` calls, add (after `reconciliationService.start()`):

```kotlin
        server.pluginManager.registerEvents(ChunkLoadReconciler(this), this)
```

Add the import:

```kotlin
import com.canefe.story.npc.ChunkLoadReconciler
```

- [ ] **Step 3: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(npc): debounced chunk-load reconciliation sweep"
```

---

## Task 14: Trigger delayed reconciliation on NPC death

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobNPCFactory.kt`

- [ ] **Step 1: Extend `onMythicMobDeath`**

Replace the body of `onMythicMobDeath` (around lines 261–266):

```kotlin
    @EventHandler
    fun onMythicMobDeath(event: MythicMobDeathEvent) {
        val entity = event.entity ?: return
        val storyNpc = plugin.npcRegistry.getByEntity(entity) ?: return
        plugin.npcRegistry.unregister(storyNpc.uniqueId)

        val location = entity.location
        val delayTicks = plugin.configService.npcRespawnDelaySeconds * 20L
        val world = location.world?.name ?: return
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                plugin.reconciliationService.requestNearby(
                    world = world,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    radius = plugin.configService.reconcileShortRadius,
                    source = "death",
                )
            },
            delayTicks,
        )
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(npc): delayed reconciliation sweep on NPC death"
```

---

# Phase 4 — story-sim SimPhase + frontend_ready handler + Time<Virtual> pause

## Task 15: Add `SimPhase` resource and pause `Time<Virtual>` at startup

**Files:**
- Create: `/Users/canefe/Projects/personal/story-sim/src/plugins/sim_phase.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/mod.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/main.rs`

- [ ] **Step 1: Write the plugin**

Create `src/plugins/sim_phase.rs`:

```rust
use bevy::log::info;
use bevy::prelude::*;

#[derive(Resource, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimPhase {
    /// Entities loaded, time paused, awaiting frontend_ready.
    Boot,
    /// Time ticks normally.
    Running,
}

impl Default for SimPhase {
    fn default() -> Self {
        SimPhase::Boot
    }
}

pub struct SimPhasePlugin;

impl Plugin for SimPhasePlugin {
    fn build(&self, app: &mut App) {
        app.init_resource::<SimPhase>()
            .add_systems(Startup, pause_time_at_startup);
    }
}

fn pause_time_at_startup(mut time: ResMut<Time<Virtual>>) {
    time.pause();
    info!("[SimPhase] Time<Virtual> paused — awaiting frontend_ready");
}

/// Public helper: transition Boot → Running and unpause Time<Virtual>.
/// No-op if already Running.
pub fn enter_running(phase: &mut SimPhase, time: &mut Time<Virtual>) {
    if *phase == SimPhase::Running {
        return;
    }
    *phase = SimPhase::Running;
    time.unpause();
    info!("[SimPhase] Boot → Running, Time<Virtual> unpaused");
}
```

- [ ] **Step 2: Register the module**

In `src/plugins/mod.rs`, add `pub mod sim_phase;` next to the other `pub mod` lines.

- [ ] **Step 3: Add the plugin to the app**

In `src/main.rs`, add `SimPhasePlugin` to the `App::new()...add_plugins(...)` chain. Locate the existing plugin registration (search for `SimInitPlugin`) and add `crate::plugins::sim_phase::SimPhasePlugin` next to it. Ensure `SimPhasePlugin` registers before the `time_scale_system` or any other system that mutates `Time<Virtual>` so the startup pause runs first.

- [ ] **Step 4: Build**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: success.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
jj commit -m "feat(sim_phase): pause Time<Virtual> at boot, add SimPhase resource"
```

---

## Task 16: Add `frontend_ready` stream handler

**Files:**
- Create: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/frontend_ready.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`

- [ ] **Step 1: Write the handler**

Create `src/plugins/stream_handlers/frontend_ready.rs`:

```rust
use bevy::prelude::*;

use super::{HandlerEntry, StreamHandlerContext, StreamMessage};
use crate::plugins::sim_phase::{enter_running, SimPhase};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry {
        msg_types: &["frontend_ready"],
        handler: handle,
    });
}

fn handle(_msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let mut phase = ctx.phase.reborrow();
    let mut time = ctx.time.reborrow();
    enter_running(&mut *phase, &mut *time);
}
```

- [ ] **Step 2: Add `phase` and `time` to `StreamHandlerContext`**

In `src/plugins/stream_handlers/mod.rs`, add the new fields to `StreamHandlerContext`:

```rust
    pub phase: ResMut<'w, crate::plugins::sim_phase::SimPhase>,
    pub time: ResMut<'w, Time<Virtual>>,
```

(Confirm the existing struct uses `ResMut` syntax compatible with Bevy's current SystemParam derive — adjust if Bevy version requires a different reborrow approach.)

- [ ] **Step 3: Register the handler module**

In `mod.rs`, add `mod frontend_ready;` near the other `mod` declarations, and inside `handlers()` add:

```rust
    frontend_ready::register(&mut entries);
```

- [ ] **Step 4: Build**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: success. (If reborrow syntax fails, replace `let mut phase = ctx.phase.reborrow(); ...` with `enter_running(ctx.phase.as_mut(), ctx.time.as_mut());` — pick whichever the Bevy version accepts.)

- [ ] **Step 5: Manual smoke test**

Run sim with NATS available. Confirm in logs:
- `[SimPhase] Time<Virtual> paused — awaiting frontend_ready` at startup.
- Publish `{"type":"frontend_ready","data":{"world":"world"}}` to the sim stream subject (e.g. `nats pub story-sim.inbound '{"type":"frontend_ready","data":{}}'`).
- Confirm `[SimPhase] Boot → Running, Time<Virtual> unpaused` appears.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
jj commit -m "feat(stream_handlers): handle frontend_ready, transition Boot→Running"
```

---

# Phase 5 — story-go phase tracking + StoryMC FrontendReadyEvent emission

## Task 17: Add `Phase` to story-go `sim.State`

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/sim/state.go`

- [ ] **Step 1: Add the enum and accessor**

Append to `state.go`:

```go
// Phase tracks the lifecycle of a sim session.
type Phase int

const (
	PhaseUnknown Phase = iota // no sim contact yet
	PhaseBoot                 // sim.init received, awaiting frontend_ready
	PhaseRunning              // frontend_ready forwarded
)

func (p Phase) String() string {
	switch p {
	case PhaseBoot:
		return "boot"
	case PhaseRunning:
		return "running"
	default:
		return "unknown"
	}
}
```

Modify the `State` struct:

```go
type State struct {
	mu       sync.RWMutex
	active   bool
	lastSeen time.Time
	phase    Phase
}
```

Add accessors:

```go
func (s *State) Phase() Phase {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.phase
}

func (s *State) SetPhase(p Phase) {
	s.mu.Lock()
	prev := s.phase
	s.phase = p
	s.mu.Unlock()
	if prev != p {
		log.Infof("[Sim] phase %s → %s", prev, p)
	}
}
```

Add Handler-level pass-throughs:

```go
func (h *Handler) Phase() Phase     { return h.state.Phase() }
func (h *Handler) SetPhase(p Phase) { h.state.SetPhase(p) }
```

- [ ] **Step 2: Reset to `PhaseBoot` on `sim.init`**

In `internal/sim/handler.go`, in the `Handle` switch, replace:

```go
	case events.SimInit:
		h.state.set(true) // sim.init implies the sim is alive
		go h.handleSimInit()
```

with:

```go
	case events.SimInit:
		h.state.set(true)
		h.state.SetPhase(PhaseBoot)
		h.frontendReadySent.Store(false)
		go h.handleSimInit()
```

Add the `frontendReadySent` field to the Handler struct (atomic bool):

```go
import "sync/atomic"
// ... in Handler struct ...
	frontendReadySent atomic.Bool
```

- [ ] **Step 3: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(sim): track Phase (Boot/Running) and reset on sim.init"
```

---

## Task 18: Forward `frontend.ready` to NATS as `frontend_ready`

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/sim/handler.go`
- Modify: `/Users/canefe/Projects/personal/story-go/cmd/server/main.go`

- [ ] **Step 1: Add `ForwardFrontendReady` method**

Append to `internal/sim/handler.go`:

```go
// ForwardFrontendReady is called when the plugin emits frontend.ready.
// Behaviour:
//   - if Phase == PhaseUnknown (no sim.init yet), drop with a warn log;
//   - if Phase == PhaseRunning (already forwarded), drop silently;
//   - otherwise (PhaseBoot), publish frontend_ready to NATS once and
//     transition to PhaseRunning.
//
// frontendReadySent atomic guard prevents double-forwarding within the
// same sim session.
func (h *Handler) ForwardFrontendReady(msg events.BridgeMessage) {
	if h.pub == nil {
		return
	}
	switch h.state.Phase() {
	case PhaseUnknown:
		log.Warn("[Sim] frontend.ready received before sim.init — dropping")
		return
	case PhaseRunning:
		log.Debug("[Sim] frontend.ready ignored — phase already running")
		return
	}
	if !h.frontendReadySent.CompareAndSwap(false, true) {
		return
	}
	out := events.BridgeMessage{
		Type:      "frontend_ready",
		Timestamp: time.Now().UnixMilli(),
		Source:    "story-go",
		Data:      msg.Data,
	}
	if err := h.pub.Publish(out); err != nil {
		log.Warnf("[Sim] frontend_ready publish failed: %v", err)
		h.frontendReadySent.Store(false) // allow retry
		return
	}
	h.state.SetPhase(PhaseRunning)
	log.Info("[Sim] frontend_ready forwarded to NATS, phase → running")
}
```

- [ ] **Step 2: Wire in `cmd/server/main.go`**

Inside `routeMessage`, before the decision-response block, add:

```go
		if msg.Type == events.FrontendReady && simHandler != nil {
			simHandler.ForwardFrontendReady(msg)
			return
		}
```

- [ ] **Step 3: Build and vet**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./...`
Expected: success.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
jj commit -m "feat(sim): forward frontend.ready to NATS, transition phase to running"
```

---

## Task 19: Add `FrontendReadinessTracker` (StoryMC)

**Files:**
- Create: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/FrontendReadinessTracker.kt`
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/Story.kt`
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/npc/PositionBroadcaster.kt`

- [ ] **Step 1: Write the tracker**

```kotlin
package com.canefe.story.bridge

import com.canefe.story.Story
import org.bukkit.Bukkit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when StoryMC has reached a state from which story-sim can safely
 * tick on real positions. Three flags must all be true, after which a single
 * [FrontendReadyEvent] is emitted per sim session:
 *
 * 1. simRunning — last [SimStatusEvent] was running=true
 * 2. positionsTickedSinceSim — at least one [PositionBroadcaster] tick has
 *    completed after simRunning became true
 * 3. reconcileResponseApplied OR fallback timer fired — at least one
 *    [com.canefe.story.npc.ReconciliationService] response has been applied,
 *    OR [ConfigService.frontendReadyFallbackMillis] has elapsed since
 *    simRunning became true (covers headless / no-player scenarios).
 *
 * Reset on simRunning=false so a sim restart re-arms the handshake.
 */
class FrontendReadinessTracker(private val plugin: Story) {

    @Volatile private var simRunning: Boolean = false
    @Volatile private var positionsTicked: Boolean = false
    @Volatile private var reconcileApplied: Boolean = false
    private val emitted = AtomicBoolean(false)
    private var fallbackTaskId: Int = -1

    fun start() {
        plugin.eventBus.on<SimStatusEvent> { onSimStatus(it.running) }
    }

    private fun onSimStatus(running: Boolean) {
        if (!running) {
            // Reset so next sim.init re-arms the handshake.
            simRunning = false
            positionsTicked = false
            reconcileApplied = false
            emitted.set(false)
            cancelFallback()
            return
        }
        if (simRunning) return // already armed
        simRunning = true
        scheduleFallback()
    }

    private fun scheduleFallback() {
        cancelFallback()
        val ticks = plugin.configService.frontendReadyFallbackMillis / 50L
        fallbackTaskId = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                plugin.logger.info("[FrontendReadiness] fallback fired; treating reconcile as satisfied")
                reconcileApplied = true
                tryEmit()
            },
            ticks,
        ).taskId
    }

    private fun cancelFallback() {
        if (fallbackTaskId != -1) {
            Bukkit.getScheduler().cancelTask(fallbackTaskId)
            fallbackTaskId = -1
        }
    }

    fun markPositionsTicked() {
        if (!simRunning) return
        if (positionsTicked) return
        positionsTicked = true
        tryEmit()
    }

    fun markReconcileApplied() {
        if (!simRunning) return
        if (reconcileApplied) return
        reconcileApplied = true
        tryEmit()
    }

    private fun tryEmit() {
        if (!simRunning || !positionsTicked || !reconcileApplied) return
        if (!emitted.compareAndSet(false, true)) return
        cancelFallback()
        val world = Bukkit.getWorlds().firstOrNull()?.name ?: ""
        plugin.logger.info("[FrontendReadiness] emitting FrontendReadyEvent world=$world")
        plugin.eventBus.emit(FrontendReadyEvent(world = world))
    }
}
```

- [ ] **Step 2: Wire into `Story.kt`**

Declare:

```kotlin
    lateinit var frontendReadinessTracker: FrontendReadinessTracker
```

Initialise after `reconciliationService.start()`:

```kotlin
        frontendReadinessTracker = FrontendReadinessTracker(this)
        frontendReadinessTracker.start()
```

Add import:

```kotlin
import com.canefe.story.bridge.FrontendReadinessTracker
```

- [ ] **Step 3: Hook `PositionBroadcaster.tick()`**

In `PositionBroadcaster.kt`, at the end of `tick()` (after the player loop), add:

```kotlin
        try {
            plugin.frontendReadinessTracker.markPositionsTicked()
        } catch (_: UninitializedPropertyAccessException) {
            // tracker not yet wired during tests — ignore
        }
```

- [ ] **Step 4: Hook `ReconciliationService.handleResponse`**

In `ReconciliationService.kt`, at the end of `handleResponse(...)` (after the log line), add:

```kotlin
        try {
            plugin.frontendReadinessTracker.markReconcileApplied()
        } catch (_: UninitializedPropertyAccessException) {
            // ignore in tests
        }
```

- [ ] **Step 5: Compile**

Run: `cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
jj commit -m "feat(bridge): emit FrontendReadyEvent once handshake conditions met"
```

---

## Task 20: End-to-end manual verification

**Files:** none — verification only.

- [ ] **Step 1: Build all three repos green**

```bash
cd /Users/canefe/Projects/personal/story-go && go build ./... && go vet ./... && go test ./...
cd /Users/canefe/Projects/personal/story-sim && cargo build && cargo test
cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin test
```

Expected: all succeed.

- [ ] **Step 2: Manual: fresh boot**

Start sim, story-go, StoryMC fresh (no saved positions). Confirm:
- sim logs `[SimPhase] Time<Virtual> paused — awaiting frontend_ready`.
- A player joins. Within +40 ticks, StoryMC emits `npc.spawn_query` with `source=player_join`.
- story-go responds (intents may be empty on first boot).
- After `PositionBroadcaster.tick()` runs (40 ticks) AND a response is applied, StoryMC emits `frontend.ready`.
- story-go logs `[Sim] frontend_ready forwarded to NATS, phase → running`.
- sim logs `[SimPhase] Boot → Running, Time<Virtual> unpaused`.

- [ ] **Step 3: Manual: chunk-load reconciliation**

Walk 200 blocks; observe debounced `npc.spawn_query` (`source=chunk_load`) approximately 1s after streaming new chunks. Resulting NPCs near the new area appear.

- [ ] **Step 4: Manual: death-respawn**

Set `npc.respawnDelaySeconds: 5` in `config.yml`. Kill an NPC. After 5s, with the player still within `reconcileShortRadius`, NPC respawns at the death location. Confirm log line `[Reconcile] requestNearby src=death`.

- [ ] **Step 5: Manual: dead status excludes spawn**

`mongosh` → `db.characters.updateOne({_id: "<id>"}, {$set: {status: "dead"}})`. Trigger any sweep. Confirm story-go log shows the character is filtered (the response intents list excludes it). NPC does not respawn.

- [ ] **Step 6: Manual: sim-restart re-arms handshake**

Restart sim only. Observe story-go re-receive `sim.init` and `phase unknown → boot`. After StoryMC's next `PositionBroadcaster.tick()` and reconcile response, observe `frontend.ready` re-emitted and sim re-unpauses.

- [ ] **Step 7: Update memory or close out**

If anything diverges from the plan, document the deviation in a follow-up note. Otherwise proceed to PR / merge per `superpowers:finishing-a-development-branch`.

---

## Self-review notes

- Spec coverage:
  - Bridge protocol types (Task 2, 3) ✔
  - `Status` schema field + filter (Task 1, 6) ✔
  - `ReconciliationService` + pending map + timeout (Task 10) ✔
  - Player-join, chunk-load, death triggers (Tasks 12, 13, 14) ✔
  - story-go spawn-query handler with per-frontend reply (Tasks 4, 7) ✔
  - `FindAliveNearby` placement (Task 6) ✔ — new `internal/reconcile` package
  - Config additions (Task 9) ✔
  - `SimPhase` Boot/Running + `Time<Virtual>` pause (Task 15) ✔
  - `frontend_ready` handler in sim (Task 16) ✔
  - story-go `Phase` state + `frontend.ready` forwarding (Tasks 17, 18) ✔
  - StoryMC `FrontendReadinessTracker` (Task 19) ✔
  - sim.init resets phase + clears `frontendReadySent` (Task 17) ✔
  - Drop `frontend.ready` before `sim.init` (Task 18) ✔
  - Observability log lines (throughout) ✔
- Type/name consistency: `requestNearby(source)` is the single sweep entrypoint; all triggers pass a `source` string for log correlation. `markPositionsTicked` / `markReconcileApplied` are the only tracker entrypoints; both no-op pre-`simRunning`.
- No placeholders / TODOs in the steps.
