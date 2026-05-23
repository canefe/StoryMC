# Intent Vocabulary Redesign — Design Spec

**Date:** 2026-05-23
**Status:** Approved design, ready for implementation planning
**Repos touched:** story-proto (SoT), story-sim (Rust), story-go (Go), Story / StoryMC (Kotlin)

## Problem

The sim↔orchestrator↔plugin message vocabulary was never designed as a coherent
set — it accreted one field at a time across four repos. The result:

1. **No source of truth.** Every message type is hand-redefined in Rust, Go, and
   Kotlin (plus a *dead* protobuf path), and they drift silently. A field rename
   in one repo doesn't fail to compile elsewhere — the field just vanishes
   (Kotlin's `ignoreUnknownKeys`, Go's opaque `map[string]interface{}` forwarding).

2. **Intents are too thin, so Kotlin reconstructs meaning the sim already had.**
   The architectural goal is: **Kotlin is a dumb renderer; meaning lives in the
   sim.** Today that's violated in ~11 places (see Appendix A). The two worst:
   - `actionId == "lua_hook"` for *every* hook behavior — the semantic meaning
     (eat vs drink vs rest) is flattened on the wire, forcing Kotlin to keep a
     hardcoded `behaviorId → animation` map to *recover* it.
   - `npc.speak` is two different messages wearing one type (sim sends 2 fields,
     the story-go LLM sends 5).

3. **`npc.state` is overloaded.** It's a 2s position poll that *also* carries
   action/behavior/label fields, so Kotlin must diff consecutive polls to detect
   "a new action started" — reconstructing a transition the sim already knew about.

## Governing Principles

- **Three message categories with distinct contracts** (Section 1).
- **Hybrid responsibility line:** the sim decides *WHAT* and *WHERE*; Kotlin
  executes *HOW-in-Bukkit*. Sim sends the animation, the flee destination, the
  swing direction, the target-kind, the nav thresholds. Kotlin owns only
  Minecraft-engine physics the sim cannot model: nav re-issue when MythicMobs
  cuts a path short, and standable-block validation.
- **Edge transitions ARE intents.** "Start eating", "set this label", "go here"
  are commands the sim issues *once*. The sim never makes Kotlin reconstruct a
  transition by diffing state.
- **Proto is the single source of truth.** The whole vocabulary lives in
  `story-proto/story/v1/`; all three repos codegen from it; drift → compile error.

---

## Section 1 — The Three-Category Model

| Category | What it is | Cadence | Kotlin's job |
|---|---|---|---|
| **Snapshot** (`npc.state`) | Pure continuous body state — position, health. No action/behavior/label fields. | 2s poll | Render position. Nothing to diff. |
| **Intents** | Discrete commands the sim issues **once**, each carrying full meaning. | Edge (on transition) | Execute once. Cache sticky ones for late-join replay. |
| **Lifecycle** (`sim.init`, `sim.status`, `sim.affordance_registry`) | Sim↔plugin handshake / registry. | On change | Connection management. |

---

## Section 2 — The Intent Vocabulary (frozen)

All payloads are the `data` of the existing envelope `{type, data, timestamp,
source}`. The `type` string is retained for dispatch; `data` is proto-JSON of the
generated type.

### Snapshot (NOT an intent)
```
npc.state        { char, x, y, z, health }            # position / health ONLY
```
Stripped of `actionId`, `behaviorId`, `actionLabel`, and `stats`. Stats already
reach the plugin via the separate `character.stats_update` message (story-go
derives it). `actionId` is already dead on the consumer side.

### Behavior intents (STICKY — Kotlin caches per-NPC, replays to late-joiners)
```
behavior.set     { char, behaviorId, animation: AnimationKind, label? }
behavior.clear   { char }
```
- `animation` is a **closed proto enum** `AnimationKind { NONE, EAT, DRINK, REST,
  SOCIALIZE, TRADE, ... }`. Adding an animation = edit proto + regen. No Kotlin
  mapping table. The enum value flows straight to the MythicMobs signal.
- `behavior.clear` is explicit — Kotlin never guesses what an empty label means.
- Kills Appendix A items #1 (anim map), #2 (lua_hook collapse), #3 (fire-once
  diffing), #4 (label stickiness).

### Action intents (one-shot, fire-and-forget)
```
npc.item_transfer { char, toChar, item, qty, reason: TransferReason }
npc.emote         { char, emote }
```
- `reason` becomes an enum `TransferReason { GIVE, SELL, STEAL, ... }` (kills A#10
  free-form string).

### Frontend intents (carry `intentId`, expect an outcome back)
```
go_to       { char, intentId, x, y, z, world, arrivalRange=3.5, stallTimeout=30, maxDuration=300 }
flee        { char, intentId, x, z }
attack      { char, intentId, target, targetKind: TargetKind, swing: SwingDir }
look_at     { char, intentId, target?, x?, y?, z?, maxYaw, maxPitch }
set_target  { char, intentId, target, targetKind: TargetKind }
clear_target{ char, intentId }
```
- **`go_to` unifies the old `npc.move` + `navigate_to`** (kills A#11 dual movement
  lanes). Carries the target `world` (honored — see Section 4 Risk 5) and
  sim-tunable nav thresholds with sane defaults; Kotlin owns the re-issue
  *mechanism*, sim owns the *numbers*.
- **`flee` sends a final destination**, not an anchor+band. The sim does the
  away-vector math (kills A#5 Kotlin flee-math). Kotlin walks + re-paths only.
- **`attack`** carries `swing: SwingDir` chosen by the sim (kills A#6 Kotlin
  random-swing) and `targetKind` so targets aren't Player-only (kills A#8).
- **`look_at`** becomes a real sim-emittable variant (kills A#9 dead Kotlin code).
- `TargetKind { PLAYER, NPC }`, `SwingDir { OVERHEAD, LEFT, RIGHT, THRUST }` are
  proto enums.

### Communication
```
npc.speak   { char, message, conversationId?, addressedToId?, addressedToName? }
```
**One schema, two producers.** Sim-speak leaves the conversation fields null;
LLM-speak (story-go) fills them. Resolves the two-producers-one-type split. The
phantom `target` field (sent by neither producer) is removed.

### Outcome lane (Kotlin → sim)
```
intent.completed { char, intentId, primitive }
intent.rejected  { char, intentId, primitive, reason: RejectReason }
```
- `RejectReason { UNREACHABLE, TARGET_NOT_FOUND, INVALID, TIMEOUT, SUPERSEDED }`
  (proto enum). `SUPERSEDED` = a newer intent for the same NPC replaced this one
  before it resolved (see Section 4, watcher contention).

---

## Section 3 — Schema SoT, Codegen & Wire

**Source of truth:** `story-proto/story/v1/` — revived and made real. The entire
vocabulary above is defined in `.proto`. Adding/renaming a field = edit `.proto` +
regen → all three repos fail to **compile** on drift.

**Codegen (3 targets):**
- **Rust (story-sim):** `prost` via `build.rs` → replaces every `json!` / `format!`
  emit site (including the raw-string `npc.move` template in `movement.rs`).
- **Go (story-go):** `protoc-gen-go` → replaces the hand-kept structs and the
  snake_case↔camelCase re-key in `handler.go`.
- **Kotlin (StoryMC):** `protoc` Kotlin/Java → replaces the `@Serializable` DTOs
  in `DomainEvents.kt`.

**Wire format:** **proto3 canonical JSON** over the existing WebSocket + NATS. We
do **not** rip out WebSocket or switch to gRPC binary. The envelope
`{type, data, timestamp, source}` stays; `data` becomes proto-JSON of a generated
type. Lowest-risk path: same transports, typed payloads.

**Dead gRPC server removed:** story-go's `internal/grpc` server
(`server.go:116-117`, running on a port with no client) and the `MultiSender`
gRPC branch (`server.go:158-159`) and the `StoryBridge.EventStream` service def
are deleted. The *message* protos are kept (now used by the JSON path); only the
unused transport service goes.

---

## Section 4 — Migration: Design-All, Migrate-A-Slice

**This project freezes the COMPLETE proto vocabulary** (Section 2) and migrates
**one vertical slice end-to-end** to prove the proto→Rust/Go/Kotlin pipeline.

**Slice = the movement path** — chosen because it is (a) the only intent you can
*see* working in-game (the NPC walks, arrives, or fails) and (b) the only slice
that exercises the **full outcome round-trip** (sim issues → Kotlin executes →
`intent.completed`/`intent.rejected` back), which is the harder half of the
pipeline. The behavior/animation path is one-directional by comparison.

**`go_to` unifies BOTH current movement lanes** (full unify, kills A#11 now):
- `navigate_to` — the Lua `char:navigateTo()` frontend.intent. *Already* mints an
  intentId, has a `PendingIntents` entry, and expects an outcome.
- `npc.move` — emitted by the **ECS** `publish_movement_intents_system`
  (`movement.rs:14-124`), driven by action-target state. **Today has NO intentId
  and NO outcome.** Folding it into `go_to` is the largest behavioral change: the
  ECS system must start minting intentIds + pushing `PendingIntents` entries.

Slice steps:
1. Stand up proto + codegen for all 3 repos. *The pipeline itself is the hard
   part — proving it once de-risks every later slice.*
2. Define `go_to` in proto (Section 2) and codegen it.
3. **story-sim:** make `char:navigateTo()` emit `go_to` (was `navigate_to`); make
   the ECS `publish_movement_intents_system` emit `go_to` *with a minted intentId
   + PendingIntents entry* (was the raw-`format!` `npc.move`, no outcome). Remove
   the `npc.move` and `navigate_to` emit sites.
4. **Lua:** rename the primitive-string lookup in
   `head_to_known_location.lua:148` from `"navigate_to"` to `"go_to"` (else
   UNREACHABLE detection silently breaks — see Risk 2).
5. **story-go:** add `go_to` to `IsSimEvent` so it routes via
   `broadcastToFrontends` like the old frontend.intent (the outcome lane already
   needs zero Go change — Go forwards it verbatim).
6. **StoryMC:** single `go_to` handler that builds the target Location and runs
   the existing `NavWatchState` watcher (arrival/stall/timeout/re-issue —
   unchanged, it's target/intent-agnostic). Emit `intent.completed`/
   `intent.rejected` with the `go_to`'s intentId. Delete `executeMoveIntent` and
   the old `navigate_to` branch.
7. **Watcher contention (Risk 3):** the single `navWatchers` map is keyed by
   characterId. When a new `go_to` arrives for an NPC with a live watcher, emit
   `intent.rejected(SUPERSEDED)` for the *old* intentId, cancel the old watcher,
   then start the new one. No leaked `PendingIntents` on the sim side.
   `RejectReason` gains `SUPERSEDED`.
8. **Unify world-resolution (Risk 5):** `go_to` carries a `world`. Today
   `navigate_to` ignores it (uses first world, `IntentExecutor.kt:647`) while
   `npc.move` honors `intent.world` (`:325-328`). `go_to` honors the supplied
   world — this also addresses the world-mismatch UNREACHABLE bug (obs 6590).
9. Delete the dead gRPC server (Section 3).

**Out of scope this project (migrated in follow-ups against the frozen schema):**
`behavior.set`/`behavior.clear` (and the npc.state action-field strip), `flee`,
`attack`, `look_at`, `set_target`, `clear_target`, `npc.speak`,
`npc.item_transfer`, `npc.emote`. These stay on the old hand-JSON path during
this project.

**Coexistence:** new proto-JSON `go_to` and any remaining old hand-JSON types
travel the same WebSocket side by side, dispatched by `type` string. Because
`go_to` *replaces* both old movement types at their emit sites, there is no period
where old `navigate_to`/`npc.move` and new `go_to` race for the same NPC — the
sim stops emitting the old types entirely. No big-bang elsewhere.

### Confirmed consumer impact (verified in code, all 3 repos)
- **EMIT (sim):** `navigate_to` via the frontend.intent Descriptor
  (`frontend_intent.rs:137-149`), single publish point `:245-296`; caller is the
  Lua `char:navigateTo()` bind (`execution.rs:1129-1144`), only Lua caller is
  `head_to_known_location.lua:132`. `npc.move` is a lone raw `format!`
  (`movement.rs:116-122`) from the ECS system, **disjoint** caller surface
  (action-target state, `target_finding.rs`). No other emit sites.
- **story-go is pure pass-through** for both `frontend.intent`/`navigate_to`
  (`handler.go:128-129`) and `npc.move` (generic NATS fan-out, `nats.go:53-72`),
  and for the outcome lane (`handler.go:275-288`, re-publishes verbatim). It
  parses no intentId/coords/outcome. **Only Go change:** add `go_to` to
  `IsSimEvent` (`events.go:6-12`) + gRPC deletion.
- **StoryMC consumers:** `npc.move`→`executeMoveIntent`
  (`IntentExecutor.kt:313-332`, fire-and-forget, no watcher/outcome — to be
  deleted); `navigate_to`→`when` branch (`:646-663`) + `NavWatchState` machine
  (`:89-239`); outcomes emitted via `completeIntent`/`rejectIntent`
  (`:816-835`)→`IntentCompletedEvent`/`IntentRejectedEvent`
  (`DomainEvents.kt:447-470`), encoded `WebSocketTransport.kt:190-191`.
- **NavWatchState is target/intent-agnostic** (`:232-239`): reads only live NPC
  position + the captured target Location, nothing from the intent. It works
  unchanged for `go_to` as long as `go_to` carries `x/y/z` + `world` + `intentId`.
- **Tests to update / add:** existing `AuthoringIntentWireTest.kt`,
  `ActionLabelDiffTest.kt`, `AnimationDiffTest.kt` unaffected by movement; add
  `go_to` wire round-trip test (decode + outcome emit), supersede-reject test.

### Verification (in-game)
- Drive an NPC to a known location (Lua `navigateTo` path) and via the ECS
  action-target path; confirm both now travel as `go_to` and both report an
  outcome (arrival → `intent.completed`; blocked → `intent.rejected`).
- Issue a second `go_to` to a walking NPC; confirm the first intent gets
  `intent.rejected(SUPERSEDED)` and no `PendingIntents` "lost mint" eviction
  log appears on the sim side.
- Cross-world target resolves correctly (no spurious UNREACHABLE from the old
  first-world assumption).

---

## Appendix A — The 11 "Kotlin implements meaning" smells (from inventory)

Resolution column marks which are fixed by THIS project's slice (★, the movement
path) vs. a later slice against the frozen schema (·).

| # | Smell | file:line | Resolution |
|---|---|---|---|
| 1 | `behaviorId → animation` hardcoded map | IntentExecutor.kt:57-63, :493 | · `AnimationKind` enum in `behavior.set` |
| 2 | `actionId == "lua_hook"` collapse forces #1 | entity_state_broadcast.rs:75; IntentExecutor.kt:490 | · semantic `behavior.set` |
| 3 | animation fire-once diffing + cache | IntentExecutor.kt:48, :77-81 | · edge-triggered intent |
| 4 | action-label stickiness / empty-suppress | IntentExecutor.kt:26, :38-43 | · explicit `behavior.clear` |
| 5 | `flee_from` destination math in Kotlin | IntentExecutor.kt:664-716 | · `flee` sends dest |
| 6 | `attempt_hit` random swing default | IntentExecutor.kt:785 | · `swing: SwingDir` |
| 7 | navigate_to arrival/stall/re-issue loop | IntentExecutor.kt:89-239 | ★ mechanism stays Kotlin (correct per Hybrid line); thresholds now in `go_to`; watcher reused |
| 8 | set_target Player-only constraint | IntentExecutor.kt:638 | · `targetKind` |
| 9 | look_at exists only in Kotlin | IntentExecutor.kt:717-759 | · real `look_at` intent |
| 10 | item_transfer `reason` opaque string | lua_world_api.rs:3028; IntentExecutor.kt:527 | · `TransferReason` enum |
| 11 | two unmodeled movement lanes | movement.rs:118 vs frontend.intent | ★ unified `go_to` (ECS lane gains intentId+outcome) |

## Appendix B — Notable wire bugs the redesign fixes
- `npc.speak` two-producers-one-type schema split
  (frontend_intent.rs:189-202 vs story-go generate.go:190-218).
- npc.state `stats` emitted but silently dropped (no Kotlin field).
- snake_case (sim) vs camelCase (story-go `CharacterStatsUpdateEvent`) requiring a
  manual re-key in handler.go:794-800.
- `npc.move` emitted via raw `format!` string template, bypassing serde
  (movement.rs:118-122).
