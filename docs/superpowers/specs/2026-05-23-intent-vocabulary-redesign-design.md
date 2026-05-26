# Intent Vocabulary Redesign — Design Spec

**Date:** 2026-05-23
**Status:** ✅ IMPLEMENTED (2026-05-25). All three phases landed; the `go_to`
proving slice is live, compiling, and passing wire round-trip tests in all four
repos. The out-of-scope vocabulary (behavior.set/clear, flee, attack, look_at,
set_target, clear_target, npc.speak, item_transfer, emote) is frozen in proto
and migrated in follow-ups as originally planned. See the **Implementation status**
section below for the per-phase commit trail. (Original status: "Approved design,
ready for implementation planning.")
**Repos touched:** story-proto (SoT), story-sim (Rust), story-go (Go), Story / StoryMC (Kotlin)

> **Implementation status (added 2026-05-25).** This spec is built. The proving
> slice (`go_to` movement with full outcome round-trip + supersede) works
> end-to-end across the wire; `npc.move` and `navigate_to` are removed at their
> emit sites. Verified: clean `compileKotlin`/`compileTestKotlin` and green
> `GoToWireTest` / `ItemTransferWireTest` / `AuthoringIntentWireTest` in StoryMC.
> NOT yet verified: live in-game walk (an NPC physically arriving/stalling on a
> real server) — that is the remaining manual check.
>
> **Phase 1 (proto SoT + codegen):**
> - story-proto: `5bc6640` add go_to + frontend-intent vocabulary and intent outcomes; `c7f50a0` cross-repo go_to golden json.
> - story-go: `e36a751` bump submodule + regen go types; `c1c5583` go_to golden decode.
> - story-sim: `73a154f` add prost codegen + serde camelCase json round-trip; `1e11d0a` go_to golden decode.
> - StoryMC: `e0a8ef5` add protobuf gradle codegen + go_to canonical-json round-trip test; `a5c8509` go_to golden decode.
> - Plan doc: `67aa130` "go_to slice implementation plan (3 phases)".
>
> **Phase 2 (gRPC removal + query re-home):**
> - story-go: `2e86853` delete dead gRPC server + dead sim worldStateQuerier; `75ef030` regen without grpc service stub.
> - story-proto: `a4d2754` remove StoryBridge gRPC service (submodule pin `069268a`).
>
> **Phase 3 (go_to movement feature, the visible slice):**
> - story-sim: `0397c3b` navigate_to lua path emits go_to envelope with world; `9e3b281` ECS movement emits tracked go_to (mints intentId + PendingIntents, was untracked npc.move); `df804f8` head_to_known_location reads go_to outcome.
> - story-go: `792b52c` route go_to to frontends as sim event; `a3e1cd2` forward intent outcome events to NATS.
> - StoryMC: `f30ff2a` go_to handler with outcome round-trip, remove npc.move + navigate_to paths; `7413757` supersede prior go_to with SUPERSEDED reject.
>
> The remaining `navigate_to` references in StoryMC `IntentExecutor.kt` are the
> Bukkit-side re-issue *mechanism* (`npc.navigateTo(target)`) and its comments —
> this is correct per the Hybrid line: Kotlin owns the re-path mechanism, the sim
> owns the thresholds carried on `go_to`.

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
- Reject reason is a proto enum. StoryMC ALREADY has a Kotlin `RejectionReason`
  enum (`DomainEvents.kt:411-429`: `NPC_NOT_FOUND, TARGET_NOT_FOUND, OUT_OF_RANGE,
  NPC_DEAD, NPC_BUSY, INVALID_PRIMITIVE, UNSUPPORTED_BACKEND, EXECUTION_ERROR,
  UNREACHABLE`). The proto enum is the **superset** of that, with `SUPERSEDED`
  and `TIMEOUT` added; the generated type REPLACES the hand-written
  `RejectionReason`. `SUPERSEDED` = a newer intent for the same NPC replaced this
  one before it resolved (see Section 4 Phase 3, watcher contention).

---

## Section 3 — Schema SoT, Codegen & Wire

**Source of truth:** `story-proto/story/v1/` — a real git repo, consumed by
story-go as the `proto/` **git submodule** (`.gitmodules`, commit `d8a811d`).
story-go already runs codegen via `make proto` (raw `protoc` + `protoc-gen-go`,
output committed to `gen/story/v1/*.pb.go`). "Revive and make real" therefore
means: (a) ADD the missing vocabulary to the `.proto` (none of `go_to`,
`navigate_to`, `npc.move`, `intent.completed/rejected` exist in proto today —
they live only as hand-JSON), and (b) EXTEND codegen to the two repos that have
none. Adding/renaming a field = edit `.proto` + regen → all three repos fail to
**compile** on drift.

**Codegen (3 targets):**
- **Go (story-go):** *pipeline EXISTS.* Bump the `proto/` submodule, `make proto`,
  commit `gen/`. Replace the hand-kept `events.go` structs + the
  snake_case↔camelCase re-key in `handler.go` with generated types.
- **Rust (story-sim):** *NET-NEW.* Add `prost` + `prost-build` deps, a `build.rs`
  that compiles the vendored/submoduled `.proto`, and replace every `json!` /
  raw-`format!` emit site (incl. the `npc.move` template in `movement.rs`).
- **Kotlin (StoryMC):** *NET-NEW.* Add a protobuf Gradle plugin
  (`com.google.protobuf` or Wire) generating Kotlin/Java types, replacing the
  `@Serializable` DTOs in `DomainEvents.kt` / `StoryEvent.kt`.

**Wire format:** **proto3 canonical JSON** over the existing WebSocket + NATS. We
do **not** rip out WebSocket or switch to gRPC binary. The envelope
`{type, data, timestamp, source}` stays; `data` becomes proto-JSON of a generated
type. Lowest-risk path: same transports, typed payloads. (The Go `gen/` proto
types currently feed ONLY the gRPC path via `convert.go`; this project makes them
feed the JSON path too.)

**gRPC server removed + queries re-homed.** CORRECTION to an earlier assumption:
the gRPC server is **not** dead — it is wired into the `MultiSender`
(`server.go:158-159`), serves a live listener (`server.go:520-521`), and is the
sole consumer of the generated proto types via `internal/grpc/convert.go`. It
also backs `QueryWorldState` / `QueryCharacterState` (`server.go:325, 345`).
Removing it therefore requires **re-homing those two queries onto the WebSocket
request/response path** before deleting the `StoryBridge` service, the
`MultiSender` gRPC branch, the listener, and `convert.go`. The *message* protos
are kept (now used by the JSON path); the `service StoryBridge` def and the gRPC
transport go. This is Phase 2 and is independent of the movement feature.

---

## Section 4 — Migration: One Plan, Three Phases

**This project freezes the COMPLETE proto vocabulary** (Section 2) and delivers it
as **one phased plan**. The three phases are independently testable but executed
in order in a single plan document.

The **movement path** is the proving slice for the pipeline (Phase 3) because it
is (a) the only intent you can *see* working in-game (the NPC walks, arrives, or
fails) and (b) the only slice that exercises the **full outcome round-trip** (sim
issues → Kotlin executes → `intent.completed`/`intent.rejected` back).

### Phase 1 — Proto pipeline + codegen (foundation, no behavior change)
1. **story-proto:** add the full frozen vocabulary (Section 2) to `story/v1/`,
   including `go_to`, the outcome messages, and the movement enums. (The other
   vocabulary messages are added to proto now but only `go_to` is wired this
   project.)
2. **story-go (pipeline EXISTS):** bump the `proto/` submodule to the new commit,
   `make proto`, commit `gen/story/v1/*.pb.go`.
3. **story-sim (NET-NEW):** add `prost` + `prost-build`, a `build.rs` compiling
   the `.proto` (vendored or submoduled), and confirm generated Rust types build.
4. **StoryMC (NET-NEW):** add a protobuf Gradle plugin generating Kotlin/Java
   types from the `.proto`; confirm they build.
5. **Pipeline proof:** a `go_to` value round-trips proto-JSON encode/decode
   identically through prost (Rust), protoc-gen-go (Go), and the Gradle plugin
   (Kotlin) — a cross-repo golden-JSON test.

### Phase 2 — gRPC removal + query re-home (independent of movement)
6. Re-home `QueryWorldState` / `QueryCharacterState` (`server.go:325, 345`) onto
   the WebSocket request/response path (they currently traverse the gRPC server).
7. Remove the `StoryBridge` gRPC service: delete the `MultiSender` gRPC branch
   (`server.go:158-159`), the listener (`server.go:520-521`), `internal/grpc/`
   (incl. `convert.go`), and the `service StoryBridge` def in proto. Keep the
   message protos.

### Phase 3 — go_to movement feature (the visible slice)
**`go_to` unifies BOTH current movement lanes** (full unify, kills A#11 now):
- `navigate_to` — the Lua `char:navigateTo()` frontend.intent. *Already* mints an
  intentId, has a `PendingIntents` entry, and expects an outcome.
- `npc.move` — emitted by the **ECS** `publish_movement_intents_system`
  (`movement.rs:14-124`), driven by action-target state. **Today has NO intentId
  and NO outcome.** Folding it into `go_to` is the largest behavioral change: the
  ECS system must start minting intentIds + pushing `PendingIntents` entries.

8. **story-sim:** make `char:navigateTo()` emit `go_to` (was `navigate_to`); make
   the ECS `publish_movement_intents_system` emit `go_to` *with a minted intentId
   + PendingIntents entry* (was the raw-`format!` `npc.move`, no outcome). Remove
   the `npc.move` and `navigate_to` emit sites. (`PendingIntents` method is
   `last_for_primitive` / `push` / `resolve`, not `lastFor`.)
9. **Lua:** rename the primitive-string lookup in
   `head_to_known_location.lua:148` from `"navigate_to"` to `"go_to"` (else
   UNREACHABLE detection silently breaks — Risk 2).
10. **story-go:** add `go_to` to `IsSimEvent` (`internal/sim/events.go`) so it
    routes via `broadcastToFrontends` like the old frontend.intent. The outcome
    lane already needs zero Go change (forwarded verbatim, `handler.go:275-288`).
11. **StoryMC:** single `go_to` handler that builds the target Location (honoring
    the supplied `world`) and runs the existing `NavWatchState` watcher
    (arrival/stall/timeout/re-issue — unchanged, it's target/intent-agnostic).
    Emit `intent.completed`/`intent.rejected` with the `go_to`'s intentId. Delete
    `executeMoveIntent` and the old `navigate_to` branch.
12. **Watcher contention (Risk 3):** the single `navWatchers` map is keyed by
    characterId. When a new `go_to` arrives for an NPC with a live watcher, emit
    `intent.rejected(SUPERSEDED)` for the *old* intentId, cancel the old watcher,
    then start the new one. No leaked `PendingIntents` on the sim side.
    `RejectReason` gains `SUPERSEDED`.
13. **Unify world-resolution (Risk 5):** `go_to` carries `world`. Today
    `navigate_to` ignores it (first world, `IntentExecutor.kt:647`) while
    `npc.move` honors `intent.world` (`:325-328`). `go_to` honors the supplied
    world — also fixes the world-mismatch UNREACHABLE bug (obs 6590).

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
  parses no intentId/coords/outcome. **Phase 3 Go change:** add `go_to` to
  `IsSimEvent` (`internal/sim/events.go`). (gRPC removal + query re-home is the
  separate Phase 2.)
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
