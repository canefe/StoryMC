# NPC Identity Canonicalization — Design Spec

**Date:** 2026-05-25
**Status:** Approved design, ready for implementation planning
**Repos touched:** Story / StoryMC (Kotlin) primarily; story-go (Go) for the Path-B refusal guarantee.
**Layer:** This is "Layer 0" from `2026-05-25-full-pipeline-map.md` — the foundation everything else (perception→behavior reliability, stats→behavior, the Director) depends on.

---

## Problem

A sim-backed NPC currently has its Mongo identity (`characterId`) and its in-world registry key bridged **by coincidence**, not by guarantee. The result is nondeterministic NPC resolution — the "some move, flaky/incoherent" symptom.

Concretely, in the current code:

1. **`executeNpcSpawnIntent` forks identity silently.** (`bridge/IntentExecutor.kt:608`)
   ```kotlin
   stableUniqueId = try { UUID.fromString(intent.characterId) } catch (_) { UUID.randomUUID() }
   ```
   If `characterId` isn't a canonical UUID, the NPC spawns under a **brand-new random key nobody else knows** → every future `go_to`/`npc.state` for it fails to resolve → the NPC stands still forever.

2. **`getByStoryNPC` has NO MythicMobs path.** (`api/character/CharacterRegistry.kt:73-77`)
   ```kotlin
   fun getByStoryNPC(npc: StoryNPC): CharacterRecord? =
       byCitizensUuid[npc.uniqueId]?.let { byId[it] }      // Citizens only
           ?: byCitizensNpcId[npc.id]?.let { byId[it] }    // Citizens only
           ?: byId[npc.uniqueId.toString()]                // works ONLY if key == Mongo id (the hack's purpose)
           ?: byNameLower[npc.name.lowercase()]?.let { byId[it] }  // silent name fallback
   ```
   For a MythicMob NPC, paths 1-2 never match. Path 3 matches **only because** of the `UUID.fromString` hack — so the `randomUUID()` fallback doesn't just fork identity, it knocks resolution down to **name matching**, which mis-routes on rename/duplicate-name.

3. **`factory.spawn` already stores the real `characterId` in a PDC key that nothing reads.** (`npc/mythicmobs/MythicMobNPCFactory.kt:85-89`, key `MythicMobNPCKeys.CHARACTER_ID`) The canonical data exists; the resolver just ignores it.

4. **`resolveNPC` is a 7-deep waterfall** (`bridge/IntentExecutor.kt:718`) ending in name-matching, papering over the above.

The outbound side is already clean: `PositionBroadcaster.tick()` (`npc/PositionBroadcaster.kt:84`) only emits NPCs that have a `getCharacterIdForNPC(npc)` and sends the canonical Mongo id. So identity is sound *on the way out* and broken *on the way back in*.

---

## Governing Principle

**`characterId` (Mongo character UUID) is the single identity for a sim-backed NPC across every layer** — Mongo `byId`, the in-world registry key, the `CHARACTER_ID` PDC, the sim `ExternalId`, and the wire. No separate registry-key id-space for sim NPCs; no bridging index; no `UUID.fromString` coincidence; no silent name-routing.

This matches the existing sim-side decision (2026-05-22, `project_sim_identity_locomotion_split`): ExternalId = persistent identity = Mongo char id, always set.

Citizens NPCs with no Mongo backing keep a random registry key and remain **non-sim-routable** (unchanged — they already are; this spec does not touch them).

**Bad-id policy (decided):** A sim spawn whose `characterId` is not a canonical UUID with a resolvable `CharacterRecord` is **hard-failed and logged, never spawned under a random key.** A visibly-absent NPC is the correct signal that surfaces the upstream bug; a ghost NPC hides it.

---

## Components

### Component 1 — `executeNpcSpawnIntent`: remove the fork
**File:** `bridge/IntentExecutor.kt:568-611`

- Parse `intent.characterId` as UUID up front. On failure: `logger.warning("[NpcSpawn] refusing spawn — characterId not a canonical UUID: '${intent.characterId}'")` and **return** (no spawn).
- Delete the `catch { UUID.randomUUID() }` branch entirely.
- Spawn with `stableUniqueId = UUID.fromString(characterId)` (now guaranteed valid) and `characterId = intent.characterId` (PDC, as today).
- Keep the existing guards (online-player skip, already-spawned skip via `resolveNPC`, near-player skip).

### Component 2 — `MythicMobStoryNPC`: expose stored characterId
**File:** `npc/mythicmobs/MythicMobStoryNPC.kt`

- Add `val characterId: String?` that reads the `MythicMobNPCKeys.CHARACTER_ID` PDC from the backing entity (already written at spawn by the factory). Returns null if absent or entity gone.
- Survives respawn/rehydrate because it lives in PDC (consistent with `STABLE_UUID` rehydrate handling).
- (`StoryNPC` interface gains an optional `characterId: String? get() = null` default so `getByStoryNPC` can read it polymorphically; `CitizensStoryNPC` keeps the default null.)

### Component 3 — `CharacterRegistry.getByStoryNPC`: canonical resolution
**File:** `api/character/CharacterRegistry.kt:73-77`

New resolution order:
1. **`npc.characterId?.let { byId[it] }`** — primary, reads the PDC characterId (Component 2).
2. `byCitizensUuid[npc.uniqueId]?.let { byId[it] }` — Citizens.
3. `byCitizensNpcId[npc.id]?.let { byId[it] }` — Citizens.
4. `byId[npc.uniqueId.toString()]` — back-compat for any NPC whose key still equals a Mongo id.
5. **Name fallback, but logged:** `byNameLower[npc.name.lowercase()]?.let { byId[it] }?.also { logger.warning("[CharacterRegistry] resolved NPC '${npc.name}' by NAME fallback — identity index miss, investigate") }`

The name path stops being a silent normal path; a warning means "something upstream didn't set identity correctly."

### Component 4 — `resolveNPC`: collapse the waterfall
**File:** `bridge/IntentExecutor.kt:718`

- With Component 3 resolving canonically, the primary path is `characterRegistry.getById(characterId)` → `npcRegistry` lookup by that NPC. Keep the existing fallbacks but treat the name/Citizens-name last resorts as **warned**, not silent. The function shrinks; the coincidental key==id path is no longer load-bearing.
- `requestReconcileForMissing` on a true miss stays.

### Component 5 — Path-B record guarantee
**Files:** `story-go/internal/sim/handler.go:HandleCharacterPosition`, `bridge/IntentExecutor.kt:executeNpcSpawnIntent`

- The `HandleCharacterPosition`-triggered `npc.spawn` (Path B) must yield a resolvable NPC. Per the hard-fail policy: when `executeNpcSpawnIntent` receives a characterId with **no `CharacterRecord`** (`characterRegistry.getById` null), it logs and refuses (same path as Component 1's bad-UUID refusal). It does NOT invent a record.
- story-go side: no behavior change required for correctness, but add a debug log on first-position spawn noting whether the id is a canonical UUID, to make upstream diagnosis easier. (Optional, low-cost.)

---

## What this explicitly does NOT do (scope guard)

- Does not change Citizens NPC identity or behavior.
- Does not touch the sim ExternalId model (already correct).
- Does not fix the `go_to` vs `navigate_to` movement migration (separate spec: `2026-05-23-intent-vocabulary-redesign-design.md`).
- Does not add a `byCharacterId` HashMap index — `byId` already keys by characterId; we just route NPC→characterId via the PDC instead of adding a parallel index.

---

## Testing (TDD)

Test setup per `CLAUDE.md`:
```kotlin
plugin.configService.npcReactionsEnabled = false
plugin.configService.autoModeEnabledByDefault = false
```

1. **`getByStoryNPC` resolves a MythicMob NPC by PDC characterId** — given an NPC whose `characterId` property returns a registered id, returns the right `CharacterRecord` without touching the name path.
2. **`getByStoryNPC` name fallback logs a warning** — an NPC resolvable only by name resolves but emits the warning (assert via log capture or a seam).
3. **`executeNpcSpawnIntent` refuses a non-UUID characterId** — no spawn, warning logged, factory never called.
4. **`executeNpcSpawnIntent` refuses a characterId with no CharacterRecord** — no spawn, warning logged.
5. **`resolveNPC` returns the correct NPC by characterId** after a canonical spawn, and does not silently resolve a different same-named NPC.
6. **Round-trip:** spawn via `executeNpcSpawnIntent` → `resolveNPC(characterId)` returns that exact NPC (key == characterId).

---

## Risks / notes

- **Existing in-world NPCs** spawned under the old random-key path won't have a matching `byId` key but DO have the `CHARACTER_ID` PDC (factory always wrote it) — Component 2+3 will now resolve them correctly via PDC, so this fix *retroactively heals* already-spawned ghost NPCs on next resolve. Good.
- **Rehydrate path** (`mythicMobNpcFactory.rehydrateAllLoaded`) must preserve the `CHARACTER_ID` PDC → confirm it re-reads PDC and doesn't re-mint. (Verify during implementation.)
- New outbound events: none. No `WebSocketTransport.serializeEvent` registration needed (`project_websocket_serialize_registration`).
