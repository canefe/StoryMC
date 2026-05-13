# Directional Combat — Implementation Plan

**Spec:** `2026-05-10-directional-combat-design.md`
**Date:** 2026-05-10
**Repos:** Story (Paper plugin), story-sim (Rust), StoryClient (Fabric mod)

Each phase is self-contained: enter a fresh chat with the spec + this plan and a phase number. Build green before moving on.

---

## Phase 0 — Discovery (already complete)

Findings (anchor points; reference during implementation):

### Story plugin
- `bridge/IntentExecutor.kt:441-448` — current `attempt_hit` (vanilla `attacker.attack`)
- `bridge/IntentExecutor.kt:341-349` — `set_target` for comparison
- `bridge/DomainEvents.kt:304-321` — `FrontendIntentEvent` data class (needs `swingDirection`)
- `Story.kt:377-517` — `initializeManagers()`, where new services are wired
- `Story.kt:588-663` — `initializeEventBus()`, where intent handlers register
- `event/PlayerEventListener.kt:48-50` — existing `EntityDamageByEntityEvent` listener (no cancellation)
- `perception/CombatPerceptionListener.kt` — secondary damage listener
- `api/StoryNPC.kt:1-172` — interface (no facingYaw/stamina/takeDamage yet)
- `npc/mythicmobs/MythicMobStoryNPC.kt:33-200+` — adapter; uses MythicMobs `ActiveMob`
- `PacketEventsPacketListener.kt:1-26` — packet infrastructure (PacketEvents lib)
- `src/test/kotlin/com/canefe/story/StoryTest.kt:1-72` — MockBukkit + mockk pattern
- `src/main/resources/config.yml` — no `combat:` section yet

### story-sim
- `src/plugins/behavior/execution.rs:699-705` — `attempt_hit` Lua binding
- `src/plugins/behavior/execution.rs:580-591` — `ctx.me` snapshot (HookContext at lines 30-36)
- `src/plugins/frontend_intent.rs:22-29` — `IntentKind` enum (`AttemptHit { target_char_id }`)
- `src/plugins/frontend_intent.rs:88-95` — JSON serialization for `AttemptHit`
- `src/plugins/frontend_intent.rs:47-119` — NATS publish via `BridgePubSubOut`
- `src/components/stimulus.rs:4-28` — `StimulusType` enum (no Melee* variants)
- `src/components/stats.rs:8-11` — `Stats { values: HashMap<String, f32> }`
- `packs/BaseGame/lua/defs/stats/stamina.lua` — stamina stat already defined (default 100, min 0, max 100)
- `packs/BaseGame/lua/defs/behaviors/react_to_attacker.lua:84,94,126` — three `attemptHit` call sites

### StoryClient
- MC 1.21.1, Fabric loader 0.19.2, Fabric API 0.116.7, Kotlin via fabric-language-kotlin 1.13.2+kotlin.2.1.20
- `client/packets/StoryPacketManager.kt:47-68` — handler registration pattern (S2C only today)
- `client/packets/StoryPacket.kt:7-13` — base interface
- `client/packets/NPCAudioPacket.kt:32-72` — example data-class packet
- `client/StoryClientConfig.kt:6-15` — Gson-backed config object
- `client/hud/SquadListHud.kt`, `client/render/BubbleRenderer.kt` — render examples
- `NPCMessageParserClient.kt:160-180` — keybind registration via `KeyBindingHelper`
- `gradle.properties` — version source of truth

### Allowed APIs (cite when needed)
- Bukkit `Player.location.yaw`, `getEyeLocation()`, `damage(double)`, `getHealth()`/`setHealth()`
- MythicMobs `ActiveMob.setTarget(AbstractEntity)` (5.11.2)
- Fabric API 1.21.1: `ClientPlayNetworking`, `ServerPlayNetworking`, `KeyBindingHelper`, `HudRenderCallback`, `WorldRenderEvents`, `ClientTickEvents`
- Mixin against `LivingEntityRenderer` for pose deltas (no existing infra — first mixin)

### Anti-patterns / known gaps
- Don't call `attacker.attack(target)` from new combat path (re-enters vanilla cancellation)
- Don't `replace_all` for any FQN→short refactor (per global feedback)
- No inline FQNs in Kotlin — always import (per global feedback)
- StoryClient has no C2S packet code today — Phase 5 establishes the pattern
- StoryClient has no entity-pose mixin today — Phase 5 introduces it

---

## Phase 1 — Story plugin combat skeleton

**Goal:** Land the package + types + service stubs without changing behavior. Plugin still uses vanilla damage; no listener cancellation yet.

**Files (new) under `src/main/kotlin/com/canefe/story/combat/`:**
- `SwingDir.kt` — enum (OVERHEAD, LEFT, RIGHT, THRUST)
- `CombatState.kt` — sealed class per spec §4
- `Combatant.kt` — interface: `entityId`, `facingYaw()`, `eyeLocation()`, `stamina()`, `takeDamage(amount, attacker)`, `onPoseChange(state)`, `currentState()`, `transitionTo(state)`
- `adapter/CombatantRegistry.kt` — bidirectional `EntityId ↔ Combatant`
- `adapter/PlayerCombatant.kt` — wraps Bukkit `Player`
- `adapter/NpcCombatant.kt` — wraps `StoryNPC`
- `DirectionalCombatService.kt` — empty methods: `queueSwing(combatant, dir)`, `queueBlock(combatant, dir, mode)`, `queueDirectionSwitch`, `queueFeint`, `tick()` (BukkitRunnable @ 20 tps)

**Files (touched):**
- `Story.kt` — in `initializeManagers()` (around line 497), construct `CombatantRegistry`, `DirectionalCombatService`. Expose as `lateinit var` properties beside other services. Schedule `directionalCombatService.tick()` repeating task.
- `config.yml` — add `combat:` section with placeholder keys (`enabled: false`, `parry-window-base-ticks: 5`, `windup-base-ticks: 12`).

**Verification:**
- `./gradlew compileKotlin` passes.
- `./gradlew test` passes (existing tests unaffected — `combat.enabled: false`).
- New `combat/` package imports cleanly, no usage from existing files.

**Anti-pattern guards:** No vanilla-damage cancellation yet. No behavior change. Ship as a no-op skeleton.

---

## Phase 2 — Hit detection + damage + stamina + vanilla cancel

**Goal:** Server-authoritative directional damage path works for player→player and player→NPC under `combat.enabled: true`.

**Files (new):**
- `combat/resolution/HitDetector.kt` — `detect(attacker: Combatant, dir: SwingDir, weapon: WeaponClass): List<Combatant>`
  - Yaw cone: ±45° base, LEFT biased -20°, RIGHT +20°, reach per weapon class
  - THRUST: 0.6-block capsule raycast, reach × 1.3
- `combat/resolution/DamageResolver.kt`
  - `resolve(attackerState, defenderState, dir, attackerStats, defenderStats): Outcome` per §6 adjacency table
  - `Outcome` sealed: Unblocked, Parry, PerfectBlock, PartialBlock, BadBlock
  - `damageFor(outcome, weaponDmg, strength, combatSkill)` per §6 formula
- `combat/resolution/StaminaService.kt` — per-tick drain/refund per §4 cost table; reads max from sim (Phase 3 wires sim path; for now hardcode 100)
- `combat/resolution/WeaponClass.kt` — enum + reach/dmg table (sword 3.2/4, polearm 4.5/6, dagger 2.2/2.5)
- `combat/listener/VanillaMeleeListener.kt` — `@EventHandler(priority = HIGHEST)` on `EntityDamageByEntityEvent`. Cancels iff (a) `combat.enabled` AND (b) `cause == ENTITY_ATTACK` AND (c) NOT carrying a thread-local "applying directional damage" flag. Then routes to `DirectionalCombatService.onVanillaSwingAttempt(attacker)`.
- `combat/stimulus/CombatStimulusEmitter.kt` — placeholder; emits to existing event bus (Phase 3 connects to sim)

**Files (touched):**
- `DirectionalCombatService.kt` — implement `tick()`: advance state machines, call `HitDetector` at each `Active` tick, run `DamageResolver`, set thread-local flag, call `defender.takeDamage(...)`, clear flag.
- `Story.kt` — register `VanillaMeleeListener`.

**Tests (new) under `src/test/kotlin/com/canefe/story/combat/`:**
- `HitDetectorTest.kt` — yaw cone math (8 directions × 3 weapons), thrust raycast hit/miss
- `DamageResolverTest.kt` — all 16 adjacency cells; formula at low/mid/high stats
- `CombatStateTest.kt` — Windup→Active→Recovery; Stagger interrupts; feint refund; mid-windup direction switch in/out of window
- `StaminaServiceTest.kt` — drain table; 0-stamina auto-drop; regen tick
- `VanillaMeleeListenerTest.kt` — uses MockBukkit; flag set → not cancelled; flag clear → cancelled

**Verification:**
- `./gradlew test` green.
- Manual smoke: enable `combat.enabled: true`; vanilla LMB on player produces no damage and no swing animation server-side (state machine routes it).

**Anti-pattern guards:** Don't apply damage via `Player.damage(double, Entity)` if it re-fires `EntityDamageByEntityEvent` — use plain `setHealth()` minus damage, or set the thread-local flag *before* calling `damage()`. Verify in test.

---

## Phase 3 — sim ↔ plugin direction passthrough

**Goal:** `react_to_attacker.lua` can pass a swing direction; plugin routes it; stamina visible in `ctx.me`.

**story-sim changes:**
- `src/components/stimulus.rs:4-28` — add `MeleeHitLanded`, `MeleeParried`, `MeleeStaggered`, `StaminaBroken` to `StimulusType`.
- `src/plugins/frontend_intent.rs:22-29` — extend `IntentKind::AttemptHit { target_char_id, direction: Option<SwingDir> }`. Add new enum `SwingDir { Overhead, Left, Right, Thrust }` (serde-tagged camelCase strings).
- `src/plugins/frontend_intent.rs:88-95` — serialize `swingDirection` in JSON when present.
- `src/plugins/behavior/execution.rs:699-705` — `attempt_hit` Lua binding accepts optional direction string ("overhead"/"left"/"right"/"thrust"); maps to `SwingDir`.
- `src/plugins/behavior/execution.rs:30-36` — `HookContext` adds `actor_stamina: f32`.
- `src/plugins/behavior/execution.rs:580-591` — `ctx.me` snapshot exposes `getStamina()` returning `hook_ctx.actor_stamina`.
- `src/plugins/behavior/execution.rs` — wherever `HookContext` is constructed, read `Stats.values["stamina"]` and set `actor_stamina` (default 100).
- `packs/BaseGame/lua/defs/behaviors/react_to_attacker.lua:84,94,126` — pass a direction (random pick of 4 for v1; personality bias is Phase 4 NPC AI work plugin-side).

**Story plugin changes:**
- `bridge/DomainEvents.kt:304-321` — `FrontendIntentEvent` adds `val swingDirection: String? = null` (string, parsed in IntentExecutor).
- `bridge/IntentExecutor.kt:441-448` — `attempt_hit` no longer calls `attacker.attack(target)`. Resolves attacker to `Combatant` via `CombatantRegistry`, parses `intent.swingDirection` to `SwingDir` (default THRUST if null), calls `directionalCombatService.queueSwing(combatant, dir)`. Keep target validation; `queueSwing` ignores target — hit detection runs on cone.
- New stimulus emit path: `CombatStimulusEmitter` writes to event bus → existing bridge serialization sends to story-go → eventually to sim. Confirm wire format with one round-trip log.

**Verification:**
- `cargo build -p story-sim` green.
- `./gradlew compileKotlin` green.
- Run sim with logfile + Monitor (per `feedback_sim_debug_workflow.md`); confirm `attempt_hit` emits with `swingDirection` populated.
- Plugin log shows `IntentExecutor` parses direction and calls `queueSwing`.

**Anti-pattern guards:** Don't add a Rust `SwingDir` whose discriminants don't match Kotlin enum names (case-sensitive serde). Match the spec's UPPER_SNAKE Kotlin names but serialize as camelCase strings; document the mapping in one comment in `frontend_intent.rs`.

---

## Phase 4 — NPC CombatBrain

**Goal:** NPC fights with directional smart-picks, parry attempts, feints. Plugin-side; sim still owns strategic mode.

**Files (new):**
- `combat/ai/CombatBrain.kt` — per `NpcCombatant`. `tick(now: Int)`. Phase offset = `entityId mod 4`. Reads target's `CombatState` from `CombatantRegistry`.
- `combat/ai/DecisionWeights.kt` — pure functions: `pAttackUnguarded(combatSkill)`, `pParry(combatSkill)`, `pFeint(combatSkill, pressure)`, direction-read accuracy.
- `combat/ai/Personality.kt` — sim-supplied `aggression`, `preferredDirection`, `feintPropensity`. Read once per swing.

**Files (touched):**
- `DirectionalCombatService.kt` — owns `Map<EntityId, CombatBrain>`; tick brains alongside state machines.
- `adapter/NpcCombatant.kt` — wire `Personality` from existing per-NPC stats payload.
- `Story.kt` — no change beyond confirming brain map lives in service.

**Tests:**
- `CombatBrainTest.kt` — statistical (1000 trials per case): low (0.1), mid (0.5), high (0.9) combat skill. Assert observed P(smart-pick), P(parry), P(feint) within ±5% of formula.

**Verification:**
- Two MythicMobs NPCs spawned facing each other under `combat.enabled: true` exchange directional attacks for ≥10 swings without crash. Phase-offset prevents same-tick mutual parries.

**Anti-pattern guards:** Brains run only when sim's strategic mode is `fight`. If `react_to_attacker.lua` flips to `flee`, brain emits no decisions. Don't poll sim every tick — read mode from existing perception cache.

---

## Phase 5 — StoryClient packets + input + HUD + pose

**Goal:** Player can swing/block/parry/feint with mouse-drag direction. Remote entities visibly telegraph.

**Phase 5a — packets (foundation):**
- `client/combat/packet/` — 9 data-class packets per spec §8 mirroring Story-side definitions.
- New file `combat/packet/CombatC2SSender.kt` — establishes the C2S pattern (first in StoryClient): wraps `ClientPlayNetworking.send(...)` with combat channel ids.
- `client/packets/StoryPacketManager.kt:47-50` — register 5 S2C handlers.
- Plugin side mirror under `combat/packet/` with `CombatPacketRegistration.kt` using `ServerPlayNetworking` (or PacketEvents wrapper).

**Phase 5b — input:**
- `client/combat/DirectionInputCapture.kt` — register LMB/RMB intercept (mixin on `MinecraftClient.handleInputEvents` or use Fabric mouse callback). On press, open 4-tick capture window; on close, resolve mouse-delta to `SwingDir`; send `SwingIntentC2S` / `BlockIntentC2S`.
- Feint key: register via `KeyBindingHelper` (default `F`, category `category.story.combat`). Send `FeintC2S` on edge-trigger.
- Direction switch: re-press LMB during windup with new drag → `DirectionSwitchC2S`.

**Phase 5c — HUD:**
- `client/combat/hud/StaminaBarHud.kt` — bottom-center bar; updates from `StaminaUpdateS2C`.
- `client/combat/hud/DirectionCommitHud.kt` — small icon: green=can-feint, yellow=committed.
- `client/combat/hud/ParryFlashHud.kt` — flash on `Blocking` parry-window-active.
- `client/combat/hud/OutcomeBannerHud.kt` — "PARRIED"/"BLOCKED"/"STAGGERED" 0.5s fade on `HitOutcomeS2C`.
- Register all four via `HudRenderCallback.EVENT.register` in `NPCMessageParserClient` (or new `CombatClientInit`).

**Phase 5d — pose & camera:**
- `client/combat/pose/EntityPoseRenderer.kt` — first mixin against `LivingEntityRenderer.render`. Apply per-state pose deltas per spec §8 client mod responsibilities. State source: `CombatStateClient` cache fed by `CombatStatePushS2C`.
- `client/combat/camera/CombatCameraEffects.kt` — screen shake + 0.3s slow-mo (0.5×) via `MinecraftClient.timer`. Gated by `StoryClientConfig.slowMoEnable`.

**Phase 5e — config:**
- `client/StoryClientConfig.kt:6-15` — add `parryWindowScale: Double = 1.0`, `slowMoEnable: Boolean = true`, `screenShakeIntensity: Float = 1.0f`. Update `StoryConfigData` inner class.

**Verification:**
- Build StoryClient: `./gradlew build`.
- In-game: LMB-drag-up swings overhead; RMB-tap-during-attacker-windup parries; stamina bar drains; outcome banner displays.
- Two players: A swings overhead, B sees pose delta on A; B parries; A sees stagger pose.

**Anti-pattern guards:** Don't bypass server authority — client predictions snap back on `IntentRejectedS2C`. Don't fake hits client-side. Mixin minimal: pose-render only, no input-side mixin if Fabric callback suffices.

---

## Phase 6 — Verification

- `./gradlew test` (Story) all green.
- `cargo test -p story-sim` green.
- StoryClient `./gradlew build` green.
- Integration smokes:
  1. Vanilla LMB cancelled → custom swing fires → damage applied without re-entering listener.
  2. Sim-side `react_to_attacker.lua` issues `attemptHit("...", "left")` → plugin queues LEFT swing → cone hits → outcome stimulus returns to sim → log shows `MeleeHitLanded` registered.
  3. Two NPCs (MythicMobs) under `combat.enabled: true` complete a 30-second fight; one dies via existing `EntityDeathEvent`.
  4. Player vs NPC: parry succeeds → NPC enters `Staggered` → free hit lands.
- Grep for anti-patterns:
  - `rg "attacker\.attack\(" Story/src/main/kotlin/com/canefe/story/combat` → no matches expected.
  - `rg "import .*\\..*\\.SwingDir" Story/src StoryClient/src` → only intended imports.
- Tag commit: `feat(combat): directional combat phase 6 complete`.

---

## Cross-cutting reminders

- Every phase commits as standalone with conventional commits (`feat(combat): …`).
- Don't add Co-Authored-By lines (per global feedback).
- For any FQN inlining you're tempted to do — add an import instead (per global feedback).
- Each phase's verification step is mandatory before moving to next.
