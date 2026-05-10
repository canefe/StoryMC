# Directional Combat System

**Status:** Design — pending implementation plan
**Date:** 2026-05-10
**Scope:** Story (Paper plugin), StoryClient (Fabric mod), story-sim (Rust)

## 1. Goal

Replace vanilla Minecraft melee with a Bannerlord-style directional combat system. Cinematic, weighty, committed swings; directional blocks with parry windows; stamina economy; stat-driven feel. Applies to all melee actors (players, MythicMobs, StoryNPCs) — vanilla mobs are out of scope because the world uses MythicMobs exclusively.

## 2. Non-Goals

- Custom swing animations beyond what server-pushed pose deltas (yaw/pitch/position offsets) can drive on the Fabric client mod
- Anti-cheat hardening beyond "client sends intents, server arbitrates"
- Ranged combat (bows, crossbows) — out of scope
- PvP-only or PvE-only modes — system is universal

## 3. System Overview

Every melee actor is a `Combatant` driven by a server-authoritative state machine: `Idle → Windup(dir) → Active → Recovery`, with `Blocking(dir)` and `Staggered` as parallel states. Hits resolve at `Active` ticks via yaw cone (overhead/left/right) or raycast (thrust). Defender state at hit-time decides outcome: unblocked, parried, perfect-block, partial-block, or bad-block.

### Three-process division of labor

- **Story (Paper plugin)** — owns the canonical state machine, hit detection, damage application, parry windows, stagger, stamina drain. Cancels vanilla `EntityDamageByEntityEvent`. Owns combat AI for NPCs (smart attack-direction picks + reactive block + parry attempts + feints), gated by combat skill.
- **StoryClient (Fabric mod)** — captures mouse-drag direction at LMB/RMB-press; sends intent packets; predicts swing animation locally; renders directional HUD (stamina bar, current commit, parry-window flash, outcome banner); plays attacker-pose animations on remote entities (yaw/pitch/roll deltas) driven by server `CombatStatePushS2C`.
- **story-sim** — source of truth for stats (`combat`, `agility`, `endurance`, `strength`) and stamina pool/regen. Plugin reads via existing snapshot path. Sim sets strategic mode (engage/flee/defensive) per existing `react_to_attacker.lua` style. Combat outcomes flow back as stimuli: `MeleeHitLanded`, `MeleeParried`, `MeleeStaggered`, `StaminaBroken`.

### The `Combatant` seam

The state machine, hit detection, and damage code never branch on actor type. They call `combatant.facingYaw()`, `combatant.stamina()`, `combatant.takeDamage(...)`, `combatant.onPoseChange(state)`. Adapters (`PlayerCombatant`, `NpcCombatant`) implement the surface.

## 4. State Machine

```kotlin
sealed class CombatState {
    object Idle : CombatState()
    data class Windup(val dir: SwingDir, val ticksLeft: Int, val canFeint: Boolean) : CombatState()
    data class Active(val dir: SwingDir, val ticksLeft: Int) : CombatState()
    data class Recovery(val ticksLeft: Int) : CombatState()
    data class Blocking(val dir: SwingDir, val parryWindowTicksLeft: Int) : CombatState()
    data class Staggered(val ticksLeft: Int) : CombatState()
}
enum class SwingDir { OVERHEAD, LEFT, RIGHT, THRUST }
```

### Timing (in ticks @ 20 tps), all stat-modulated

| Phase | Base | Modifier | Notes |
|---|---|---|---|
| Windup | 12t (600ms) | `agility` ↓, weapon class baseline | Direction committed. Feint window = first 6t (combat skill ≥ threshold). |
| Active | 3t (150ms) | weapon class | Hit detection runs each active tick. |
| Recovery | 8t (400ms) | `agility` ↓ | Cannot swing/block; can be staggered. |
| Block parry window | first 5t of `Blocking` | `combat` skill ↑ window size | Tap-RMB landing here = parry. Hold past = passive block. |
| Stagger | 14t (700ms) | strength of staggerer ↑ | Cannot act. |

### Stamina costs

Stamina pool and regen come from sim (`endurance` stat-driven). Plugin reads per tick, applies drains, reports back.

| Action | Cost | Notes |
|---|---|---|
| Swing commit | 8 | Refunded if feinted. |
| Block — passive hold | drain 2/tick | Discourages turtling. |
| Block — perfect (passive match) | 4 | |
| Block — partial (adjacent) | 8 | |
| Block — bad (opposite) | 12 | |
| Parry success | -6 (refund) | Rewards skill. |
| Direction switch mid-windup | 4 | First half of windup only. |
| Sprint | 1/tick | Existing system. |

At **0 stamina**: blocks auto-drop, swings impossible, vulnerable until first regen (~2s).

### Transition invariants

1. **No interrupting your own swing for blocks.** Once in `Windup`/`Active`/`Recovery`, blocks are unavailable. Forces commitment.
2. **Stagger overrides everything.** Drops to `Staggered` regardless of current state. Parry-into-stagger is the high-skill counter.

## 5. Input

### Player input (StoryClient mod)

- **LMB-press** → starts a swing-direction capture window of 4 ticks. Mouse-drag direction (Δyaw + Δpitch over the window) resolves to nearest of:
  - drag UP (large -Δpitch) → OVERHEAD
  - drag LEFT (large -Δyaw) → LEFT
  - drag RIGHT (large +Δyaw) → RIGHT
  - small Δyaw + Δpitch, neutral / forward push → THRUST (default if no drag)
- **RMB-press** → same direction-capture; enters `Blocking(dir)`. Held = sustained block. Released = exits to Idle.
- **RMB-tap landing in attacker's parry window** → parry attempt.
- **Direction switch mid-windup**: re-press LMB and drag in new direction during first-half-of-windup. Costs 4 stamina.
- **Feint**: dedicated keybind (e.g., `F`) during first-half-of-windup. Available only above combat-skill threshold. Cancels into Recovery, refunds swing stamina.

### NPC input

`CombatBrain` per NPC (see §7). When sim's strategic mode is `fight`, brain decides actions; when sim flips to `flee`/`disengage`, brain stops. Lua's `attemptHit(targetCid, dir?)` accepts an optional direction; if omitted, brain picks. If sim wants to force a direction (e.g., personality bias), it passes one.

## 6. Hit Resolution & Damage

### Hit detection at each `Active` tick

- **OVERHEAD / LEFT / RIGHT** → yaw cone. ±45° from facing yaw, reach = weapon-class baseline (sword 3.2, polearm 4.5, dagger 2.2). LEFT cone biased -20° from yaw, RIGHT +20°. All `Combatant` entities inside cone+reach are hit.
- **THRUST** → narrow forward raycast: 0.6-block-wide capsule, reach = weapon-class baseline ×1.3. Single target.
- **One swing → one hit per target.** Multi-tick `Active` doesn't double-tap; first connect wins.

### Outcome resolution (defender state at hit-time)

1. Defender Idle/Windup/Active/Recovery/Staggered → **unblocked**, 100% damage.
2. Blocking(dir) with `parryWindowTicksLeft > 0` AND dir matches → **PARRY**: 0 dmg, attacker → `Staggered`, defender stamina refund.
3. Blocking(dir) past parry window, dir matches → **perfect block**: 0 dmg, defender stamina drain 4.
4. Blocking(dir) past parry window, dir adjacent → **partial block**: damage = `base × (0.30 + (1 - defenderCombatSkill) × 0.20)` (30%–50%). Stamina drain 8.
5. Blocking(dir) past parry window, dir opposite → **bad block**: damage = `base × (0.70 + (1 - defenderCombatSkill) × 0.20)` (70%–90%). Stamina drain 12.

### Direction adjacency table

|  | OVERHEAD | LEFT | RIGHT | THRUST |
|---|---|---|---|---|
| **OVERHEAD** | match | adj | adj | opp |
| **LEFT** | adj | match | opp | adj |
| **RIGHT** | adj | opp | match | adj |
| **THRUST** | opp | adj | adj | match |

### Base damage

```
base_dmg = weapon_dmg × (1 + strength_modifier) × combat_skill_modifier
strength_modifier = (strength_stat - 0.5) × 0.6     // ±30% from strength
combat_skill_modifier = 0.7 + combat_skill × 0.6    // 70%–130%
```

Final damage = `base_dmg × block_outcome_multiplier`. Applied via plugin's damage path (not vanilla `attacker.attack`). Damage skips re-entry into `EntityDamageByEntityEvent` cancellation by setting a per-tick "applying directional damage" flag the listener checks.

### Stagger triggers

- Parry success (always staggers attacker)
- Bad-block while defender's stamina hits 0 (defender staggers)

### Outcome stimuli to sim

- `MeleeHitLanded { attacker, defender, dir, damage, outcome }` (outcome ∈ {Unblocked, Parried, PerfectBlock, PartialBlock, BadBlock})
- `MeleeStaggered { combatant, by }`
- `StaminaBroken { combatant }`

## 7. NPC Combat AI

Lives plugin-side as `CombatBrain` per `NpcCombatant`. Tick-rate decisions every 2-4 ticks. Brain is *active* only while sim's strategic mode is `fight`.

### Decision loop (each brain tick when state = Idle)

```
observe target's CombatState
  → target in Windup            → DEFEND decision
  → target in Active/Recovery   → ATTACK decision
  → target Idle/Blocking        → ATTACK decision (mix-up)
```

### ATTACK decision

- Read target's current `Blocking(dir)` if any.
- **Smart-pick:** `P(attack target's UNGUARDED direction) = 0.4 + 0.5 × combat_skill`.
  - combat 0.0 → 40% smart, 60% random
  - combat 1.0 → 90% smart, 10% random
- If target not blocking, pick weighted by personality bias (sim-supplied: aggressive→overhead, defensive→thrust, balanced=uniform).
- During windup, **feint check** every 2 ticks: `P(feint) = 0.0 + 0.6 × combat_skill × pressure_factor`. `pressure_factor` rises when target is also in windup (mind-game) or has low stamina. Triggers only in first-half-of-windup. Feint → `Recovery`, refund stamina, sim emits `Feinted` stimulus.

### DEFEND decision (target winding up)

- **Block-or-parry:** `P(parry) = 0.1 + 0.7 × combat_skill`.
- **Direction read accuracy** = `combat_skill`. 0.0 = random guess, 1.0 = always correct.
- **Parry timing:** brains schedule `Blocking` entry such that parry window aligns with attacker's `Active` tick. High-skill nails timing; low-skill misses (within parry window tolerance).

### Disengage triggers (brain hands control back to sim)

- stamina < 20% → emit `StaminaLow` stimulus, sim re-evaluates
- HP < 20% → existing `react_to_attacker.lua` OnTick HP-bail handles
- target out of weapon-reach for >40 ticks → reposition (sim navigateTo closes distance, brain passive while moving)

### Range awareness

Brain only fires ATTACK when target within `weapon_reach × 1.1`. Outside, brain passive. Existing `react_to_attacker.lua` keeps issuing `navigateTo(target.pos)` to close the gap.

### NPC-vs-NPC

Brains tick independently with phase offsets (`entity_id mod 4`) so two NPCs never decide same-tick. Prevents simultaneous mutual parries.

### Personality knobs (sim → plugin)

- `aggression` (0..1) — biases ATTACK probability vs DEFEND wait
- `preferred_direction` — overhead/thrust/etc.
- `feint_propensity` (0..2) — multiplier on feint probability

Read once per swing, not per tick.

## 8. Client/Server Packet Flow

### Client → Server

```kotlin
SwingIntentC2S { dir: SwingDir }
BlockIntentC2S { dir: SwingDir, mode: PressOrRelease }
DirectionSwitchC2S { newDir: SwingDir }
FeintC2S { }
```

### Server → Client

```kotlin
CombatStatePushS2C { entityId: Int, state: CombatState, dir: SwingDir?, ticksLeft: Int }
StaminaUpdateS2C { current: Float, max: Float }   // self-only, debounced
HitOutcomeS2C { attackerEntityId: Int, defenderEntityId: Int, outcome: HitOutcome, dir: SwingDir }
StaggerS2C { entityId: Int, ticks: Int }
IntentRejectedS2C { reason: String }              // "out of stamina", "staggered", etc.
```

### Client mod responsibilities

1. **Input capture** — mouse-drag direction at LMB/RMB-press over a 4-tick window.
2. **Local prediction** — first-person swing animation plays immediately; rolls back if server rejects (rare).
3. **HUD** — stamina bar, current commit indicator (color-coded: green=can-feint, yellow=committed), parry-window flash, outcome banner ("PARRIED", "BLOCKED", "STAGGERED" — 0.5s fade).
4. **Remote pose rendering** — applies pose deltas on `CombatStatePushS2C`:
   - Windup OVERHEAD → head pitch -45°
   - Windup LEFT → yaw offset -30°, slight roll
   - Windup RIGHT → yaw offset +30°, slight roll
   - Windup THRUST → head pitch +15°, weapon extended forward
   - Active → 0.15-block forward lunge
   - Recovery → return to neutral over recovery duration
   - Blocking → weapon raised; 2nd-person-readable dir overlay
   - Staggered → bent-over slumped pose
5. **Camera effects** — screen shake on local stagger or parry-receive; 0.3s slow-mo (0.5x) on parry-success. Configurable in `StoryClientConfig`.

### Server authority

- Client packets are intents, not commands. Server validates: stamina, not staggered, state compatibility, direction switch in first-half-of-windup, feint allowed by combat skill.
- Rejected → `IntentRejectedS2C { reason }` for HUD feedback. Client snaps prediction back to Idle.
- Anti-cheat surface: client can try to swing/block/feint; can't fake hits, damage, or stamina.

### Latency budget

50-150ms typical. Windups (600ms) and active frames (150ms) are well above latency floor. Parry timing is tightest — 250ms parry window means a 150ms-ping player has ~100ms effective window. For high-ping players, parry window scales server-side via config option.

## 9. Package Layout

### Story plugin — `src/main/kotlin/com/canefe/story/combat/`

```
combat/
├── Combatant.kt
├── CombatState.kt
├── SwingDir.kt
├── DirectionalCombatService.kt
├── adapter/
│   ├── PlayerCombatant.kt
│   ├── NpcCombatant.kt
│   └── CombatantRegistry.kt
├── resolution/
│   ├── HitDetector.kt
│   ├── DamageResolver.kt
│   └── StaminaService.kt
├── ai/
│   ├── CombatBrain.kt
│   └── DecisionWeights.kt
├── packet/
│   ├── SwingIntentC2S.kt
│   ├── BlockIntentC2S.kt
│   ├── DirectionSwitchC2S.kt
│   ├── FeintC2S.kt
│   ├── CombatStatePushS2C.kt
│   ├── StaminaUpdateS2C.kt
│   ├── HitOutcomeS2C.kt
│   ├── StaggerS2C.kt
│   ├── IntentRejectedS2C.kt
│   └── CombatPacketRegistration.kt
├── stimulus/
│   └── CombatStimulusEmitter.kt
└── listener/
    └── VanillaMeleeListener.kt
```

### Story — touched existing files

- `bridge/IntentExecutor.kt` — `attempt_hit` no longer calls `attacker.attack(target)`; calls `directionalCombatService.queueSwing(combatant, dir)`. Direction passed in via extended `FrontendIntentEvent`.
- `bridge/DomainEvents.kt` — `FrontendIntentEvent` adds optional `swingDirection: SwingDir?`.
- `Story.kt` — wire `DirectionalCombatService`, `CombatantRegistry`, `CombatBrain` lifecycle.

### StoryClient — `src/client/kotlin/com/canefe/storyclient/client/combat/`

```
combat/
├── DirectionInputCapture.kt
├── CombatStateClient.kt
├── prediction/
│   ├── LocalSwingPrediction.kt
│   └── PredictionRollback.kt
├── hud/
│   ├── StaminaBarHud.kt
│   ├── DirectionCommitHud.kt
│   ├── ParryFlashHud.kt
│   └── OutcomeBannerHud.kt
├── pose/
│   ├── EntityPoseRenderer.kt
│   ├── BlockOverlay.kt
│   └── StaggerPose.kt
├── camera/
│   └── CombatCameraEffects.kt
└── packet/                  # mirror of plugin packets
```

### StoryClient — touched existing files

- `packets/StoryPacketManager.kt` — register new combat packets.
- `StoryClientConfig.kt` — add combat config (parry window scale, slow-mo enable, screen-shake intensity).

### story-sim — touched existing files

- `packs/BaseGame/lua/defs/behaviors/react_to_attacker.lua` — `attemptHit` passes a direction (random or personality-biased). Reads new `ctx.me.stamina`.
- `src/plugins/behavior/execution.rs` — extend `attempt_hit` Lua binding to take optional direction; pass through to `FrontendIntent`. Add stamina to `ctx.me` snapshot.
- New stimuli registered: `MeleeParried`, `MeleeStaggered`, `StaminaBroken`.

## 10. Tests

- `combat/HitDetectorTest.kt` — yaw cone math, thrust raycast
- `combat/DamageResolverTest.kt` — adjacency table outcomes (all 16 cells)
- `combat/CombatStateTest.kt` — state transitions: Windup→Active→Recovery, Stagger interrupts, feint refunds, direction switch in/out of window
- `combat/CombatBrainTest.kt` — decision probabilities at low/mid/high combat skill (statistical, ~1000 trials per case)
- Integration: cancel-vanilla-damage flow, sim ↔ plugin direction passthrough, NPC-vs-NPC fight smoke test

## 11. Open Questions / Future Work

- **Multi-target swings on cone hits:** v1 hits *all* in cone. Acceptable for v1; future could add max-targets or damage falloff.
- **Weapon class data source:** weapon damage / reach baselines need a config source. Reuse existing item config or add a new `combat-weapons.yml`. To be settled in implementation plan.
- **Knockback:** vanilla knockback is part of `attacker.attack`. Custom damage path means custom knockback. v1: small fixed knockback per swing dir; future: stat-modulated.
- **Death events:** existing death pipeline already handles `EntityDeathEvent`; directional damage just sets entity HP, vanilla death fires normally. Confirm in implementation.
- **Parry-into-disarm or weapon break:** future flavor; out of v1.
