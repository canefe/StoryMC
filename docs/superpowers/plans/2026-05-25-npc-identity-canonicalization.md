# NPC Identity Canonicalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a sim-backed NPC resolve deterministically by its Mongo `characterId` everywhere, killing the `randomUUID()` identity fork and silent name-matching.

**Architecture:** `characterId` (Mongo UUID) becomes the single identity for sim NPCs. `MythicMobStoryNPC` exposes the `CHARACTER_ID` PDC already written at spawn; `CharacterRegistry.getByStoryNPC` resolves via that PDC first (`byId` is already keyed by characterId); `executeNpcSpawnIntent` hard-fails on a non-UUID or unregistered characterId instead of minting a random key; name-matching is demoted to a logged-warning last resort.

**Tech Stack:** Kotlin, Paper/Bukkit, MythicMobs, JUnit5 + MockBukkit + mockk. Build: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` / `./gradlew test`.

**Spec:** `docs/superpowers/specs/2026-05-25-npc-identity-canonicalization-design.md`

**Test setup note (per CLAUDE.md):** when a test boots the full plugin, set `plugin.configService.npcReactionsEnabled = false` and `plugin.configService.autoModeEnabledByDefault = false`. The unit tests below construct `CharacterRegistry` / stubs directly and do NOT need full plugin boot.

---

## Task 1: `StoryNPC.characterId` accessor + MythicMob PDC read

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/api/StoryNPC.kt` (add interface property with default)
- Modify: `src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobStoryNPC.kt` (override to read PDC)
- Test: `src/test/kotlin/com/canefe/story/npc/mythicmobs/MythicMobStoryNPCCharacterIdTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.canefe.story.npc.mythicmobs

import com.canefe.story.api.StoryNPC
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MythicMobStoryNPCCharacterIdTest {
    // A minimal StoryNPC that does NOT override characterId, to prove the
    // interface default is null (Citizens/stub NPCs have no Mongo-id PDC).
    private class NoIdNpc : StoryNPC {
        override val name = "x"; override val id = -1
        override val uniqueId = java.util.UUID.randomUUID()
        override val entity = null; override val isSpawned = false
        override val location = null
        override fun navigateTo(location: org.bukkit.Location) {}
        override fun navigateTo(location: org.bukkit.Location, speedModifier: Float, range: Float, distanceMargin: Double) {}
        override fun navigateTo(entity: org.bukkit.entity.Entity) {}
        override fun navigateTo(entity: org.bukkit.entity.Entity, speedModifier: Float, range: Float, distanceMargin: Double) {}
        override fun cancelNavigation() {}
        override val isNavigating = false
        override fun spawn(location: org.bukkit.Location) = false
        override fun despawn() = false
        override fun teleport(location: org.bukkit.Location) {}
        override fun clone(): StoryNPC = this
        override fun attack(target: org.bukkit.entity.Player) {}
        override fun stopAttacking(target: org.bukkit.entity.Player) {}
        override fun follow(target: org.bukkit.entity.Player) {}
        override fun stopFollowing() {}
        override val isFollowing = false
        override fun lookAt(target: org.bukkit.entity.Entity) {}
        override fun rotateTo(yaw: Float, pitch: Float) {}
        override fun sit(location: org.bukkit.Location?) {}
        override fun stand() {}
        override val isSitting = false
        override val skinTexture: String? = null
        override val skinSignature: String? = null
        override fun setSkin(name: String, signature: String, texture: String) {}
        override fun <T : Any?> unwrap(type: Class<T>): T? = null
    }

    @Test
    fun `interface default characterId is null`() {
        val npc: StoryNPC = NoIdNpc()
        assertNull(npc.characterId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.npc.mythicmobs.MythicMobStoryNPCCharacterIdTest"`
Expected: FAIL to compile — `characterId` is not a member of `StoryNPC`.

- [ ] **Step 3: Add the interface default property**

In `src/main/kotlin/com/canefe/story/api/StoryNPC.kt`, after the `clientFacingUuid` property (around line 33), add:

```kotlin
    /**
     * The persistent Mongo character id for this NPC, if known. Source of truth
     * for routing sim intents (go_to, npc.speak) to the right NPC. Null for NPCs
     * with no Mongo backing (e.g. decorative Citizens NPCs).
     */
    val characterId: String? get() = null
```

- [ ] **Step 4: Override in MythicMobStoryNPC to read the PDC**

In `src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobStoryNPC.kt`, after the `clientFacingUuid` override (line 56), add:

```kotlin
    override val characterId: String?
        get() = backingEntity.persistentDataContainer
            .get(MythicMobNPCKeys.CHARACTER_ID, MythicMobNPCKeys.STRING)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.npc.mythicmobs.MythicMobStoryNPCCharacterIdTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/canefe/story/api/StoryNPC.kt src/main/kotlin/com/canefe/story/npc/mythicmobs/MythicMobStoryNPC.kt src/test/kotlin/com/canefe/story/npc/mythicmobs/MythicMobStoryNPCCharacterIdTest.kt
git commit -m "feat(npc): StoryNPC.characterId accessor reading CHARACTER_ID PDC"
```

---

## Task 2: `CharacterRegistry.getByStoryNPC` resolves by characterId first, warns on name fallback

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/api/character/CharacterRegistry.kt:73-77`
- Test: `src/test/kotlin/com/canefe/story/api/character/GetByStoryNpcTest.kt`

The `getByStoryNPC` resolver currently has no characterId path; `byId` is already keyed by characterId, so we read `npc.characterId` first. The name path stays but logs a warning so silent mis-routing becomes visible.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.canefe.story.api.character

import com.canefe.story.api.StoryNPC
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals

class GetByStoryNpcTest {
    private fun registry(records: List<CharacterRecord>): CharacterRegistry {
        val charStorage = mockk<com.canefe.story.storage.mongo.MongoCharacterStorage>(relaxed = true)
        val feStorage = mockk<com.canefe.story.storage.mongo.MongoFrontendConfigStorage>(relaxed = true)
        every { charStorage.findAll() } returns records
        every { feStorage.findAllByFrontend(any()) } returns emptyList()
        val reg = CharacterRegistry(charStorage, feStorage, Logger.getLogger("test"))
        reg.loadAll()
        return reg
    }

    private fun npcWithCharacterId(name: String, charId: String?): StoryNPC {
        val npc = mockk<StoryNPC>(relaxed = true)
        every { npc.name } returns name
        every { npc.uniqueId } returns UUID.randomUUID()
        every { npc.id } returns -1
        every { npc.characterId } returns charId
        return npc
    }

    @Test
    fun `resolves by characterId PDC, not by name`() {
        val rec = CharacterRecord(id = "char-abc", name = "Tobin")
        val reg = registry(listOf(rec))
        // NPC reports a DIFFERENT name than the record but the right characterId.
        val npc = npcWithCharacterId(name = "WrongName", charId = "char-abc")
        assertEquals(rec, reg.getByStoryNPC(npc))
    }

    @Test
    fun `falls back to name when characterId is null`() {
        val rec = CharacterRecord(id = "char-xyz", name = "Maja")
        val reg = registry(listOf(rec))
        val npc = npcWithCharacterId(name = "Maja", charId = null)
        assertEquals(rec, reg.getByStoryNPC(npc))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.api.character.GetByStoryNpcTest"`
Expected: FAIL — `resolves by characterId` returns null (no characterId path yet), resolves by name to the wrong record only if names matched (they don't), so assertion fails.

- [ ] **Step 3: Add the characterId path + warning to getByStoryNPC**

Replace `getByStoryNPC` (lines 73-77) with:

```kotlin
    fun getByStoryNPC(npc: StoryNPC): CharacterRecord? =
        npc.characterId?.let { byId[it] }
            ?: byCitizensUuid[npc.uniqueId]?.let { byId[it] }
            ?: byCitizensNpcId[npc.id]?.let { byId[it] }
            ?: byId[npc.uniqueId.toString()]
            ?: byNameLower[npc.name.lowercase()]?.let { byId[it] }?.also {
                logger.warning(
                    "[CharacterRegistry] resolved NPC '${npc.name}' by NAME fallback " +
                        "— identity index miss, investigate (characterId=${npc.characterId})",
                )
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.api.character.GetByStoryNpcTest"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/canefe/story/api/character/CharacterRegistry.kt src/test/kotlin/com/canefe/story/api/character/GetByStoryNpcTest.kt
git commit -m "feat(registry): getByStoryNPC resolves by characterId PDC first; name fallback warns"
```

---

## Task 3: `executeNpcSpawnIntent` hard-fails on non-UUID / unregistered characterId

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt:568-611`
- Test: `src/test/kotlin/com/canefe/story/bridge/NpcSpawnIntentRefusalTest.kt`

Remove the `catch { UUID.randomUUID() }` fork. Refuse to spawn when `characterId` isn't a canonical UUID, or when no `CharacterRecord` exists for it (per the spec's hard-fail policy). Refusal = log + return, factory never called.

> **Implementation note:** `executeNpcSpawnIntent` is in object/class `IntentExecutor` and takes `plugin: Story`. The test verifies the *decision* (refuse vs proceed) without booting MythicMobs. Extract the validation into a pure, testable helper `npcSpawnRefusalReason(characterId, hasRecord): String?` (null = proceed) and call it from `executeNpcSpawnIntent`. This keeps the spawn path itself untested-by-unit (it needs MythicBukkit) but makes the *policy* fully unit-tested.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.canefe.story.bridge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcSpawnIntentRefusalTest {
    @Test
    fun `refuses non-uuid characterId`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(characterId = "not-a-uuid", hasRecord = true)
        assertEquals("characterId is not a canonical UUID", reason)
    }

    @Test
    fun `refuses valid uuid with no character record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "11111111-1111-1111-1111-111111111111",
            hasRecord = false,
        )
        assertEquals("no CharacterRecord for characterId", reason)
    }

    @Test
    fun `allows valid uuid with a record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "11111111-1111-1111-1111-111111111111",
            hasRecord = true,
        )
        assertNull(reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.NpcSpawnIntentRefusalTest"`
Expected: FAIL to compile — `npcSpawnRefusalReason` does not exist.

- [ ] **Step 3: Add the pure helper**

In `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`, add (near the other helpers, e.g. above `executeNpcSpawnIntent`):

```kotlin
    /**
     * Returns a refusal reason if a sim spawn for [characterId] must be rejected,
     * or null if it may proceed. Policy: characterId MUST be a canonical UUID and
     * MUST have a CharacterRecord — otherwise we refuse rather than mint a random
     * registry key (which produces an unroutable ghost NPC). See spec
     * 2026-05-25-npc-identity-canonicalization.
     */
    fun npcSpawnRefusalReason(characterId: String, hasRecord: Boolean): String? {
        val isUuid = try { java.util.UUID.fromString(characterId); true } catch (_: Exception) { false }
        return when {
            !isUuid -> "characterId is not a canonical UUID"
            !hasRecord -> "no CharacterRecord for characterId"
            else -> null
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.NpcSpawnIntentRefusalTest"`
Expected: PASS (all three).

- [ ] **Step 5: Wire the helper into executeNpcSpawnIntent and remove the fork**

Replace the body of `executeNpcSpawnIntent` (lines 568-611) so the refusal check runs first and the `randomUUID()` fallback is gone:

```kotlin
    fun executeNpcSpawnIntent(plugin: Story, intent: NpcSpawnIntent) {
        if (!plugin.isNpcRegistryReady) return

        // Identity gate: refuse rather than mint a random key (ghost NPC).
        val hasRecord = try {
            plugin.characterRegistry.getById(intent.characterId) != null
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
        npcSpawnRefusalReason(intent.characterId, hasRecord)?.let { reason ->
            plugin.logger.warning("[NpcSpawn] refusing spawn of '${intent.name}' — $reason (characterId='${intent.characterId}')")
            return
        }

        // Never spawn a stand-in for a player who is currently online
        val onlinePlayer = Bukkit.getOnlinePlayers().firstOrNull { it.characterId == intent.characterId }
        if (onlinePlayer != null) {
            plugin.logger.info("[NpcSpawn] Skipping spawn of '${intent.name}' — player ${onlinePlayer.name} is online")
            return
        }

        // Already spawned — only skip if the entity is actually alive in the world
        val existing = resolveNPC(plugin, intent.characterId)
        if (existing?.isSpawned == true) return

        val factory = plugin.mythicMobNpcFactoryOrNull ?: run {
            plugin.logger.warning("[NpcSpawn] MythicMob factory not available, cannot spawn '${intent.name}'")
            return
        }

        val targetWorld = if (intent.world.isNotBlank()) Bukkit.getWorld(intent.world) else null
        val world = targetWorld ?: Bukkit.getWorlds().firstOrNull() ?: return
        val loc = Location(world, intent.x, intent.y, intent.z)

        // Only spawn if a player is nearby — prevents mass spawning with no players online
        val nearPlayer = Bukkit.getOnlinePlayers().any { p ->
            p.world == world &&
                p.location.distanceSquared(loc) <= SPAWN_RADIUS_SQ
        }
        if (!nearPlayer) {
            plugin.logger.info("[NpcSpawn] Skipping spawn of '${intent.name}' — no player within range")
            return
        }

        plugin.logger.info("[NpcSpawn] Spawning '${intent.name}' (${intent.characterId}) at $loc")
        // characterId is guaranteed a canonical UUID here (refusal gate above).
        factory.spawn(
            mobTemplate = intent.mobTemplate,
            location = loc,
            displayName = intent.name,
            stableUniqueId = java.util.UUID.fromString(intent.characterId),
            characterId = intent.characterId,
        )
    }
```

- [ ] **Step 6: Run the focused tests + compile to verify nothing else broke**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin && ./gradlew test --tests "com.canefe.story.bridge.NpcSpawnIntentRefusalTest"`
Expected: compile OK, tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt src/test/kotlin/com/canefe/story/bridge/NpcSpawnIntentRefusalTest.kt
git commit -m "feat(spawn): hard-fail non-UUID/unregistered characterId, remove randomUUID fork"
```

---

## Task 4: `resolveNPC` — make name fallbacks warned, not silent

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt:718` (resolveNPC name/Citizens-name last resorts)
- Test: covered indirectly by Task 2/3; add one direct guard test if a seam exists (see note).

`getByStoryNPC` (Task 2) now resolves canonically, so `resolveNPC`'s primary path (`getById` → `npcRegistry`) is reliable. The two `firstOrNull { it.name == ... }` Citizens-name branches and the `getByName(characterId)` registry branch are the remaining silent paths. Add a warning log to each so a name-based resolution is visible in logs.

> **Note:** `resolveNPC` is `private` and needs `plugin`/MythicBukkit; a full unit test would require plugin boot. The behavior change here is purely additive logging on existing fallback branches — no logic change — so it is verified by compile + the existing `GoToExecTest`/`IntentExecutorOutcomeTest` continuing to pass. Do NOT add a brittle boot test just for log lines.

- [ ] **Step 1: Add warning logs to the name-based fallback branches**

In `resolveNPC` (`IntentExecutor.kt`), at the `// Name match in Citizens` branch (around line 754) and the `// Last resort: treat characterId as a name` branch (around line 770) and the final `// Legacy fallback: treat characterId as a name in Citizens` branch, wrap each returned NPC with a warning. Concretely, change:

```kotlin
            // Name match in Citizens
            val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == record.name }
            if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
```
to:
```kotlin
            // Name match in Citizens (warned: identity index miss)
            val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == record.name }
            if (citizenNpc != null) {
                plugin.logger.warning("[resolveNPC] '${record.name}' resolved by Citizens NAME fallback — investigate (characterId=$characterId)")
                return CitizensStoryNPC(citizenNpc)
            }
```

And change the unified-registry last resort:
```kotlin
            // Last resort: treat characterId as a name in the unified registry
            plugin.npcRegistry.getByName(characterId)?.let { return it }
```
to:
```kotlin
            // Last resort: treat characterId as a name in the unified registry (warned)
            plugin.npcRegistry.getByName(characterId)?.let {
                plugin.logger.warning("[resolveNPC] characterId '$characterId' resolved by registry NAME fallback — investigate")
                return it
            }
```

And the legacy Citizens-name fallback:
```kotlin
        val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == characterId }
        if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
```
to:
```kotlin
        val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == characterId }
        if (citizenNpc != null) {
            plugin.logger.warning("[resolveNPC] characterId '$characterId' resolved by legacy Citizens NAME fallback — investigate")
            return CitizensStoryNPC(citizenNpc)
        }
```

- [ ] **Step 2: Compile and run the existing IntentExecutor tests**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin && ./gradlew test --tests "com.canefe.story.bridge.*"`
Expected: compile OK, all bridge tests PASS (no behavior change, only added logging).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt
git commit -m "feat(resolveNPC): warn on name-based fallback resolution (no longer silent)"
```

---

## Task 5: Full build + test sweep

**Files:** none (verification task).

- [ ] **Step 1: Full compile**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full test suite**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests green. If any pre-existing test resolved an NPC by name and now trips the warning, that's fine (warning, not failure); only a hard FAIL needs investigation.

- [ ] **Step 3: Final commit (if any docs/cleanup pending)**

```bash
git add -A
git commit -m "chore: NPC identity canonicalization — full build green" --allow-empty
```

---

## Self-review notes

- **Spec coverage:** Component 1 → Task 3; Component 2 (MythicMobStoryNPC characterId) → Task 1; Component 3 (getByStoryNPC) → Task 2; Component 4 (resolveNPC) → Task 4; Component 5 (Path-B record guarantee) → Task 3 (the `hasRecord` gate in `executeNpcSpawnIntent` is exactly the Path-B refusal). story-go optional debug log (Component 5, "optional, low-cost") is intentionally omitted as YAGNI for this pass.
- **Rehydrate concern (spec risk note):** resolved — `characterId` is read lazily from PDC at resolve time, so rehydrate (which does not restore CHARACTER_ID into any cache) needs no change; the PDC persists on the entity. No task required.
- **Type consistency:** `npcSpawnRefusalReason(characterId: String, hasRecord: Boolean): String?` used identically in Task 3 Steps 3 and 5. `StoryNPC.characterId: String?` defined Task 1, consumed Task 2. `MythicMobNPCKeys.CHARACTER_ID` / `.STRING` are existing keys.
- **No `byCharacterId` index added** — `byId` is already keyed by characterId; getByStoryNPC routes NPC→characterId via PDC then hits `byId`. Matches spec scope guard.
