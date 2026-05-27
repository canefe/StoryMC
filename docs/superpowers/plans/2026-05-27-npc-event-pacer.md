# NPC Event Pacer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single global pacing queue on the StoryClient that bundles per-NPC visual events (dialogue + voice + emote + action-label) within a 200ms window and releases one bundle per second to its renderers, replacing the existing per-NPC `pendingVoiceDialogues` map.

**Architecture:** New `NpcEventPacer` singleton owns two structures: `openBundles: ConcurrentHashMap<String, Bundle>` keyed by NPC id (a bundle accumulates events for 200ms before sealing; voice-pending extends to 3s) and `readyQueue: ConcurrentLinkedDeque<Bundle>` (sealed bundles paced at 1 per second). The pacer is invoked at the four existing payload-receiver call sites (audio, emote, perception ACTION, dialogue) and runs its `tick()` from the existing `ClientTickEvents.END_CLIENT_TICK` loop. Replay calls the same renderers as today (`BubbleRenderer.startBubble`, `EmoteRenderer.onEmote`, `playAudio`, `PerceptionPopupRenderer.onPerception`) — no renderer changes.

**Tech Stack:** Kotlin, Fabric 1.21 client, Minecraft client APIs

**Spec:** `docs/superpowers/specs/2026-05-27-npc-event-pacer-design.md`

---

## Critical Working-Tree Warning

`StoryClient` has many unstaged in-flight changes when this plan starts:

```
 M src/client/kotlin/com/canefe/storyclient/client/BubbleRenderer.kt
 M src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt
 M src/client/kotlin/com/canefe/storyclient/client/perception/NpcPerceptionPayload.kt
 M src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt
 M src/client/kotlin/com/canefe/storyclient/client/recognition/HelixNametagRenderer.kt
```

These belong to the user's other in-progress work (perception-debug + helix HUD). Every commit in this plan **must** use explicit per-file `git add` — NEVER `git add -A`, `git add .`, or any wildcard. Two previous tasks in the emote plan had to be soft-reset because in-flight changes leaked into emote commits. Don't repeat that mistake.

For `NPCMessageParserClient.kt` specifically: when you Edit it, you'll see the working-tree version which contains a `println("[DBG perception-recv]...")` debug line and a 4-argument `PerceptionPopupRenderer.onPerception(..., payload.entityId)` call. **Do not touch those** — they are not your concern. Edit ONLY the four sites the plan names (audio receiver body, emote receiver body, perception receiver body, and the dialogue-receiving path via TypingManager).

If the in-flight working-tree state makes a clean Edit difficult, stop and report BLOCKED rather than guessing.

---

## File Structure

**New:**
- `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt` — the pacer singleton (~180 LOC: data classes + state + 5 public functions + replay).

**Modify:**
- `StoryClient/src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`
  - Remove the `pendingVoiceDialogues` field, `onVoiceReceived` function, and the voice-timeout block inside `tick()`.
  - Change `parseAndDisplayNpcMessage` so it hands the chunk to `NpcEventPacer.onDialogueChunk` instead of buffering or rendering directly.
  - Expose `findNpcEntityId` as `internal` so the pacer can use it at replay time.
  - Make `displayNpcMessage` `internal` and rename to `renderNpcMessage` so the pacer can call it at replay time.
  - Have the existing `tick()` also call `NpcEventPacer.tick()`.
- `StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`
  - **Audio receiver** (around line 76–95): stop calling `TypingManager.onVoiceReceived` and `playAudio` directly. Hand the bytes + uuid to `NpcEventPacer.onVoiceAudio`. Expose `playAudio` as `internal` so the pacer can call it at replay time.
  - **Emote receiver** (around line 188–191): route through `NpcEventPacer.onEmote` with the entityId-derived key.
  - **Perception receiver** (around line 169–185): for `PopupType.ACTION` only, route through `NpcEventPacer.onActionLabel`; all other PopupType values pass through unchanged to `PerceptionPopupRenderer.onPerception`.

---

## Task 1: Create `NpcEventPacer` skeleton with public API + state (no behavior yet)

This task is "make it exist and compile." No behavior; subsequent tasks add the lifecycle methods. Splitting like this keeps each task small and the public API stable before any wiring changes.

**Files:**
- Create: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.canefe.storyclient.client.pacing

import com.canefe.storyclient.client.perception.PopupType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Single global pacing queue for all NPC-driven visual events on the client.
 *
 * Events for the same NPC arriving within a 200ms window are merged into a
 * [Bundle] that pops atomically — preserving dialogue+voice sync and keeping
 * one NPC's reaction visually coherent. Bundles drip out of [readyQueue] at
 * one per second regardless of how many NPCs are talking at once. The
 * 3-second voice-wait semantic from the old `TypingManager.pendingVoiceDialogues`
 * is preserved as a bundle-level override on [Bundle.sealAtMs].
 *
 * Bundle key: the NPC's uuid-string when available, falling back to
 * `"entity:<entityId>"` for emote-only events whose payload carries no uuid.
 * Emote-only bundles never need to merge with dialogue (dialogue always has
 * a uuid), so the two key spaces don't collide.
 *
 * See `docs/superpowers/specs/2026-05-27-npc-event-pacer-design.md`.
 */
object NpcEventPacer {

    internal const val BUNDLE_WINDOW_MS = 200L
    internal const val BUNDLE_MAX_OPEN_MS = 1000L
    internal const val VOICE_WAIT_MS = 3000L
    internal const val POP_INTERVAL_MS = 1000L
    internal const val QUEUE_DEPTH_COLLAPSE_THRESHOLD = 8

    internal data class DialogueChunk(
        val text: String,
        val color: String?,
        val isNew: Boolean,
    )

    internal data class Bundle(
        val npcKey: String,
        val npcUuid: String?,          // null for emote-only entity-keyed bundles
        val openedAtMs: Long,
        var sealAtMs: Long,
        val dialogue: MutableList<DialogueChunk> = mutableListOf(),
        var voiceAudio: ByteArray? = null,
        var voicePending: Boolean = false,
        val emotes: MutableList<String> = mutableListOf(),
        var actionLabel: String? = null,
        var emoteEntityId: Int = -1,
    )

    internal val openBundles = ConcurrentHashMap<String, Bundle>()
    internal val readyQueue = ConcurrentLinkedDeque<Bundle>()
    internal var lastPopMs = 0L

    // ── Public API (no-op until Tasks 2+ implement the methods) ────────────

    fun onDialogueChunk(npcId: String, text: String, color: String?, voicePending: Boolean) {
        // Implemented in Task 3.
    }

    fun onVoiceAudio(npcId: String, audioBytes: ByteArray) {
        // Implemented in Task 4.
    }

    fun onEmote(entityId: Int, emoteId: String) {
        // Implemented in Task 5.
    }

    fun onActionLabel(npcId: String, label: String) {
        // Implemented in Task 6.
    }

    fun tick() {
        // Implemented in Task 2.
    }

    /** Test-only: drive [tick] with an injected clock. */
    internal fun tickForTest(nowMs: Long) {
        // Implemented in Task 2.
    }

    /** Test-only: reset all internal state. */
    internal fun resetForTest() {
        openBundles.clear()
        readyQueue.clear()
        lastPopMs = 0L
    }
}
```

- [ ] **Step 2: Compile to confirm**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git status -sb | head -3
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt
git commit -m "feat(pacer): NpcEventPacer skeleton — state + public API (no behavior yet)"
```

---

## Task 2: Implement `tick()` — seal expired bundles + pop one bundle per second

This task adds the heartbeat. Without any `onXxx` method implemented yet, `openBundles` is always empty, so this is effectively dead code — but it establishes the pop pacing in isolation so Task 3+ can rely on it.

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Replace the empty `tick`/`tickForTest` stubs**

Replace these two empty function bodies:

```kotlin
    fun tick() {
        // Implemented in Task 2.
    }

    /** Test-only: drive [tick] with an injected clock. */
    internal fun tickForTest(nowMs: Long) {
        // Implemented in Task 2.
    }
```

with:

```kotlin
    fun tick() {
        tickAt(System.currentTimeMillis())
    }

    /** Test-only: drive [tick] with an injected clock. */
    internal fun tickForTest(nowMs: Long) {
        tickAt(nowMs)
    }

    private fun tickAt(nowMs: Long) {
        // 1. Seal any open bundles whose sealAtMs has passed.
        val toSeal = openBundles.entries.toList()
            .filter { (_, bundle) -> nowMs >= bundle.sealAtMs }
            .map { it.key }
        for (key in toSeal) {
            val sealed = openBundles.remove(key) ?: continue
            // Dedupe emotes at seal time (e.g. LAUGH+LAUGH+LAUGH → LAUGH).
            val deduped = sealed.emotes.distinct()
            sealed.emotes.clear()
            sealed.emotes.addAll(deduped)
            readyQueue.addLast(sealed)
        }

        // 2. Pop at most one bundle per POP_INTERVAL_MS.
        if (nowMs - lastPopMs >= POP_INTERVAL_MS) {
            val bundle = readyQueue.pollFirst()
            if (bundle != null) {
                lastPopMs = nowMs
                replay(bundle)
            }
        }
    }

    private fun replay(bundle: Bundle) {
        // Implemented in Task 7. For now, no-op so Task 2 lands without
        // depending on the renderer wiring.
    }
```

- [ ] **Step 2: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt
git commit -m "feat(pacer): tick() — seal expired bundles + pop one bundle per second"
```

---

## Task 3: Implement `onDialogueChunk` — open/extend a bundle for the NPC

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Replace the empty `onDialogueChunk` stub**

Replace this empty function body:

```kotlin
    fun onDialogueChunk(npcId: String, text: String, color: String?, voicePending: Boolean) {
        // Implemented in Task 3.
    }
```

with:

```kotlin
    fun onDialogueChunk(npcId: String, text: String, color: String?, voicePending: Boolean) {
        val now = System.currentTimeMillis()
        val bundle = bundleFor(npcKey = npcId, npcUuid = npcId, now = now)
        // First dialogue chunk of this bundle is "new"; subsequent chunks
        // (e.g. streaming tokens) are updates to the same session.
        val isNew = bundle.dialogue.isEmpty() && !TypingManager.isSessionActive(npcId)
        bundle.dialogue.add(DialogueChunk(text, color, isNew))
        if (voicePending) {
            bundle.voicePending = true
        }
        extendSeal(bundle, now)
    }

    /**
     * Get the open bundle for [npcKey], opening one if needed. Caller must
     * call [extendSeal] after mutating the bundle's contents to update its
     * deadline.
     */
    private fun bundleFor(npcKey: String, npcUuid: String?, now: Long): Bundle {
        return openBundles.getOrPut(npcKey) {
            Bundle(
                npcKey = npcKey,
                npcUuid = npcUuid,
                openedAtMs = now,
                sealAtMs = now + BUNDLE_WINDOW_MS,
            )
        }
    }

    /**
     * Extend the bundle's seal deadline based on what's inside:
     *   - normal: 200ms after the most recent event, bounded by 1000ms after
     *     the bundle was opened (prevents an event-spamming NPC from holding
     *     the bundle open forever)
     *   - voice-pending without audio yet: up to 3000ms after the bundle was
     *     opened (preserves the legacy VOICE_WAIT_TIMEOUT_MS semantic)
     */
    private fun extendSeal(bundle: Bundle, now: Long) {
        val cap = if (bundle.voicePending && bundle.voiceAudio == null) {
            bundle.openedAtMs + VOICE_WAIT_MS
        } else {
            bundle.openedAtMs + BUNDLE_MAX_OPEN_MS
        }
        bundle.sealAtMs = minOf(now + BUNDLE_WINDOW_MS, cap)
    }
```

- [ ] **Step 2: This adds a call to `TypingManager.isSessionActive(npcId)` which doesn't exist yet — expose it**

In `src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`, find the existing `hasActiveSession(): Boolean` near the top of the object and add this companion just below it:

```kotlin
    internal fun isSessionActive(npcId: String): Boolean = activeSessions.containsKey(npcId)
```

- [ ] **Step 3: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt \
        src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt
git commit -m "feat(pacer): onDialogueChunk — open/extend bundle, voice-wait override"
```

---

## Task 4: Implement `onVoiceAudio` — attach audio bytes to the NPC's bundle

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Replace the empty `onVoiceAudio` stub**

```kotlin
    fun onVoiceAudio(npcId: String, audioBytes: ByteArray) {
        // Implemented in Task 4.
    }
```

with:

```kotlin
    fun onVoiceAudio(npcId: String, audioBytes: ByteArray) {
        val now = System.currentTimeMillis()
        val bundle = bundleFor(npcKey = npcId, npcUuid = npcId, now = now)
        bundle.voiceAudio = audioBytes
        // Audio arrival cancels the voice-wait extension — once we have the
        // bytes there's no reason to keep the bundle open beyond the normal
        // 200ms window (capped at openedAt + 1000ms).
        extendSeal(bundle, now)
    }
```

- [ ] **Step 2: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt
git commit -m "feat(pacer): onVoiceAudio — attach bytes, cancel voice-wait extension"
```

---

## Task 5: Implement `onEmote` — add emote id with collapse-on-overflow

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Replace the empty `onEmote` stub**

```kotlin
    fun onEmote(entityId: Int, emoteId: String) {
        // Implemented in Task 5.
    }
```

with:

```kotlin
    fun onEmote(entityId: Int, emoteId: String) {
        val now = System.currentTimeMillis()
        val key = "entity:$entityId"
        // Collapse-on-overflow: if the queue is congested AND this emote id
        // is already pending on this entity, drop the dupe silently.
        val congested = readyQueue.size + openBundles.size > QUEUE_DEPTH_COLLAPSE_THRESHOLD
        if (congested) {
            val existing = openBundles[key]
            if (existing != null && existing.emotes.contains(emoteId)) {
                return
            }
        }
        val bundle = bundleFor(npcKey = key, npcUuid = null, now = now)
        bundle.emoteEntityId = entityId
        bundle.emotes.add(emoteId)
        extendSeal(bundle, now)
    }
```

- [ ] **Step 2: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt
git commit -m "feat(pacer): onEmote — add emote id, collapse dupes when congested"
```

---

## Task 6: Implement `onActionLabel` — last-write-wins on the bundle

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Replace the empty `onActionLabel` stub**

```kotlin
    fun onActionLabel(npcId: String, label: String) {
        // Implemented in Task 6.
    }
```

with:

```kotlin
    fun onActionLabel(npcId: String, label: String) {
        val now = System.currentTimeMillis()
        val bundle = bundleFor(npcKey = npcId, npcUuid = npcId, now = now)
        // Last-write-wins. Blank label = clear (handled by the renderer at
        // replay time, matches existing PerceptionPopupRenderer semantics).
        bundle.actionLabel = label
        extendSeal(bundle, now)
    }
```

- [ ] **Step 2: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt
git commit -m "feat(pacer): onActionLabel — last-write-wins"
```

---

## Task 7: Implement `replay` — atomic dispatch to existing renderers

This task wires the pacer's output to the actual renderers. It depends on three small visibility changes in other files (Task 7a) which we do first, then implement `replay` (Task 7b). Done as one commit.

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`

- [ ] **Step 1: Expose `TypingManager.findNpcEntityId` and the dialogue-rendering helper**

In `src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`:

a. Change `private fun findNpcEntityId(...)` (around line 83) to `internal fun findNpcEntityId(...)`.

b. Change `private fun displayNpcMessage(npcId: String, text: String, color: String?, isNew: Boolean)` (around line 54) to:

```kotlin
    internal fun renderNpcMessage(npcId: String, text: String, color: String?, isNew: Boolean) {
        MinecraftClient.getInstance().execute {
            val entityId = findNpcEntityId(npcId)

            if (isNew) {
                if (StoryClientConfig.useBubbleRenderer) {
                    BubbleRenderer.startBubble(npcId, entityId, text, color)
                } else {
                    NPCDialogueHud.startDialogue(npcId, text, color)
                }
            } else {
                if (StoryClientConfig.useBubbleRenderer) {
                    BubbleRenderer.updateBubble(npcId, text, color)
                } else {
                    NPCDialogueHud.updateDialogue(npcId, text, color)
                }
            }
        }
    }
```

(Rename + visibility change. The body is identical to the previous `displayNpcMessage`; only the name and access change.)

c. Find the existing call site inside the file — `parseAndDisplayNpcMessage` (around line 51) calls `displayNpcMessage(npcId, text, color, isNew)`. Change this to `renderNpcMessage(npcId, text, color, isNew)`.

d. Also the `onVoiceReceived` function (around line 78) calls `displayNpcMessage(...)`. Change to `renderNpcMessage(...)`. (This function is removed entirely in Task 8, but rename it cleanly first so this task compiles.)

- [ ] **Step 2: Expose `NPCMessageParserClient.playAudio`**

In `src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`, find `private fun playAudio(audioData: ByteArray, npcUuidFromPacket: String?)` (around line 581) and change `private` to `internal`. Move the function to a companion object so the pacer can call it without an instance:

Look at the existing structure: `playAudio` is currently an instance method on the `NPCMessageParserClient` class. The simplest reachable form is to make it a top-level `internal` function on the companion object. Find the existing `companion object` (around line 26) which currently contains `audioClip`, `chunkBuffer`, `activePositionalAudio`. The function uses these companion fields already, so it's safe to move.

Move `private fun playAudio(audioData: ByteArray, npcUuidFromPacket: String?)` (whole function body, around line 581) inside the existing `companion object` block (just before the closing `}` of the companion). Change `private` to `internal`. Verify it still compiles — every reference inside the function body should resolve since they were already on the companion.

- [ ] **Step 3: Implement `replay` in the pacer**

In `src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`, replace:

```kotlin
    private fun replay(bundle: Bundle) {
        // Implemented in Task 7. For now, no-op so Task 2 lands without
        // depending on the renderer wiring.
    }
```

with:

```kotlin
    private fun replay(bundle: Bundle) {
        val mc = net.minecraft.client.MinecraftClient.getInstance() ?: return
        mc.execute {
            // Order: action-label first (sets sticky), then emote (instant
            // visual reaction), then dialogue + voice together.
            if (bundle.actionLabel != null && bundle.npcUuid != null) {
                val uuid = java.util.UUID.fromString(bundle.npcUuid)
                val entityId = com.canefe.storyclient.client.TypingManager.findNpcEntityId(bundle.npcUuid) ?: -1
                com.canefe.storyclient.client.perception.PerceptionPopupRenderer.onPerception(
                    uuid,
                    bundle.actionLabel!!,
                    com.canefe.storyclient.client.perception.PopupType.ACTION,
                    entityId,
                )
            }
            for (emoteId in bundle.emotes) {
                com.canefe.storyclient.client.emote.EmoteRenderer.onEmote(bundle.emoteEntityId, emoteId)
            }
            for (chunk in bundle.dialogue) {
                if (bundle.npcUuid != null) {
                    com.canefe.storyclient.client.TypingManager.renderNpcMessage(
                        bundle.npcUuid,
                        chunk.text,
                        chunk.color,
                        chunk.isNew,
                    )
                }
            }
            if (bundle.voiceAudio != null) {
                com.canefe.storyclient.client.NPCMessageParserClient.playAudio(
                    bundle.voiceAudio!!,
                    bundle.npcUuid,
                )
            }
        }
    }
```

Add an import block at the top of `NpcEventPacer.kt` to avoid the inline FQNs (per project feedback memory):

```kotlin
import com.canefe.storyclient.client.NPCMessageParserClient
import com.canefe.storyclient.client.TypingManager
import com.canefe.storyclient.client.emote.EmoteRenderer
import com.canefe.storyclient.client.perception.PerceptionPopupRenderer
import net.minecraft.client.MinecraftClient
import java.util.UUID
```

And then simplify the `replay` body:

```kotlin
    private fun replay(bundle: Bundle) {
        val mc = MinecraftClient.getInstance() ?: return
        mc.execute {
            // Order: action-label first (sets sticky), then emote (instant
            // visual reaction), then dialogue + voice together.
            val uuidString = bundle.npcUuid
            if (bundle.actionLabel != null && uuidString != null) {
                val entityId = TypingManager.findNpcEntityId(uuidString) ?: -1
                PerceptionPopupRenderer.onPerception(
                    UUID.fromString(uuidString),
                    bundle.actionLabel!!,
                    PopupType.ACTION,
                    entityId,
                )
            }
            for (emoteId in bundle.emotes) {
                EmoteRenderer.onEmote(bundle.emoteEntityId, emoteId)
            }
            if (uuidString != null) {
                for (chunk in bundle.dialogue) {
                    TypingManager.renderNpcMessage(uuidString, chunk.text, chunk.color, chunk.isNew)
                }
            }
            if (bundle.voiceAudio != null) {
                NPCMessageParserClient.playAudio(bundle.voiceAudio!!, uuidString)
            }
        }
    }
```

**Crucial:** the `PerceptionPopupRenderer.onPerception(...)` signature in the spec uses 4 args `(uuid, label, type, entityId)`. The actual HEAD signature in this repo is `(npcUuid, perceivedLabel, type)` — 3 args. **Use whichever signature compiles.** If `onPerception` takes only 3 args at HEAD, drop the `entityId` arg from the call. The replay body is the same shape regardless; the renderer call has to match its real arity.

To verify the real signature, before writing the replay code, run:

```bash
grep -n "fun onPerception" /Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt
```

Use whatever you find. If 4-arg, keep the code above. If 3-arg, change to:

```kotlin
PerceptionPopupRenderer.onPerception(
    UUID.fromString(uuidString),
    bundle.actionLabel!!,
    PopupType.ACTION,
)
```

- [ ] **Step 4: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. If any compile error mentions a missing or mismatched type, read the actual file and adjust ONLY the call site (don't redefine the renderer).

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt \
        src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt \
        src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt
git commit -m "feat(pacer): replay dispatches to existing renderers in atomic MC-thread block"
```

---

## Task 8: Rewire the four payload-receiver call sites to route through the pacer

This task changes WHERE events enter the rendering pipeline. After this, all NPC visuals are paced. Before this, the pacer is dead code.

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`

### 8a: AudioPayload receiver

- [ ] **Step 1: Find the existing audio receiver**

In `NPCMessageParserClient.kt` around line 76–95, the current receiver body is roughly:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(AudioPayload.ID) { payload, context ->
            try {
                println("📦 Received audio payload! Size: ${payload.audioData.size} bytes")
                context.client().execute {
                    val (npcUuid, audioBytes) = extractNpcHeader(payload.audioData)
                    println("🎵 Processing audio data... npcUuid=$npcUuid, audioSize=${audioBytes.size}")

                    if (npcUuid != null) {
                        TypingManager.onVoiceReceived(npcUuid)
                    }

                    playAudio(audioBytes, npcUuid)
                }
            } catch (e: Exception) {
                println("❌ Error processing audio packet: ${e.message}")
                e.printStackTrace()
            }
        }
```

- [ ] **Step 2: Replace it with a pacer-routed version**

Change the receiver body to:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(AudioPayload.ID) { payload, context ->
            try {
                context.client().execute {
                    val (npcUuid, audioBytes) = extractNpcHeader(payload.audioData)
                    if (npcUuid != null) {
                        NpcEventPacer.onVoiceAudio(npcUuid, audioBytes)
                    } else {
                        // No uuid header — legacy path, play immediately.
                        playAudio(audioBytes, null)
                    }
                }
            } catch (e: Exception) {
                println("❌ Error processing audio packet: ${e.message}")
                e.printStackTrace()
            }
        }
```

Add `import com.canefe.storyclient.client.pacing.NpcEventPacer` at the top of the file if not already present.

### 8b: NpcEmoteIconPayload receiver

- [ ] **Step 3: Change the emote receiver body**

The HEAD version of the body is:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(NpcEmoteIconPayload.ID) { payload, _ ->
            EmoteRenderer.onEmote(payload.entityId, payload.emoteId)
        }
```

Change it to:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(NpcEmoteIconPayload.ID) { payload, _ ->
            NpcEventPacer.onEmote(payload.entityId, payload.emoteId)
        }
```

### 8c: NpcPerceptionPayload receiver

- [ ] **Step 4: Change the perception receiver body for ACTION type only**

The HEAD version of the body is:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.perception.NpcPerceptionPayload.ID,
        ) { payload, _ ->
            com.canefe.storyclient.client.perception.PerceptionPopupRenderer.onPerception(
                payload.npcUuid,
                payload.perceivedLabel,
                payload.type,
            )
        }
```

Change it to:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.perception.NpcPerceptionPayload.ID,
        ) { payload, _ ->
            if (payload.type == com.canefe.storyclient.client.perception.PopupType.ACTION) {
                NpcEventPacer.onActionLabel(payload.npcUuid.toString(), payload.perceivedLabel)
            } else {
                com.canefe.storyclient.client.perception.PerceptionPopupRenderer.onPerception(
                    payload.npcUuid,
                    payload.perceivedLabel,
                    payload.type,
                )
            }
        }
```

**Working-tree note**: the working-tree version of this receiver has an extra debug `println` and a 4-arg `onPerception` call. Both belong to the user's unstaged in-flight perception-debug work. **Do not edit those lines.** Match the structure above against the **HEAD** version (use `git show HEAD:src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` to confirm). Only commit the type-gate logic.

If the working-tree state makes the Edit ambiguous, stop and report BLOCKED.

### 8d: TypingManager.parseAndDisplayNpcMessage

- [ ] **Step 5: Route dialogue through the pacer**

In `TypingManager.kt`, replace the entire `parseAndDisplayNpcMessage` function (currently around line 42–52):

```kotlin
    private fun parseAndDisplayNpcMessage(npcId: String, text: String, color: String? = null, voicePending: Boolean = false) {
        val isNew = !activeSessions.containsKey(npcId)

        if (voicePending) {
            pendingVoiceDialogues[npcId] = PendingDialogue(npcId, text, color, isNew)
            return
        }

        displayNpcMessage(npcId, text, color, isNew)
    }
```

with:

```kotlin
    private fun parseAndDisplayNpcMessage(npcId: String, text: String, color: String? = null, voicePending: Boolean = false) {
        // The pacer owns voice-wait state via Bundle.voicePending. We hand
        // it the chunk and let it decide when to call back into renderNpcMessage.
        com.canefe.storyclient.client.pacing.NpcEventPacer.onDialogueChunk(npcId, text, color, voicePending)
    }
```

### 8e: Drive the pacer's tick from the existing tick loop

- [ ] **Step 6: Add a `NpcEventPacer.tick()` call to TypingManager.tick()**

In `TypingManager.kt`, at the bottom of the existing `tick()` (around line 271–325), add **as the last statement before the closing brace**:

```kotlin
        com.canefe.storyclient.client.pacing.NpcEventPacer.tick()
```

(The existing tick loop already runs every client tick via `ClientTickEvents.END_CLIENT_TICK.register { TypingManager.tick() }` in NPCMessageParserClient.kt. We piggyback on that.)

- [ ] **Step 7: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

Confirm `git status` shows ONLY these two files newly staged before committing.

```bash
cd /Users/canefe/Projects/personal/StoryClient
git status -sb | head -15
git diff --cached --stat
git add src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt \
        src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt
git commit -m "feat(pacer): rewire dialogue/voice/emote/action-label through NpcEventPacer"
```

If `git diff --cached --stat` shows any other file or any unrelated hunks (e.g. debug println, helix imports), STOP and report BLOCKED — that means in-flight work got staged.

---

## Task 9: Remove the now-dead `pendingVoiceDialogues` machinery from TypingManager

After Task 8, `pendingVoiceDialogues` is unreferenced and the voice-wait responsibility lives entirely in the pacer. Clean up the dead code.

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt`

- [ ] **Step 1: Remove the `PendingDialogue` data class and the `pendingVoiceDialogues` map**

In `TypingManager.kt`, delete these declarations (around lines 21–30):

```kotlin
    // Voice sync: holds dialogue display until voice arrives
    private data class PendingDialogue(
        val npcId: String,
        val text: String,
        val color: String?,
        val isNew: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val pendingVoiceDialogues = java.util.concurrent.ConcurrentHashMap<String, PendingDialogue>()
    private const val VOICE_WAIT_TIMEOUT_MS = 3000L // Display dialogue after 3s even without voice
```

- [ ] **Step 2: Remove `onVoiceReceived`**

Delete the entire function (around lines 74–81):

```kotlin
    /**
     * Called when audio arrives for an NPC. If there's a pending dialogue waiting
     * for voice, display it now.
     */
    fun onVoiceReceived(npcId: String) {
        val pending = pendingVoiceDialogues.remove(npcId) ?: return
        renderNpcMessage(pending.npcId, pending.text, pending.color, pending.isNew)
    }
```

- [ ] **Step 3: Remove the voice-timeout block from `tick()`**

In `tick()` around line 301–308, delete:

```kotlin
        // Timeout pending voice dialogues — display them even without voice
        val timedOut = pendingVoiceDialogues.entries.toList()
            .filter { (_, pending) -> now - pending.timestamp > VOICE_WAIT_TIMEOUT_MS }
        for ((npcId, pending) in timedOut) {
            pendingVoiceDialogues.remove(npcId)
            renderNpcMessage(pending.npcId, pending.text, pending.color, pending.isNew)
        }
```

- [ ] **Step 4: Compile**

```bash
cd /Users/canefe/Projects/personal/StoryClient
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew compileClientKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. If anything else still references `pendingVoiceDialogues` or `onVoiceReceived`, grep to find it:

```bash
grep -rn "pendingVoiceDialogues\|onVoiceReceived" src/client/kotlin/
```

Expected: zero hits.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git status -sb | head -3
git add src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt
git commit -m "refactor(typing): remove pendingVoiceDialogues — pacer owns voice-wait now"
```

---

## Task 10: Manual smoke test

No new code. Verify the feature end-to-end.

- [ ] **Step 1: Start the local stack**

Per the standard recipe (NATS, story-sim, story-go, MC server with Story plugin, MC client with StoryClient mod).

- [ ] **Step 2: Trigger Scenario 1 — two simultaneous emotes**

In sim, fire `char:emote("laugh")` on two different NPCs at the same tick. (Easiest: temporarily add the calls to two NPCs' `OnStart` hook, or invoke via a dev/REPL command.)

**Expected:** the two laugh icons appear ~1 second apart in client, NOT simultaneously.

- [ ] **Step 3: Trigger Scenario 2 — emote + speak from one effect**

Trigger an NPC interaction that fires `joke_landed` (chat that buckets as warm). The effect calls both `char:emote("laugh")` and `char.partner:emote("laugh")`, and chat dialogue.

**Expected:** for each NPC, the emote icon and its dialogue line appear together in the same pop (the bundle stays intact). Both NPCs' bundles release ~1s apart.

- [ ] **Step 4: Trigger Scenario 3 — dialogue + voice sync preserved**

Trigger an NPC speech that carries a `voice:1` header and an audio payload.

**Expected:** the dialogue line appears together with the voice audio (no longer than 3s delay), the same as today's behavior. The bundle waits up to 3s for the audio.

- [ ] **Step 5: Trigger Scenario 4 — disable mod mid-session**

While bundles are in flight, set `modEnabled = false` in the StoryClient config (or use the in-mod toggle). All queued visuals should stop arriving.

**Expected:** no orphan bubbles or icons appear after the toggle.

(Note: the spec says modEnabled-toggle clears the queue. The current implementation does not explicitly clear `openBundles`/`readyQueue` when modEnabled flips. If this fails the test, add the clearing inline in `tick()` — the early `if (!StoryClientConfig.modEnabled) return` in `onIncomingServerMessage` already prevents new events from entering. To make existing queued events stop firing, add at the top of `NpcEventPacer.tickAt`:

```kotlin
        if (!com.canefe.storyclient.client.StoryClientConfig.modEnabled) {
            openBundles.clear()
            readyQueue.clear()
            return
        }
```

If you needed this fix, commit it as a separate cleanup task with message: `fix(pacer): clear bundles when modEnabled toggles off`. Otherwise skip.)

- [ ] **Step 6: Report**

Confirm all 4 scenarios behave as expected. If any fails, identify the specific bundle-lifecycle step that broke and either fix in-place (small follow-up commit, conventional message) or report what you found.

---

## Self-Review

**Spec coverage:**

- §Architecture: Tasks 1+2 (skeleton + tick), Tasks 3-6 (4 onXxx methods), Task 7 (replay), Task 8 (rewiring). ✓
- §Bundle lifecycle (open, extend, voice-wait override, seal, pop): Tasks 2-6 collectively. The `extendSeal` helper in Task 3 covers extend + voice-wait override + hard cap. ✓
- §Replay order (action-label → emote → dialogue → voice): Task 7. ✓
- §Collapse-on-overflow: Task 5 (only emotes, only when congested, only same-id duplicates). ✓
- §Wiring changes (4 sites): Task 8 covers all four — audio, emote, perception ACTION-type, dialogue via parseAndDisplayNpcMessage. Plus the tick driver. ✓
- §Edge cases (NPC despawns, voice-only bundle, streaming chunks, mod disable, blank action label, same emote 3×, voice before dialogue, voice never arrives, finishSession during queue): handled by the bundle data model + replay defensive checks (`if uuidString != null`, the existing renderers' null-tolerance). Mod-disable clearing is called out in Task 10 Step 5 as a conditional fix-in-place since it's a small policy point.
- §Removal of old code: Task 9 explicitly removes `pendingVoiceDialogues`, `PendingDialogue`, `onVoiceReceived`, and the voice-timeout tick block. ✓
- §Out of scope (cross-event priority, configurable interval, adaptive pacing, non-NPC visuals, persistence): not implemented. ✓

**Placeholder scan:** No "TBD", "TODO", "fill in details". Two `Implemented in Task N` placeholder comments exist in Task 1's skeleton — those are explicit promises that get fulfilled in named subsequent tasks (Tasks 2-7), with the actual code each time. That's a legitimate progressive-build pattern, not a no-content placeholder.

**Type consistency:**
- `npcId: String`, `entityId: Int`, `emoteId: String`, `audioBytes: ByteArray`, `label: String` — consistent across all `onXxx` signatures. ✓
- `Bundle.npcKey`, `Bundle.npcUuid`, `Bundle.emoteEntityId` — same names used in lookup and replay. ✓
- Constants `BUNDLE_WINDOW_MS`, `BUNDLE_MAX_OPEN_MS`, `VOICE_WAIT_MS`, `POP_INTERVAL_MS`, `QUEUE_DEPTH_COLLAPSE_THRESHOLD` — defined once in Task 1, referenced consistently. ✓
- `TypingManager.findNpcEntityId` and `TypingManager.renderNpcMessage` — visibility change in Task 7, used in Task 7's replay body. ✓
- `NPCMessageParserClient.playAudio` — visibility + companion-move in Task 7, called from Task 7's replay body. ✓

**Verification confirmed before plan**: `PerceptionPopupRenderer.onPerception` signature at HEAD is 3-arg `(npcUuid, perceivedLabel, type)`. Task 7 explicitly handles both 3-arg and 4-arg shapes with a grep-first instruction. ✓

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-27-npc-event-pacer.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task with two-stage review. 10 small tasks; matches the pattern we used for emote icons.

**2. Inline Execution** — execute tasks here in this session with checkpoints.

**Which approach?**
