# Full Pipeline Map — story-sim ↔ story-go ↔ storymc ↔ StoryClient (+ story-mcp, story-bot, story-recognition)

**Date:** 2026-05-25
**Status:** Reference / diagnosis — not a spec. Maps every existing pipeline end-to-end and locates the gaps blocking an autonomous AI-storyteller TTRPG.
**Method:** Read across all 6 repos (file:line citations in body). Six parallel trace agents + direct reads.

---

## TL;DR — what's missing for the end-to-end autonomous vision

The plumbing mostly exists; **coherence, enforcement, and the autonomous "brain" do not.** Concretely:

1. **No autonomous Director.** Nothing decides *what should happen next* without a human. The "AI DM" (`story-bot/bot/workflows/story_dm`) is a Discord `!dm` command that prints a beat-sheet for a person to read. It never ticks, never watches world state, never emits intents.
2. **Narrative tooling is a write-only island.** `set_narrative_directive`, `create_runsheet`, `trigger_step`, `create_world_event` exist in story-mcp, but **no runtime service calls them** — only a human via Claude/Discord. story-go only calls `/internal/index_memory`. (Exception: story-bot reads `get_narrative_directive` to inject into NPC prompts, and activates/deactivates directives via `/api/directives/*` — but those are human-triggered HTTP calls.)
3. **The new (decoupled) architecture is "partially works, flaky."** The sim is a deep cognition engine that has never reliably driven the whole village in-game. Movement is mid-migration (`go_to` vs `navigate_to` both in `IntentExecutor`), spawn identity has a `catch → randomUUID()` fork, and there are three independent "already spawned?" guards across processes.
4. **Monolith NPC behavior not ported.** `ScheduleManager`/`NPCBehaviorManager`/`DutyLoopRunner`/wandering still live in the plugin and drive nothing in the new architecture.
5. **Decision system (CK3-style) has no trigger and no consequences.** `decision/manager.go` is wired plugin↔client↔go but has **no caller** of `Open()` and `finalize()` has a `// Phase 2 TODO` with no side effects.

**Layer order to make it real:** (0) make identity+movement deterministic → (1) world bootstrap (populate sim with the village in lockstep) → (2) behavior parity (port monolith schedules/duties to sim goals) → (3) the Director (authors story as intents, leases NPC capabilities from the sim) → (4) MCP as the Director's read/write working memory → (5) decision-initiation + NPC LLM-tier promotion.

---

## The two responsibility lines that the whole system is organized around

- **OrchestratorIntent (story-go → plugin):** speak, quest, spawn, teleport, kill, revive — LLM/DM-driven, works WITHOUT story-sim. (`story-proto/story/v1/intents.proto`)
- **Frontend Intents (story-sim → plugin):** go_to, flee, attack, lookAt, setTarget, behaviorSet — locomotion/combat/animation, REQUIRE story-sim. Gated by `broadcastToFrontends` on `sim.IsActive()`.

These two flows are structurally separate at the story-go entry point — which is why the DM grab gate and (future) Director lease gate can live cleanly at the NATS entry.

---

## PIPELINE 1 — NPC spawn + identity (the foundation, currently flaky)

**Identity spine:** `characterId` = MongoDB character UUID, threaded everywhere:
- storymc mints it (`CharacterRecord.id`); story-go carries `ev.CharacterID`; story-sim stamps `ExternalId{id}` (`lua_world_api.rs:627` "Identity is universal"); every sim `go_to`/`npc.state` keyed by `ext_id.id`.

**Two spawn origins (the root of flakiness):**
- **Path A (human/plugin):** `/char spawnall` → `stableUuid = UUID.randomUUID()` → `factory.spawn(...)` → `characterRegistry.register(id = stableUuid)`. Mongo id == random uuid, written to both. Consistent.
- **Path B (sim-originated):** story-go on FIRST position (`HandleCharacterPosition`, `internal/sim/handler.go:~800`) sends `spawn_entity` to sim AND `npc.spawn` to plugin. Plugin `executeNpcSpawnIntent` (`IntentExecutor.kt:567`) does `factory.spawn(stableUniqueId = UUID.fromString(characterId) catch → randomUUID())` and **does NOT call `characterRegistry.register`**.

**Defects:**
- `UUID.fromString(characterId) catch → randomUUID()` silently forks identity if characterId isn't a canonical UUID → NPC unroutable forever.
- Path B skips `characterRegistry.register`, so `resolveNPC`'s best lookup misses → falls to O(n) scan / name-match.
- `resolveNPC` (`IntentExecutor.kt:718`) is a 7-deep waterfall ending in **name matching** (`npcRegistry.getByName`). Rename/dup-name/missing-record → wrong NPC or none.
- Three independent "already spawned?" guards: story-go `h.spawned`, sim `ExternalId` dedup, plugin `isSpawned` — no shared truth → double-spawn / miss races.
- `executeNpcStateIntent` runs a 2s position **snapshot** as `npc.navigateTo` → re-triggers MythicMobs pathing.

**Identity model (decision, 2026-05-22):** ExternalId = persistent identity (always set = Mongo id); ExternallyControlled = locomotion-owner only. (memory: project_sim_identity_locomotion_split)

---

## PIPELINE 2 — Movement (mid-migration; the visible flakiness)

- sim behavior decides "walk to X" → `publish_movement_intents_system` (gated `With<ExternallyControlled>`) → `build_ecs_go_to(ext_id.id,...)` mints intentId + PendingIntents → `go_to` envelope on NATS `story-sim.events`.
- story-go `handler.Handle` `case GoTo → broadcastToFrontends` (DROPS if `!sim.IsActive()` or NPC grabbed via GrabRegistry).
- storymc `executeFrontendIntent` → `resolveNPC` → `npc.navigateTo` + `startNavWatcher` (re-issues navigateTo on a cadence because MythicMobs `GoToMechanic` cuts paths short) → on arrival `completeGoTo`, on stall `rejectGoTo(UNREACHABLE)`.
- outcome → `intent.outcome` → story-go → sim (satisfies PendingIntents).

**Defects:** `IntentExecutor.kt` comments still describe `navigate_to` (old vocab) while `activeGoTo`/supersede use `go_to` (new vocab) — half-migrated. The re-issue loop fighting MythicMobs path-cutting = the `acquire_food UNREACHABLE` thrash (memory: project_acquire_food_unreachable_loop). Fix already designed: `docs/superpowers/specs/2026-05-23-intent-vocabulary-redesign-design.md` (proto = SoT, npc.state stripped to pure snapshot, intents carry full meaning).

---

## PIPELINE 3 — Conversation / speech (separate path; LLM via story-go, NOT sim)

- Player chat → `NPCInteractionListener` → `ConversationManager.emitPlayerSpeech` (perception, decoupled from conversation) + `addPlayerMessage` (debounced response).
- `generateResponses` → `selectNextSpeaker` → `intelligence.generateNPCResponse` (BridgeIntelligence → story-go → story-bot `/api/generate`; LocalIntelligence fallback = Claude SDK in Kotlin).
- Response → `speakAsNPC` (single broadcast path) → `NPCMessageService` formats with `<npc_typing>` tag (+ `voice:1`) → client `TypingManager` → `BubbleRenderer`/HUD; `AudioPayload` releases held bubble (3s timeout) + positional audio.
- Conversation lifecycle: start/join/proximity-auto-end(10s)/end-with-memory-summarize. Session-feed + worldInformation on end.

**Sim autonomous speech:** story-sim can emit `npc.speak` directly (`frontend_intent.rs:236`, `expects_outcome=false`). **No `source` field** distinguishes sim-autonomous from LLM-reply at the intent level.

**Gaps:** story-go ConversationState struct (`intelligence/handler.go:45`) and `elections` map are **defined but unused** — conversation state still owned by Kotlin (the plan.md "ConversationManager Migration" is largely not done). `handleGenerateResponse` case exists but is a skeleton.

---

## PIPELINE 4 — Perception (one-way: storymc → go → sim)

- Bukkit events (`PerceptionListener`: combat/death/weather/targeting) + `GazeBroadcaster` (1s, 15° cone) + `PerceptionBroadcaster` (2s sight stimulus w/ FOV/LOS/light + ACTION popups).
- story-go `perception_handler.go`: parse typed Details → `recognition.Resolve` rewrite (epithet/descriptor per perceiver) → fold consecutive dups (30s) → per-char buffer (keep 30, summarize oldest 15) → async `recognition.Reinforce`.
- Stimulus forwarded to sim → `perception_stimulus` handler → `StimulusBuffer` → blackboard → behavior scoring.
- Recognition: story-recognition (Python) confidence tiers 0-4, decay, epithets, descriptors (from chargen via `setDescriptor`). Client `HelixNametagRenderer`.

**Gaps:** Speech & Movement perception variants defined but no Bukkit listener emits them. Perception buffer in story-go is **in-memory only** (lost on restart, `forgetPerception` not persisted, no permission gating on queries). Perception flow one-way: sim never tells storymc who-sees-whom. SpeakGate/autonomous-speech election commented out (`perception_handler.go:~200`).

---

## PIPELINE 5 — Session / quest / memory / decision

**Session (✅ wired, go-owned):** `/session start/end/add/feed` → SessionManager (thin proxy) → DomainEventEmitter → story-go session.Store (Mongo `sessions`). `feed` → DM permission gate → `llm.Narrate` (story-bot `/api/narrate`) → `AppendHistory` → `IntentSessionNarration` back to players. Summary every 3 entries. No session resume after go restart.

**Quest (❌ stubbed, plugin-local):** QuestManager = in-memory + YAML files, **not in Mongo, not in go**. story-go `handleQuestAssign` is a pass-through with `// TODO Persist`. `/quest create <prompt>` calls LOCAL LLM. No autonomous generation/assignment/completion. No reward execution.

**Memory (✅ mostly, go-owned):** NPC learns → `emitMemoryObserved` → story-go `persistMemoryWithConsent` (DM gate) → Mongo `memories` (sessionId stamped if active session) → async `MemoryIndexer.Index` → story-mcp `/internal/index_memory` (embed + Qdrant). **Active-session gate** = no active session → no memory mutations. Read-back via story-mcp `search_memories` / agent `/api/research`. Fire-and-forget indexing can desync Mongo↔Qdrant.

**Decision (⚠️ wired but inert):** story-go `decision/manager.go` ↔ `DecisionRelay` (plugin) ↔ `DecisionPacketReceiver`/`DecisionHud`/`CinematicCamera` (client). Leader/vote modes, timeout, tally all implemented. **BUT: no caller of `Open()` anywhere, and `finalize()` (manager.go:~216) is a `// Phase 2 TODO` — outcomes resolve but trigger NO consequences (no memory, no perception, no NPC action, no broadcast of result).** Spec lists "NPC autonomous decisions (no player)" as OUT OF SCOPE.

---

## PIPELINE 6 — story-mcp ("storydb") — the brain's would-be working memory

**Two surfaces, one Mongo+Qdrant:** MCP server (`story_mcp/server.py`, ~80 @tools) + HTTP server (`story_narrative/`).

**Tool groups:** characters, lore, factions/eras, relationships (dyads + events + promotion candidates), affiliations (+ reputation, derived live), memories (search/get/index/reindex), players/PCs, sessions, world_events, rumors, and in-game actions (`npc_speak`/`npc_move`/`npc_emote` → POST story-go `/api/speak` `/api/workflow`, fire-and-forget — NOT stubs).

**Narrative tooling (the storyteller substrate):**
- `NarrativeDirective` {characterId, goal, emotional_state, tensions{}, beats[], scene_context, status draft|reviewed|active|inactive, auto_generated}. `.to_prompt()` injects into NPC system prompt. set/get/list/activate/deactivate.
- `Runsheet` {sessionId, title, steps[], status}; `RunsheetStep` {index, description, actions[], status}; `RunsheetAction` {type: npc.speak|npc.move|npc.emote|delay|parallel|intent.quest.assign|session.narration, data{}}. `trigger_step` → `.to_workflow_step()` → POST story-go `/api/workflow`.
- `get_dm_briefing` assembles NPC docs + last-10 memories + recent sessions (reads legacy `npcs` collection).

**Who calls these at runtime:**
- story-bot: `get_narrative_directive` (per NPC response, injected into prompt), `/api/directives/generate|activate|deactivate` (human-triggered).
- story-go: **only** `/internal/index_memory`.
- **Runsheets, world_events, rumors create, promotion candidates, reputation-driven reactions: NO runtime caller. Human-via-Claude only.**

---

## PIPELINE 7 — player-facing client systems (mostly built)

- **Action wheel** (`ActionWheelHud`): Inspect / Perception Log (DM) / Speak As (DM) / Follow / Follow Char / Recognize / Puppet (DM) / Grab-Release (DM). Nearby NPCs via `NearbyNPCBroadcaster` (2s, `story:nearby_npcs`). Actions dispatch as chat commands or direct payloads.
- **Directional combat** (✅ core): client `DirectionInputCapture` (4-tick or live-aim) → swing/block/dirswitch/feint payloads → `DirectionalCombatService` state machine (Windup/Active/Recovery/Blocking/Staggered) → hit resolution → `CombatStatePush`/`HitOutcome` → camera FX. NPC `CombatBrain`. Gaps: weapon class hardcoded SWORD, knockback not impl, stats placeholdered (`DEFAULT_STAT=0.5`, not wired to sim).
- **Squad** (orders ✅, formations partial): `SquadOrderPayload` (move/hold/follow/engage/idle/set-formation) → `SquadOrderListener`; `SquadListBroadcaster` (2s). Formation positioning driven by sim, not fully traced.
- **Puppet/DM control** (✅ core): `PuppetCommandPayload` opcodes 0x01-0x07 (move/add/remove/toggle/clear/speak-at/dm-control). Grab (0x07) → `DMControlToggleEvent` → story-go `GrabRegistry` denylist gate at NATS entry (drops sim go_to + npc.speak for grabbed NPC; everything else forwards so NPC stays alive). (memory: project_dm_override_gate)
- **Sim viz:** `ItemHologramRenderer` (arc item giver→receiver, `story:item_transfer`), `PerceptionPopupRenderer` (PopupType.ACTION sticky labels, `story:npc_perception`).
- **Packet layer:** legacy `<npc_typing>` plugin-message system (`StoryPacketManager`) being replaced by Fabric Payload API (combat already migrated).

---

## The authority/coexistence problem (the hard design question)

The sim is an autonomous author of NPC bodies. The Director would be a second autonomous author of story. They will collide on the same NPC. The DM grab gate already solved this for ONE human (GrabRegistry = per-NPC denylist at NATS entry). The Director generalizes that to a **per-NPC, per-capability lease** (locomotion / speech / combat), sim authoritative by default; Director takes a scoped, time-bounded lease for the duration of a beat, then releases. Subtlety: on release the sim resumes its own committed goal (e.g. walks back to the well) — a beat that needs an NPC to STAY must write that as a sim-side fact, not rely on holding the lease forever.

---

## Cross-cutting risks found

- **Silent wire drift:** Kotlin `ignoreUnknownKeys` + Go opaque `map[string]interface{}` forwarding → renamed fields vanish without compile error. (intent-vocab-redesign spec fixes via proto SoT.)
- **New outbound SerializableStoryEvent must be registered in `WebSocketTransport.serializeEvent`** or payload drops to `{raw:...}`. (memory: project_websocket_serialize_registration)
- **In-memory state lost on restart:** perception buffers, decision pending map, no session resume.
- **Fire-and-forget everywhere:** memory indexing, npc_speak/move/emote, runsheet triggers — no confirmation the game acted.

---

## Source citations (entry points)

- Spawn: `story-go/internal/sim/handler.go` HandleCharacterPosition/handleNpcState; `story/.../bridge/IntentExecutor.kt` executeNpcSpawnIntent/resolveNPC; `story-sim/src/plugins/stream_handlers/spawn_entity.rs`, `lua_world_api.rs:618`.
- Movement: `story-sim/src/plugins/behavior/movement.rs`, `frontend_intent.rs`; `story-go/internal/sim/handler.go` Handle; `IntentExecutor.kt` startNavWatcher/activeGoTo.
- Conversation: `story/.../conversation/ConversationManager.kt`, `npc/service/NPCMessageService.kt`; `story-go/internal/intelligence/handler.go`; `story-bot/api/main.py`.
- Perception: `story/.../bridge/Perception{Listener,Service}.kt`, `perception/{Gaze,Perception}Broadcaster.kt`; `story-go/internal/intelligence/perception_handler.go`; `story-recognition/`.
- Session/quest/memory/decision: `story-go/internal/{session,domain,decision}/`; `story/.../session/SessionManager.kt`, `quest/QuestManager.kt`, `bridge/DecisionRelay.kt`.
- storydb: `story-mcp/src/story_mcp/server.py`, `story_narrative/`, `models.py`.
- Sim cognition: `story-sim/src/plugins/behavior/` (planner, goal_selection, scoring, execution, joint/, trade/); `docs/B2-*.md`.
