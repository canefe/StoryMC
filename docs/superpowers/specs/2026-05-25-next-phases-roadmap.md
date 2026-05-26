# Next Phases Roadmap — toward the autonomous AI-storyteller TTRPG

**Date:** 2026-05-25
**Status:** Living roadmap. Sequencing + scope for the layers above NPC identity.
**Companion docs:**
- `2026-05-25-full-pipeline-map.md` — full 6-repo trace of what exists today.
- `2026-05-25-npc-identity-canonicalization-design.md` / plan — Layer 0 (DONE).
- `2026-05-23-intent-vocabulary-redesign-design.md` — the movement-determinism half of Layer 0 (NOT yet built).

---

## The goal (one line)

A Minecraft world that runs and narrates *itself* — a mix of sim-AI-only NPCs and sim-AI + LLM NPCs, with an AI storyteller (Director) that reads world context from the story-mcp "storydb" and drives scenes, so the DM doesn't manually puppet everything.

## The dependency order (why this sequence)

Each layer is only worth building once the one below it is reliable. You can't trust a storyteller built on a movement layer that silently stalls, and you can't tell a perception bug from a stats bug if NPC identity is nondeterministic.

```
Layer 0  Identity + deterministic movement        ← the bones
Layer 1  World bootstrap (village lives in sim)    ← the body
Layer 2  Behavior parity (NPCs do what they did)   ← the muscles
Layer 3  Director (autonomous storyteller)          ← the brain
Layer 4  storydb as the Director's memory           ← the long-term memory
```

Plus two cross-cutting tracks the user has flagged that slot into Layers 1–2:
- **Perception → behavior** (make what an NPC senses actually change what it does)
- **Stats → behavior** (make stats like sight/consciousness/combat actually drive outcomes)

---

## Layer 0 — Foundation (the bones)

**0a. NPC identity canonicalization — ✅ DONE.**
characterId (Mongo UUID) is now the single identity across Mongo → entity PDC → registry → sim. Killed the `randomUUID()` ghost-NPC fork; name-resolution now warns instead of silently mis-routing. Spec + plan + 4 feature commits landed.

**0b. Movement determinism — ✅ DONE (code), ⬜ in-game verification pending.**
`2026-05-23-intent-vocabulary-redesign-design.md` is **implemented** (status flipped 2026-05-25). All three phases landed: proto is the single source of truth across all four repos (story-proto submodule → story-go `make proto`, story-sim `prost`+`build.rs`, StoryMC `com.google.protobuf` gradle plugin); the gRPC `StoryBridge` service was removed (Phase 2); and `go_to` now unifies both old movement lanes — the ECS `npc.move` (now minting an intentId + PendingIntents) and the Lua `navigate_to` — with a full outcome round-trip and SUPERSEDED handling (Phase 3). The leftover `navigate_to` references in StoryMC are only the Bukkit-side re-issue *mechanism*, which correctly stays Kotlin-side per the Hybrid line.
Verified: clean `compileKotlin`/`compileTestKotlin` + green `GoToWireTest`/`ItemTransferWireTest`/`AuthoringIntentWireTest`. See the design spec's "Implementation status" block for the per-phase commit trail.
**Player-visible payoff:** NPCs walk where they're told, reliably, without stalling or snapping back.
**Done when:** a handful of NPCs receive `go_to`, walk there, and report arrival/stall with no silent drops, repeatably. ← **The wire/plumbing is proven; the only remaining check is this in-game walk on a live server (sim + StoryMC running), which can't be done headless.**

---

## Layer 1 — World bootstrap (the body)

**The gap:** the sim is a deep brain thinking about a mostly-empty room. There is no path that puts the *real village* into the sim and keeps it in lockstep. Today only a partial/flaky set of NPCs ever make it in.

**1a. Populate the sim with the real village.**
At session start (or on demand), register the storymc/Mongo NPCs into the sim's ECS — each stamped with its canonical characterId as ExternalId (now reliable thanks to Layer 0). Tear down cleanly on end. One owner of "who is spawned where," instead of three independent guards across processes.

**1b. Live lockstep loop sim ↔ storymc.**
storymc tells the sim where players/NPCs actually are; the sim emits movement/behavior/speech; storymc executes on the real entities; outcomes flow back. This is the spine that *replaces* the old plugin tick loops as the thing driving NPCs.

**Player-visible payoff:** walk into town and the whole village is present and moving, not just a couple of test NPCs.

---

## Layer 2 — Behavior parity + the two cross-cutting tracks (the muscles)

**The gap:** the monolith's autonomous-NPC stack (ScheduleManager / NPCBehaviorManager / DutyLoopRunner / wandering) still lives in the plugin and drives *nothing* in the new architecture. A bootstrapped NPC currently has no reason to do its old daily routine.

**2a. Behavior parity.**
Re-express the monolith's schedules/duties/wandering as sim goals/behaviors (the B2 GOAL→BEHAVIOR→ACTION engine that already exists). An NPC in the new world should at least do what it did before — go to work, wander, sleep, do its job — autonomously, no human.

**2b. Perception → behavior (cross-cutting track the user named).**
The wire exists (storymc → go `perception_stimulus` → sim StimulusBuffer → scoring) but the loop isn't trustworthy:
- Fix the affordance source-entity lookup TODO (`perception_stimulus.rs`) so perceived things are attributed correctly.
- Add stimulus decay (stimuli currently never fade, so behavior weighting goes stale).
- Verify end-to-end that an NPC visibly reacts to what it perceives.
**Player-visible payoff:** an NPC that *sees* a fight flees or reacts; one that *hears* you responds — sensing actually changes doing.

**2c. Stats → behavior (cross-cutting track the user named).**
Make stats real drivers, not flavor:
- Wire combat stats off the placeholder `DEFAULT_STAT=0.5` to real per-character values.
- Make the sim's sight check read `consciousness` (storymc already scales by it; the sim ignores it — they disagree).
- Confirm stat weights actually change which action an NPC picks.
**Player-visible payoff:** a guard in the dark genuinely doesn't see you; a strong NPC reliably hits harder than a weak one — consistently, because numbers decide it.

**Player-visible payoff (whole layer):** a living village that was busy before you arrived, where senses and stats produce believable, consistent behavior.

---

## Layer 3 — The Director (the brain)

**The gap:** nothing decides *what should happen next* without a human. The current "AI DM" is a Discord command that prints a beat-sheet for a person to read; it never ticks, never watches the world, never acts in-game. The CK3-style decision system is fully wired but has no trigger and produces no consequences.

**3a. Director service in story-go.**
A new component that ticks, reads context, decides the next *beat*, and compiles that beat into the existing OrchestratorIntents (speak / move / spawn / quest) — flowing through plumbing that already works.

**3b. Authority model: the sim is default-boss, the Director leases.**
The sim authors NPC *bodies*; the Director authors *story*. They must not fight over the same NPC. Reuse the pattern already shipped for DM grab (the GrabRegistry gate) and generalize it: the Director takes a **scoped, time-bounded lease** on a capability (locomotion / speech / combat) for the duration of a beat, then releases. Sim keeps simulating the NPC's inner life the whole time; on release the NPC resumes its own goals. A beat that needs an NPC to *stay* writes that as a sim-side fact, not by holding a lease forever.

**3c. Trigger model: runsheet spine first, reactive fill later.**
Start runsheet-driven only (authored beats advance on conditions). Add the reactive ambient layer (idle chatter, rumors, minor events between beats) as a second pass — so the first build has one hard problem, not two.

**3d. Director-initiated decisions.**
Wire the existing decision system so the Director can *raise* a choice, resolve it, and branch the story on the outcome (and make `finalize()` actually apply consequences instead of being a no-op).

**Player-visible payoff:** stuff happens *to* you. A stranger approaches with news; a rivalry boils over; a choice with stakes appears — none of it manually staged.

---

## Layer 4 — storydb as the Director's working memory (long-term memory)

**The gap:** the narrative tools in story-mcp (narrative directives, runsheets, world events, relationships, reputation, rumors) exist but no runtime service calls them — only a human via Claude. story-go only writes memories; it never *reads* context for autonomous decisions.

**4a. Director reads context before a beat.**
Pull `get_dm_briefing`, character state, relationships, active directives, rumors — so beats are informed by the campaign so far.

**4b. Director writes consequences after a beat.**
Record world events, relationship changes, memories — so the story has continuity across sessions and the next beat reacts to the last.

**Player-visible payoff:** the world remembers. Last session's choices shape this session; NPCs' relationships and reputations actually evolve.

---

## Suggested immediate next step

**Update 2026-05-25:** Layer 0 is now complete in code — 0a (identity) and 0b
(movement determinism) both landed. The foundation is done barring one manual
check: the **in-game `go_to` walk** (an NPC physically arriving/stalling on a live
server). Do that verification when a server is up; it needs no new code, only
observation.

With the foundation done, the next *build* is **Layer 1 — World bootstrap**: get
the real Mongo/storymc village living in the sim in lockstep. This is the
precondition that makes the two cross-cutting tracks demonstrable:

- **Perception → behavior (2b)** and **Stats → behavior (2c)** — the concrete
  items the user named; each is a focused, player-visible win, but both only
  become *fully* trustworthy/demonstrable once the village actually lives in the
  sim (Layer 1).

Recommendation: verify 0b in-game (no code), then build **1 (world bootstrap)**,
then **2b/2c**.

---

## How each layer reads to a player (plain-language summary)

| Layer | What the player notices |
|---|---|
| 0 | ✅ built (code) — NPCs stop freezing/glitching; they walk where they should, reliably. *In-game walk verification still pending.* |
| 1 | The whole village is present and moving, not just a couple of NPCs. |
| 2 | NPCs do their routines; senses and stats produce believable, consistent behavior. |
| 3 | Things happen *to* you without a human staging them. |
| 4 | The world remembers — past choices shape the present. |
