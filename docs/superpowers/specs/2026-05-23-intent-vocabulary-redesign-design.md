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
go_to       { char, intentId, x, y, z, arrivalRange=3.5, stallTimeout=30, maxDuration=300 }
flee        { char, intentId, x, z }
attack      { char, intentId, target, targetKind: TargetKind, swing: SwingDir }
look_at     { char, intentId, target?, x?, y?, z?, maxYaw, maxPitch }
set_target  { char, intentId, target, targetKind: TargetKind }
clear_target{ char, intentId }
```
- **`go_to` unifies the old `npc.move` + `navigate_to`** (kills A#11 dual movement
  lanes). Carries sim-tunable nav thresholds with sane defaults; Kotlin owns the
  re-issue *mechanism*, sim owns the *numbers*.
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
- `RejectReason { UNREACHABLE, TARGET_NOT_FOUND, INVALID, TIMEOUT }` (proto enum).

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

**Slice = the behavior/animation path** (freshest pain, worst hacks):
1. Stand up proto + codegen for all 3 repos. *The pipeline itself is the hard
   part — proving it once de-risks every later slice.*
2. Migrate **only** `npc.state` (strip action + stats fields → position/health
   only) and add `behavior.set` / `behavior.clear`.
3. Delete the corresponding Kotlin hacks: anim map (A#1), lua_hook reliance (A#2),
   fire-once diffing (A#3), label stickiness (A#4).
4. Add the late-join `currentIntent[npc]` replay cache (write-on-receive,
   replay-on-viewer-join). This is a *small* render cache, not transition
   inference.
5. Delete the dead gRPC server (Section 3).

**Out of scope this project (migrated in follow-ups against the frozen schema):**
`go_to`, `flee`, `attack`, `look_at`, `set_target`, `clear_target`, `npc.speak`,
`npc.item_transfer`, `npc.emote`, and the outcome lane. These stay on the old
hand-JSON path during this project.

**Coexistence:** new proto-JSON types and old hand-JSON types travel the same
WebSocket side by side, dispatched by `type` string. No big-bang cutover.

### Confirmed consumer impact (verified in code, all 3 repos)
- **Only one production consumer** of npc.state's action fields:
  `IntentExecutor.executeNpcStateIntent` (`IntentExecutor.kt:480, 493`). No other
  listener, analytics, or persistence.
- **`actionId` is already dead** on the consumer side — nothing reads it.
- **story-go is forward-only** for these fields (opaque `map` passthrough, no
  typed binding) — **zero Go change** needed for npc.state to keep working after
  the strip. Go changes are only: new `behavior.set` handling (forward),
  snake/camel re-key cleanup, gRPC deletion.
- **`stats` on npc.state has no consumer** — purifying npc.state is free.
- **Tests to update:** `ItemTransferWireTest.kt` (asserts `NpcStateIntent` decodes
  actionId/actionLabel), `ActionLabelDiffTest.kt`, `AnimationDiffTest.kt`.

### Verification (in-game)
Drive an NPC's need (`/story npc need <char> thirst 20`); confirm:
- the drink animation + label fire once per action **with the anim map deleted**,
- a late-joining player sees the in-flight animation/label (cache replay),
- npc.state still drives position correctly with action fields stripped.

---

## Appendix A — The 11 "Kotlin implements meaning" smells (from inventory)

Resolution column marks which are fixed by THIS project's slice (★) vs. a later
slice against the frozen schema (·).

| # | Smell | file:line | Resolution |
|---|---|---|---|
| 1 | `behaviorId → animation` hardcoded map | IntentExecutor.kt:57-63, :493 | ★ `AnimationKind` enum in `behavior.set` |
| 2 | `actionId == "lua_hook"` collapse forces #1 | entity_state_broadcast.rs:75; IntentExecutor.kt:490 | ★ semantic `behavior.set` |
| 3 | animation fire-once diffing + cache | IntentExecutor.kt:48, :77-81 | ★ edge-triggered intent |
| 4 | action-label stickiness / empty-suppress | IntentExecutor.kt:26, :38-43 | ★ explicit `behavior.clear` |
| 5 | `flee_from` destination math in Kotlin | IntentExecutor.kt:664-716 | · `flee` sends dest |
| 6 | `attempt_hit` random swing default | IntentExecutor.kt:785 | · `swing: SwingDir` |
| 7 | navigate_to arrival/stall/re-issue loop | IntentExecutor.kt:89-239 | · mechanism stays Kotlin; thresholds in `go_to` |
| 8 | set_target Player-only constraint | IntentExecutor.kt:638 | · `targetKind` |
| 9 | look_at exists only in Kotlin | IntentExecutor.kt:717-759 | · real `look_at` intent |
| 10 | item_transfer `reason` opaque string | lua_world_api.rs:3028; IntentExecutor.kt:527 | · `TransferReason` enum |
| 11 | two unmodeled movement lanes | movement.rs:118 vs frontend.intent | · unified `go_to` |

## Appendix B — Notable wire bugs the redesign fixes
- `npc.speak` two-producers-one-type schema split
  (frontend_intent.rs:189-202 vs story-go generate.go:190-218).
- npc.state `stats` emitted but silently dropped (no Kotlin field).
- snake_case (sim) vs camelCase (story-go `CharacterStatsUpdateEvent`) requiring a
  manual re-key in handler.go:794-800.
- `npc.move` emitted via raw `format!` string template, bypassing serde
  (movement.rs:118-122).
