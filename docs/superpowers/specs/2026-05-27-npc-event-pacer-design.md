# NPC Event Pacing Queue — Design

**Date:** 2026-05-27
**Status:** Approved (design)
**Scope:** StoryClient (Fabric mod). A single global queue on the client that paces NPC-driven visual events (dialogue + voice + emote icons + action labels) so they release one bundle per second, with same-NPC events grouped into one atomic bundle.

## Problem

The current client renders NPC visuals as soon as their packets arrive. When the sim, story-go, and Paper plugin emit several events at once — common in scenes with multiple NPCs, or even one NPC firing a multi-effect outcome (e.g. `joke_landed` emits speak + emote on both sides) — the client floods the screen with bubbles, voice audio, emote icons, and action labels simultaneously. The result is visual noise; the player cannot read or follow what is happening.

The existing `TypingManager.pendingVoiceDialogues` map already implements a per-NPC hold that delays a dialogue chunk until its matching voice audio arrives (or 3s timeout). That pattern works well but is narrowly scoped to dialogue+voice sync. We generalize it.

## Goal

A single global pacing queue on the client routes all NPC visual events. Events for the same NPC arriving within a short window are merged into one **bundle** that pops atomically (preserving dialogue+voice sync and keeping a single NPC's reaction visually coherent). Bundles drip out at 1 second per bundle, regardless of how many NPCs are talking at once. The 3-second voice-wait semantic is preserved.

## Architecture

```
                                   ┌──────────────────────────────────┐
emote payload     ─────────────►   │                                  │
dialogue chunks   ─────────────►   │  NpcEventPacer                   │
voice audio       ─────────────►   │  - openBundles: per-NPC, sealing │ ─pop one bundle every 1.0s─►  Bubble + Emote + Audio + Action
action-label      ─────────────►   │  - readyQueue: FIFO<Bundle>      │                               renderers fire on the MC thread
                                   │  - bundleWindow: 200ms           │
                                   │  - voiceWait: 3000ms (overrides) │
                                   └──────────────────────────────────┘
```

A new singleton `NpcEventPacer` becomes the single funnel for all NPC-driven visual events on the client. Today's flow is direct: payload arrives → renderer fires. Proposed flow: payload arrives → pacer accumulates into a per-NPC bundle → bundle seals after 200ms (or 3s if waiting for voice) → bundle joins a global FIFO → `tick()` pops one bundle per second and replays it to the existing renderers.

The pacer is the only new component. Existing renderers (`BubbleRenderer`, `EmoteRenderer`, `PerceptionPopupRenderer`, audio playback) are untouched — they just get called from a different upstream site.

## Components

### NpcEventPacer (new singleton, ~150 LOC, sibling to TypingManager)

Internal state:

```kotlin
private data class Bundle(
    val npcId: String,
    val openedAtMs: Long,
    var sealAtMs: Long,                    // openedAtMs + 200ms; extended by new events up to a hard ceiling
    val dialogue: MutableList<DialogueChunk> = mutableListOf(),  // (text, color, isNew) tuples
    var voiceAudio: ByteArray? = null,
    var voicePending: Boolean = false,     // dialogue had `voice:1` header
    val emotes: MutableList<String> = mutableListOf(),
    var actionLabel: String? = null,       // last write wins
)
private val openBundles = ConcurrentHashMap<String, Bundle>()
private val readyQueue = ConcurrentLinkedDeque<Bundle>()
private var lastPopMs = 0L

private const val BUNDLE_WINDOW_MS = 200L
private const val BUNDLE_MAX_OPEN_MS = 1000L
private const val VOICE_WAIT_MS = 3000L
private const val POP_INTERVAL_MS = 1000L
private const val QUEUE_DEPTH_COLLAPSE_THRESHOLD = 8
```

Public API:

```kotlin
fun onDialogueChunk(npcId: String, text: String, color: String?, voicePending: Boolean)
fun onVoiceAudio(npcId: String, audioBytes: ByteArray)
fun onEmote(npcId: String, emoteId: String)
fun onActionLabel(npcId: String, label: String)   // blank = clear
fun tick()
internal fun tickForTest(nowMs: Long)             // for deterministic testing if test set added later
```

### Bundle lifecycle

1. **Open**: first event for `npcId` constructs a `Bundle(openedAtMs = now, sealAtMs = now + 200ms)` and puts it in `openBundles`.
2. **Extend**: any subsequent event for the same NPC bumps `sealAtMs = min(now + 200ms, openedAtMs + 1000ms)`. The hard ceiling at `openedAtMs + 1000ms` prevents a spam source from holding the bundle open forever.
3. **Voice-wait override**: if `voicePending = true` and `voiceAudio` is still null, `sealAtMs` is set to `min(sealAtMs, openedAtMs + 3000ms)` so the bundle waits up to 3s for the audio. This preserves the existing `VOICE_WAIT_TIMEOUT_MS` semantic exactly.
4. **Seal**: in `tick()`, when `now >= sealAtMs`, the bundle is moved from `openBundles` to `readyQueue`. At seal time, the emote list is deduped via `distinct()`.
5. **Pop**: also in `tick()`, when `now - lastPopMs >= 1000ms` and `readyQueue` is nonempty, pop one bundle and replay it. Set `lastPopMs = now`.

### Replay (atomic, on the MC thread)

```kotlin
MinecraftClient.getInstance().execute {
    val entityId = TypingManager.findNpcEntityId(bundle.npcId)   // existing helper, exposed internal
    bundle.actionLabel?.let { /* PerceptionPopupRenderer.onAction(npcId, it, entityId) */ }
    bundle.emotes.forEach { EmoteRenderer.onEmote(entityId ?: -1, it) }
    bundle.dialogue.forEach { /* current displayNpcMessage logic */ }
    bundle.voiceAudio?.let { playAudio(it, bundle.npcId) }
}
```

Order: action-label first (sets the sticky label for the rest of the bundle), then emotes (instant visual reaction), then dialogue+voice together. This matches the natural reading order of a single NPC's reaction beat.

### Collapse-on-overflow

When a new event arrives and `readyQueue.size + openBundles.size > 8`:

- New emote whose ID is already in the target NPC's open bundle's `emotes` list → drop silently.
- Otherwise, accept normally.

This is intentionally a single rule (collapse duplicate emotes per NPC), not a generic eviction policy. Dialogue and action labels are never dropped. The bundle hard-ceiling at 1000ms ensures the queue always drains.

### Wiring changes

Four call sites change (no renderer changes, no payload changes):

1. **`NPCMessageParserClient.kt` — AudioPayload receiver**
   Currently: `playAudio(audioBytes, npcUuid)` + `TypingManager.onVoiceReceived(npcUuid)` directly.
   New: `NpcEventPacer.onVoiceAudio(npcUuid, audioBytes)`. The pacer holds the bytes in the bundle and triggers playback at replay time.

2. **`NPCMessageParserClient.kt` — NpcEmoteIconPayload receiver**
   Currently: `EmoteRenderer.onEmote(payload.entityId, payload.emoteId)` directly.
   New: `NpcEventPacer.onEmote(npcId, payload.emoteId)`. The pacer needs the NPC's UUID-string for bundling; we look it up via `EntityIdToNpcUuid` cache (or, simplest, pass `payload.entityId` through as the bundle key when emoteId payloads don't carry uuid — see Open Question below).

3. **`NPCMessageParserClient.kt` — NpcPerceptionPayload receiver (ACTION type only)**
   Currently: `PerceptionPopupRenderer.onPerception(npcUuid, label, ACTION, entityId)` directly.
   New: route ACTION-type payloads through `NpcEventPacer.onActionLabel(npcUuid, label)`; other PopupType values (PERCEPTION, MOOD, COMBAT_*, AGGRESSION) pass through unchanged — those are different signal classes and not part of the "NPC talking" beat.

4. **`TypingManager.parseAndDisplayNpcMessage`**
   Currently: writes to `pendingVoiceDialogues[npcId]` if `voicePending`, otherwise calls `displayNpcMessage` immediately.
   New: always calls `NpcEventPacer.onDialogueChunk(npcId, text, color, voicePending)`. The pacer owns the voice-wait state via `Bundle.voicePending`. The `pendingVoiceDialogues` map and `onVoiceReceived` function are removed.

`TypingManager.tick()` continues to handle session chunking and 10s cleanup; it ALSO calls `NpcEventPacer.tick()` once per client tick.

`TypingManager.findNpcEntityId` becomes `internal` so the pacer can use it at replay time.

### Open Question (resolved during implementation)

The emote payload carries entity-id but not uuid-string; the pacer keys bundles by uuid-string everywhere else. The simplest fix: the pacer's `onEmote(npcId: String, ...)` accepts a uuid-string OR an entity-id-derived synthetic key. Implementation note: we use uuid-string when available; emote-only bundles for an unknown uuid use `"entity:$entityId"` as the bundle key. This works because emotes are entirely self-contained inside their bundle (don't merge with dialogue keyed by uuid). Acceptable for v1.

## Data Flow Example

**Scenario:** `joke_landed` fires on actor=Aldo and partner=Beth at the same sim tick.

```
T+0ms     NpcEmoteIconPayload (Aldo, LAUGH)        → openBundles[aldo] = Bundle{emotes=[LAUGH], sealAt=200}
T+5ms     NpcEmoteIconPayload (Beth, LAUGH)        → openBundles[beth] = Bundle{emotes=[LAUGH], sealAt=205}
T+50ms    <npc_typing> dialogue from Aldo (voice:1)→ openBundles[aldo].dialogue += chunk; voicePending=true; sealAt=3000
T+800ms   AudioPayload arrives (Aldo)              → openBundles[aldo].voiceAudio = bytes; sealAt=1000 (collapsed back)
T+1000ms  tick: openBundles[aldo].sealAtMs ≤ now   → seal aldo bundle into readyQueue; pop aldo (lastPop=0); replay
T+1005ms  tick: openBundles[beth].sealAtMs ≤ now   → seal beth bundle into readyQueue
T+2000ms  tick: 1000ms since lastPop, readyQueue   → pop beth; replay LAUGH on Beth
                has [beth]
```

Player sees: Aldo's full reaction (LAUGH + voiced dialogue) at T+1s, Beth's LAUGH at T+2s. Without the pacer they'd be near-simultaneous at T+0ms / T+800ms with chaotic overlap.

## Error Handling & Edge Cases

| Condition | Behavior |
|---|---|
| Bundle's NPC disconnects/despawns before pop | Bundle pops normally; `findNpcEntityId` returns null; renderers silently no-op (existing behavior). |
| Voice audio arrives but no dialogue is pending | Pacer opens a voice-only bundle; replay just plays the audio. |
| Streaming dialogue (5 chunks of one utterance over 800ms) | All 5 chunks land in the same bundle, each extending `sealAtMs`. Hard ceiling at 1000ms forces a seal so the bundle drains. The `voicePending` flag overrides this up to 3s. |
| Player toggles `modEnabled = false` mid-bundle | `tick()` clears `openBundles` and `readyQueue`. No orphan visuals. |
| Action label = blank (= clear) | Stored as `actionLabel = ""`; replay calls the existing renderer's clear path. |
| Two action labels for one NPC inside bundle window | Last-write-wins. |
| Same emote ID 3× for one NPC inside bundle window | `distinct()` at seal → shows once. |
| Queue depth grows under load, then load stops | Drains at 1 bundle/sec. 30 backed-up bundles = 30s drain. Acceptable. |
| `TypingManager.finishSessionForNpc(X)` called while X's bundle is queued | Bundle pops normally; the dialogue still renders for its configured display time then ends. Session-end is independent of pacing. |
| Voice arrives BEFORE its dialogue chunk | Audio buffered in bundle's `voiceAudio`; dialogue chunk arrives, merges; seal proceeds normally. |
| Voice arrives but no dialogue ever does (3s timeout) | Same handling as today — bundle seals at the 3s ceiling, replays audio only. |

## Testing

Per the discovery during emote-icons work, StoryClient has no test source set in the current build. To preserve testability:

- `NpcEventPacer.tickForTest(nowMs: Long)` accepts injected time so a future test source set can write deterministic tests against bundle merge, seal timing, pop pacing, dedupe, overflow, and voice-wait timeout.

**Manual smoke tests:**

1. Two NPCs emote LAUGH simultaneously → both icons appear ~1s apart, not at the same time.
2. One NPC emits emote + speak from a single Lua effect (`joke_landed`) → both render together in the same pop.
3. Three NPCs queue events while voice audio is streaming → all replays remain dialogue+voice-synced inside their bundles.
4. Disable mod mid-session → queue clears, no orphan visuals.
5. `socialize_bond` outcome with both sides hostile → both ANGER icons appear ~1s apart with their respective chat lines.

## Out of Scope

- **Cross-event-type priority** (e.g. dialogue > emote starvation prevention). Collapse-dedup is enough for v1.
- **Configurable `POP_INTERVAL_MS`** in storyclient.json. Hardcoded 1000ms; expose later if needed.
- **Adaptive pacing** (drain faster when queue is large). Explicitly rejected during brainstorming.
- **Pacing non-NPC visuals** — squad badges, puppet cursors, item holograms, perception popups other than ACTION type. Those are different signal classes and not part of the "NPC talking" beat.
- **Persistence** — the pacer is in-memory only. Mod restart clears all queued bundles.

## Files Touched (anticipated)

- New: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/pacing/NpcEventPacer.kt`
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/TypingManager.kt` — route dialogue through pacer, remove `pendingVoiceDialogues` map, expose `findNpcEntityId` as `internal`, drive `NpcEventPacer.tick()` from existing `tick()`.
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` — three receiver call sites (AudioPayload, NpcEmoteIconPayload, NpcPerceptionPayload).
