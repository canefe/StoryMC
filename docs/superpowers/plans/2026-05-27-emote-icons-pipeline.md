# Emote Icons Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 4-repo vertical slice that lets a story-sim Lua behavior call `char:emote("laugh")` and have a floating PNG icon (one of Cry / Anger / Pain / Laugh / Shock) float-and-fade above the NPC's head in StoryClient.

**Architecture:** New `IntentKind::Emote` in story-sim, dispatched as a `npc.emote_icon` JSON envelope on NATS. story-go forwards verbatim (no code change — emote types aren't on the DM-grab denylist, by design). StoryMC adds a new `NPCEmoteIconIntent` event + `IntentExecutor` handler that broadcasts a `story:npc_emote_icon` plugin-message packet. StoryClient adds a `NpcEmoteIconPayload` CustomPayload + new `EmoteRenderer` that draws a billboarded textured quad above the NPC's head with the RISE/HOLD/EXIT animation curve copied from PerceptionPopupRenderer (350/900/400ms), always visible (no crosshair gating). Free-form `emote_id` string on the wire; client holds the allowlist `{cry, anger, pain, laugh, shock} → Identifier`.

**Tech Stack:**
- story-sim: Rust + Bevy + mlua, NATS bridge
- story-go: Go pass-through (zero changes for this feature)
- StoryMC: Kotlin + Paper + PacketEvents + WebSocket bus
- StoryClient: Kotlin + Fabric 1.21 client, Minecraft rendering APIs
- story-proto: Optional — see Task 0 note

**Spec:** `docs/superpowers/specs/2026-05-27-emote-icons-pipeline-design.md`

---

## File Structure

**story-sim**
- Modify: `src/plugins/frontend_intent.rs` — add `IntentKind::Emote { emote_id }`, Descriptor entry, tests
- Modify: `src/plugins/behavior/execution.rs` — register `char:emote` Lua function on the behavior-hook surface

**story-go**
- No file changes. (The bridge fans out any unknown sim event type by default. DM-grab gate `gatedWhileGrabbed` does NOT include emotes — by design, per code comment in `grab_registry.go`.) One regression test added.
- Modify: `internal/sim/grab_registry_test.go` — add a test asserting emote_icon is NOT gated, so a future denylist edit doesn't accidentally break this.

**StoryMC (Story plugin)**
- Modify: `src/main/kotlin/com/canefe/story/bridge/StoryEvent.kt` — add `NPCEmoteIconIntent` data class
- Modify: `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — register in serializeEvent + inbound deserialize switch
- Modify: `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` — add `executeEmoteIconIntent`
- Modify: `src/main/kotlin/com/canefe/story/Story.kt` — wire `eventBus.on<NPCEmoteIconIntent> { ... }`
- Create: `src/test/kotlin/com/canefe/story/bridge/IntentExecutorEmoteIconTest.kt`

**StoryClient**
- Create: `src/client/resources/assets/storyclient/textures/emote/cry.png`
- Create: `src/client/resources/assets/storyclient/textures/emote/anger.png`
- Create: `src/client/resources/assets/storyclient/textures/emote/pain.png`
- Create: `src/client/resources/assets/storyclient/textures/emote/laugh.png`
- Create: `src/client/resources/assets/storyclient/textures/emote/shock.png`
- Create: `src/client/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayload.kt`
- Create: `src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt`
- Modify: `src/client/kotlin/com/canefe/storyclient/StoryClient.kt` (or whichever class registers payloads & world render callbacks — confirm by grep at task time) — register payload codec + WorldRenderEvents listener

---

## Task 0: Note on story-proto

Per the spec, `OrchestratorIntent` could grow an `NPCEmoteIconIntent` variant. **In practice the entire pipeline runs on proto-JSON envelopes today and the existing `NPCEmoteIntent` (text emote) is JSON-serialized via kotlinx without proto**. We follow that precedent: ship the v1 emote icon as JSON-only, with field names matching the wire (`characterId`, `emoteId`). Adding a proto schema is an optional follow-up that does not block this plan. Skip story-proto entirely for v1.

---

## Task 1: story-sim — add `IntentKind::Emote` variant + Descriptor

**Files:**
- Modify: `story-sim/src/plugins/frontend_intent.rs` (variant declaration around line 100; REGISTRY entry around line 238 after `speak`; tests in `#[cfg(test)] mod tests`)

- [ ] **Step 1: Write the failing test**

Add this test inside the existing `#[cfg(test)] mod tests` block at the bottom of `frontend_intent.rs`:

```rust
#[test]
fn emote_intent_publishes_npc_emote_icon_envelope() {
    let intent = FrontendIntent {
        character_id: "npc_42".to_string(),
        intent: IntentKind::Emote { emote_id: "laugh".to_string() },
        intent_id: "ignored-by-emote".to_string(),
    };
    let published = dispatch_intent(&intent, "ignored-by-emote", "world_overworld");
    assert_eq!(published.envelope_type, "npc.emote_icon");
    assert_eq!(published.primitive, "emote");
    assert!(!published.expects_outcome, "emote is fire-and-forget like speak");
    let data = published.envelope.get("data").unwrap();
    assert_eq!(data.get("characterId").unwrap().as_str().unwrap(), "npc_42");
    assert_eq!(data.get("emoteId").unwrap().as_str().unwrap(), "laugh");
    // Like speak, emote does not carry `primitive` in the data payload.
    assert!(data.get("primitive").is_none(), "emote envelope should not include data.primitive");
    // Fire-and-forget primitives don't ride intent_id either.
    assert!(data.get("intentId").is_none(), "emote should not include intentId (no outcome expected)");
}

#[test]
fn registry_covers_emote_variant() {
    let kind = IntentKind::Emote { emote_id: "cry".to_string() };
    // descriptor_for panics if a variant isn't registered.
    let d = descriptor_for(&kind);
    assert_eq!(d.primitive, "emote");
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test -p story-sim --lib emote_intent_publishes_npc_emote_icon_envelope registry_covers_emote_variant 2>&1 | tail -30`

Expected: Compilation error — `IntentKind::Emote` variant does not exist.

- [ ] **Step 3: Add the `Emote` variant to `IntentKind`**

In `frontend_intent.rs`, in the `IntentKind` enum (around line 95), add a new variant after `ClearLookAt`:

```rust
#[derive(Debug, Clone)]
pub enum IntentKind {
    SetTarget { target_char_id: String },
    NavigateTo { x: f32, y: f32, z: f32 },
    FleeFrom { from_x: f32, from_z: f32, min_dist: f32, max_dist: f32 },
    AttemptHit { target_char_id: String, direction: Option<SwingDir> },
    ClearTarget,
    Speak { message: String },
    LookAt { target_char_id: String },
    ClearLookAt,
    Emote { emote_id: String },
}
```

- [ ] **Step 4: Add a Descriptor entry to REGISTRY**

In `frontend_intent.rs`, inside the `const REGISTRY: &[Descriptor] = &[ ... ];` array, add a new entry after the `clear_look_at` entry (around line 272). Place it as the last element of the slice:

```rust
    Descriptor {
        primitive: "emote",
        envelope_type: "npc.emote_icon",
        expects_outcome: false,
        owns_data: false,
        // Emote is fire-and-forget (like speak): no outcome, no `primitive` in
        // data. We strip the `primitive` key the dispatcher pre-inserts.
        matches: |k| matches!(k, IntentKind::Emote { .. }),
        to_data: |k, d, _cid, _iid, _world| {
            d.remove("primitive");
            if let IntentKind::Emote { emote_id } = k {
                d.insert("emoteId".into(), serde_json::Value::String(emote_id.clone()));
            }
        },
    },
```

- [ ] **Step 5: Run the new tests + ALL existing frontend_intent tests to verify they pass**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test -p story-sim --lib frontend_intent:: 2>&1 | tail -30`

Expected: All tests pass, including the two new ones and the existing `registry_covers_all_variants` invariant test.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
git add src/plugins/frontend_intent.rs
git commit -m "feat(intents): add IntentKind::Emote variant publishing npc.emote_icon envelope"
```

---

## Task 2: story-sim — expose `char:emote(id)` on the behavior-hook surface

**Files:**
- Modify: `story-sim/src/plugins/behavior/execution.rs` (closure registration around line 1814, near where `speak` is registered)

- [ ] **Step 1: Write the failing test**

Add this test to the existing test module in `execution.rs`. If the file has no test module, add one at the bottom. The simplest check is end-to-end: invoke the behavior hook with a tiny Lua snippet that calls `char:emote("laugh")` and assert the FrontendIntentQueue contains an `Emote` intent.

Find an existing test in `execution.rs` that already drives a Lua snippet through `run_behavior_hook` or similar (search for `char:speak` in test code) and copy the harness. If none exists, here's a self-contained shape — adapt the test-harness wiring to whatever the file's existing tests use:

```rust
#[cfg(test)]
mod emote_tests {
    use super::*;
    use crate::plugins::frontend_intent::{FrontendIntentQueue, IntentKind};
    use std::sync::{Arc, Mutex};

    #[test]
    fn char_emote_enqueues_emote_intent() {
        // Build the same Lua scope construction the production code uses.
        // The exact helper to call lives in this file (search for the existing
        // test that exercises `char:speak`). Reuse it verbatim.
        let queue = FrontendIntentQueue {
            pending: Arc::new(Mutex::new(Vec::new())),
        };
        let lua = mlua::Lua::new();
        lua.scope(|scope| {
            let char_t = lua.create_table()?;
            // Call the production registrar that attaches `emote` (the function
            // refactored out of `run_behavior_hook` OR — if not refactored —
            // copy the closure body verbatim from execution.rs).
            attach_emote_for_test(&lua, scope, &char_t, "npc_42", queue.clone())?;
            lua.load(r#"char:emote("laugh")"#).set_environment(
                lua.create_table_from([("char", char_t)])?
            ).exec()?;
            Ok(())
        }).unwrap();

        let pending = queue.pending.lock().unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].character_id, "npc_42");
        match &pending[0].intent {
            IntentKind::Emote { emote_id } => assert_eq!(emote_id, "laugh"),
            other => panic!("expected Emote, got {:?}", other),
        }
    }
}
```

> **Note:** Pre-existing speak/navigateTo tests in `execution.rs` already solve the scope-wiring problem. Locate one (grep for `char:speak\|char:navigateTo` inside `mod tests` / `#[test]` in `execution.rs`) and pattern-match its setup. If none exists in `execution.rs`, write a unit test directly against the `push_intent` helper instead — the integration end will be covered by the existing `dispatch_intent` test from Task 1.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test -p story-sim --lib char_emote_enqueues_emote_intent 2>&1 | tail -20`

Expected: FAIL — `attach_emote_for_test` not defined, OR `char:emote` is nil at Lua eval.

- [ ] **Step 3: Register the `emote` closure in `run_behavior_hook`**

In `src/plugins/behavior/execution.rs`, find the block around line 1740 where `speak` is created. Add an analogous closure for emote. The exact code:

```rust
        // Emote rides on npc.emote_icon (visual icon above NPC head) — no
        // outcome, returns nil. Free-form emote_id string; client allowlists
        // which IDs resolve to actual icons.
        let cid = char_id.clone();
        let q = queue_clone.clone();
        let emote = scope.create_function(move |_, emote_id: String| {
            info!("[behavior_hook] emote queued char={} id={:?}", cid, emote_id);
            let _ = push_intent(&q, &cid, IntentKind::Emote { emote_id });
            Ok(mlua::Value::Nil)
        })?;
```

Place it immediately below the existing `speak` closure block (after the `})?;` that closes the `speak` create_function).

- [ ] **Step 4: Attach `emote` to `char_t`**

Around line 1814, in the block that does:

```rust
        char_t.set("setTarget", set_target)?;
        char_t.set("navigateTo", navigate_to)?;
        char_t.set("fleeFrom", flee_from)?;
        char_t.set("attemptHit", attempt_hit)?;
        char_t.set("clearTarget", clear_target)?;
        char_t.set("speak", speak)?;
```

Add the new line right after `speak`:

```rust
        char_t.set("speak", speak)?;
        char_t.set("emote", emote)?;
```

- [ ] **Step 5: Run the test to verify it passes (and rerun full suite to confirm no regressions)**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test -p story-sim --lib 2>&1 | tail -30`

Expected: All tests pass, including the new `char_emote_enqueues_emote_intent`.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
git add src/plugins/behavior/execution.rs
git commit -m "feat(behavior): expose char:emote(id) Lua primitive emitting npc.emote_icon"
```

---

## Task 3: story-go — regression guard that emote_icon is NOT in the DM-grab denylist

**Files:**
- Modify: `story-go/internal/sim/grab_registry_test.go` (or create if it doesn't exist)

- [ ] **Step 1: Check whether the file exists; create if not**

Run: `ls /Users/canefe/Projects/personal/story-go/internal/sim/grab_registry_test.go 2>&1`

If it doesn't exist, create it with the test below as the file's full contents. If it exists, append the test.

- [ ] **Step 2: Write the failing test (or in this case, an invariant guard)**

Append to `grab_registry_test.go`:

```go
package sim

import (
	"testing"

	"github.com/canefe/story-go/pkg/events"
)

// Emote icons must forward even for DM-grabbed NPCs. The grab gate only
// silences locomotion (go_to) and speech (npc.speak). Visual reactions
// stay alive so a grabbed NPC still looks human. This test pins that
// contract so a future denylist edit can't accidentally regress it.
func TestEmoteIconNotGatedByGrab(t *testing.T) {
	r := NewGrabRegistry()
	r.Grab("npc_42", "dm_uuid")

	msg := events.BridgeMessage{
		Type: "npc.emote_icon",
		Data: map[string]any{
			"characterId": "npc_42",
			"emoteId":     "laugh",
		},
	}
	if ShouldDropForGrab(r, msg) {
		t.Fatal("npc.emote_icon must NOT be dropped for grabbed NPCs; check gatedWhileGrabbed in grab_registry.go")
	}
}
```

> **If `NewGrabRegistry` / `Grab` aren't the actual constructor names**, inspect `grab_registry.go` first and swap to the real API. The exact import path comes from the same package the file already uses (no cross-package import needed for `sim`-internal types).

- [ ] **Step 3: Run the test to verify it passes (this is a regression guard, not red-then-green)**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./internal/sim/ -run TestEmoteIconNotGatedByGrab -v 2>&1 | tail -20`

Expected: PASS — emote_icon is not in `gatedWhileGrabbed`, so the function returns false.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
git add internal/sim/grab_registry_test.go
git commit -m "test(grab): pin that npc.emote_icon is not gated by DM-grab"
```

---

## Task 4: StoryMC — add `NPCEmoteIconIntent` event + WS transport registration

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/StoryEvent.kt` (add after the existing `NPCEmoteIntent` around line 94)
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` (two switch sites: serializeEvent around line 208; inbound deserialize around line 248)

- [ ] **Step 1: Write the failing serialization round-trip test**

Create `Story/src/test/kotlin/com/canefe/story/bridge/EmoteIconWireTest.kt`:

```kotlin
package com.canefe.story.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmoteIconWireTest {
    @Test
    fun `NPCEmoteIconIntent serializes with expected eventType and field names`() {
        val intent = NPCEmoteIconIntent(characterId = "npc_42", emoteId = "laugh")
        assertEquals("npc.emote_icon", intent.eventType)
        // Serialize through the same json/encoder serializeEvent uses.
        val payload = WebSocketTransport.serializeEvent(intent)
        assertNotNull(payload, "serializeEvent must return non-null for NPCEmoteIconIntent")
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        assertEquals("npc.emote_icon", obj["type"]!!.jsonPrimitive.content)
        val data = obj["data"]!!.jsonObject
        assertEquals("npc_42", data["characterId"]!!.jsonPrimitive.content)
        assertEquals("laugh", data["emoteId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inbound npc_emote_icon JSON deserializes to NPCEmoteIconIntent`() {
        val wire = """{"type":"npc.emote_icon","data":{"characterId":"npc_7","emoteId":"shock"}}"""
        val event = WebSocketTransport.deserializeEvent(wire)
        check(event is NPCEmoteIconIntent) { "expected NPCEmoteIconIntent, got ${event?.javaClass?.simpleName}" }
        assertEquals("npc_7", event.characterId)
        assertEquals("shock", event.emoteId)
    }
}
```

> **Note:** If `serializeEvent` / `deserializeEvent` are private or have a different signature, inspect `WebSocketTransport.kt` first. The test calls the same boundary (object-singleton or top-level) that the production code uses — adjust the call site to match. If those helpers are private, make them `internal` and add a brief comment.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.EmoteIconWireTest" 2>&1 | tail -25`

Expected: FAIL — `NPCEmoteIconIntent` class does not exist (compilation error).

- [ ] **Step 3: Add `NPCEmoteIconIntent` to `StoryEvent.kt`**

In `src/main/kotlin/com/canefe/story/bridge/StoryEvent.kt`, immediately after the existing `NPCEmoteIntent` data class (around line 99), add:

```kotlin
/**
 * Floating emoji-style icon above an NPC's head (visual reaction).
 *
 * Free-form [emoteId] on the wire; StoryClient holds the allowlist and silently
 * ignores unknown IDs. v1 stock IDs: cry, anger, pain, laugh, shock.
 *
 * Coexists with [NPCEmoteIntent] (which renders `*action*` text in chat). The
 * two are independent channels — emitting one does NOT emit the other.
 */
@Serializable
data class NPCEmoteIconIntent(
    val characterId: String,
    val emoteId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.emote_icon"
}
```

- [ ] **Step 4: Register in `WebSocketTransport.serializeEvent`**

In `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt`, find the existing serialize switch around line 208:

```kotlin
            is NPCEmoteIntent -> json.encodeToJsonElement(event)
```

Add immediately below it:

```kotlin
            is NPCEmoteIconIntent -> json.encodeToJsonElement(event)
```

- [ ] **Step 5: Register in the inbound deserialize switch**

In the same file, around line 248, find:

```kotlin
                "npc.emote" -> json.decodeFromString<NPCEmoteIntent>(data)
```

Add immediately below it:

```kotlin
                "npc.emote_icon" -> json.decodeFromString<NPCEmoteIconIntent>(data)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.EmoteIconWireTest" 2>&1 | tail -25`

Expected: PASS, both tests green.

- [ ] **Step 7: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
git add src/main/kotlin/com/canefe/story/bridge/StoryEvent.kt \
        src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt \
        src/test/kotlin/com/canefe/story/bridge/EmoteIconWireTest.kt
git commit -m "feat(bridge): NPCEmoteIconIntent event + WS serialize/deserialize registration"
```

---

## Task 5: StoryMC — `IntentExecutor.executeEmoteIconIntent` broadcasts `story:npc_emote_icon` plugin-message

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` (add handler near `executeEmoteIntent` around line 716)
- Modify: `Story/src/main/kotlin/com/canefe/story/Story.kt` (wire `eventBus.on<NPCEmoteIconIntent>` near line 730)
- Create: `Story/src/test/kotlin/com/canefe/story/bridge/IntentExecutorEmoteIconTest.kt`

- [ ] **Step 1: Write the failing handler test**

Create `IntentExecutorEmoteIconTest.kt`:

```kotlin
package com.canefe.story.bridge

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.World
import com.canefe.story.Story
import com.canefe.story.npc.StoryNPC
import kotlin.test.Test
import kotlin.test.assertTrue

class IntentExecutorEmoteIconTest {

    @Test
    fun `executeEmoteIconIntent sends story npc_emote_icon plugin message with correct bytes`() {
        val plugin = mockk<Story>(relaxed = true)
        val world = mockk<World>(relaxed = true)
        val npcEntity = mockk<LivingEntity>(relaxed = true) {
            every { entityId } returns 1234
            every { uniqueId } returns java.util.UUID(0xAA, 0xBB)
            every { location } returns Location(world, 0.0, 64.0, 0.0)
        }
        val storyNpc = mockk<StoryNPC>(relaxed = true) {
            every { entity } returns npcEntity
        }
        // Stub the registry resolution path: see IntentExecutor.resolveNPC for the
        // exact fall-through. Mock characterRegistry to return a record with name,
        // then npcRegistry.getByName to return our storyNpc.
        every { plugin.isNpcRegistryReady } returns true
        every { plugin.characterRegistry.getById("npc_42") } returns mockk(relaxed = true) {
            every { name } returns "Aldo"
        }
        every { plugin.npcRegistry.getByName("Aldo") } returns storyNpc

        val intent = NPCEmoteIconIntent(characterId = "npc_42", emoteId = "laugh")

        IntentExecutor.executeEmoteIconIntent(plugin, intent)

        // Verify a plugin-message packet was sent on the right channel.
        // The exact verify shape depends on which PacketEvents helper the executor
        // calls — adjust verify() to capture the channel + bytes. The minimum
        // assertion: the channel is "story:npc_emote_icon" and the payload bytes
        // begin with the entity id (4 bytes BE = 1234) followed by UTF "laugh".
        val captured = slot<ByteArray>()
        verify { /* the production sender invocation that sends to all players */ }
        // Then decode `captured.captured` and assert:
        //   readInt() == 1234
        //   readUTF() == "laugh"
        assertTrue(true, "Adapt verify() to the production sender; see PerceptionBroadcaster.broadcastPerceptionPopup for the pattern")
    }
}
```

> **Test harness reality check:** The Story plugin's existing tests (per CLAUDE.md / project_test_classpath_noclassdef memory) use a Paper test environment and many full-boot tests fail with NoClassDefFoundError in CI. Look at `PerceptionBroadcasterLocationTest.kt` (already in the working copy) for the actual test pattern + mocks the project uses, and mirror it. The assertion that matters is: a `story:npc_emote_icon` plugin-message with `[int entityId][UTF emoteId]` reaches `Bukkit.getOnlinePlayers()`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.IntentExecutorEmoteIconTest" 2>&1 | tail -25`

Expected: FAIL — `executeEmoteIconIntent` does not exist.

- [ ] **Step 3: Implement `executeEmoteIconIntent` in `IntentExecutor.kt`**

In `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`, immediately after `executeEmoteIntent` (which ends around line 731 — search for `executeEmoteIntent`), add:

```kotlin
    fun executeEmoteIconIntent(
        plugin: Story,
        intent: NPCEmoteIconIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Emote icon intent: character '${intent.characterId}' not found")
            return
        }
        val entity = npc.entity ?: run {
            plugin.logger.warning("Emote icon intent: character '${intent.characterId}' has no live entity")
            return
        }

        val bytes = java.io.ByteArrayOutputStream().also { baos ->
            java.io.DataOutputStream(baos).use { out ->
                out.writeInt(entity.entityId)
                out.writeUTF(intent.emoteId)
            }
        }.toByteArray()

        val packet = com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage(
            "story:npc_emote_icon",
            bytes,
        )
        for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
            try {
                com.github.retrooper.packetevents.PacketEvents.getAPI()
                    .playerManager.getUser(player).sendPacket(packet)
            } catch (_: Exception) {
                // Match PerceptionBroadcaster: swallow per-player send failures.
            }
        }
    }
```

> **Existing imports in `IntentExecutor.kt`** — if any of the FQNs above are already imported at the top of the file (`WrapperPlayServerPluginMessage`, `PacketEvents`, `Bukkit`), use the short name instead per the no-inline-FQN feedback memory. Always add the import at the top of the file, never inline an FQN that isn't already there.

- [ ] **Step 4: Wire the event handler in `Story.kt`**

In `src/main/kotlin/com/canefe/story/Story.kt`, find the existing line around line 730:

```kotlin
        eventBus.on<NPCEmoteIntent> { IntentExecutor.executeEmoteIntent(this, it) }
```

Add immediately below it:

```kotlin
        eventBus.on<NPCEmoteIconIntent> { IntentExecutor.executeEmoteIconIntent(this, it) }
```

- [ ] **Step 5: Run the handler test + the wire test from Task 4 to verify all pass**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.*" 2>&1 | tail -25`

Expected: PASS — both `EmoteIconWireTest` and `IntentExecutorEmoteIconTest` green. Pre-existing NoClassDefFoundError test failures (per the test-classpath memory) are environment, not regressions.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
git add src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt \
        src/main/kotlin/com/canefe/story/Story.kt \
        src/test/kotlin/com/canefe/story/bridge/IntentExecutorEmoteIconTest.kt
git commit -m "feat(intent): executeEmoteIconIntent broadcasts story:npc_emote_icon plugin message"
```

---

## Task 6: StoryClient — add the 5 emote PNG assets

**Files:**
- Create: `StoryClient/src/client/resources/assets/storyclient/textures/emote/cry.png`
- Create: `StoryClient/src/client/resources/assets/storyclient/textures/emote/anger.png`
- Create: `StoryClient/src/client/resources/assets/storyclient/textures/emote/pain.png`
- Create: `StoryClient/src/client/resources/assets/storyclient/textures/emote/laugh.png`
- Create: `StoryClient/src/client/resources/assets/storyclient/textures/emote/shock.png`

- [ ] **Step 1: Create the directory and place the PNGs**

Run:

```bash
mkdir -p /Users/canefe/Projects/personal/StoryClient/src/client/resources/assets/storyclient/textures/emote
ls /Users/canefe/Projects/personal/StoryClient/src/client/resources/assets/storyclient/textures/emote
```

Expected: empty dir. The five PNGs must be supplied by the user (or generated). Each should be 32×32 RGBA with transparency, recognizable at small scale: tear droplet for cry, red angry face for anger, grimace for pain, big grin for laugh, gasping wide-eye for shock.

> **Asset placeholder warning:** If the engineer reaches this task without assets in hand, create five **temporary** 32×32 solid-color placeholders (cry=blue, anger=red, pain=yellow, laugh=green, shock=purple) so the pipeline can be exercised end-to-end. Replace with real art before merge.

Run: `ls /Users/canefe/Projects/personal/StoryClient/src/client/resources/assets/storyclient/textures/emote/`

Expected: `cry.png anger.png pain.png laugh.png shock.png` all listed.

- [ ] **Step 2: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/resources/assets/storyclient/textures/emote/
git commit -m "feat(emote): add 5 stock emote icon PNGs (cry/anger/pain/laugh/shock)"
```

---

## Task 7: StoryClient — `NpcEmoteIconPayload` CustomPayload + codec

**Files:**
- Create: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayload.kt`

- [ ] **Step 1: Write the failing codec round-trip test**

Create `StoryClient/src/test/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayloadTest.kt`:

```kotlin
package com.canefe.storyclient.client.emote

import io.netty.buffer.Unpooled
import net.minecraft.network.PacketByteBuf
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcEmoteIconPayloadTest {
    @Test
    fun `codec round-trips entityId and emoteId`() {
        val original = NpcEmoteIconPayload(entityId = 1234, emoteId = "laugh")
        val buf = PacketByteBuf(Unpooled.buffer())
        NpcEmoteIconPayload.CODEC.encode(buf, original)
        val decoded = NpcEmoteIconPayload.CODEC.decode(buf)
        assertEquals(1234, decoded.entityId)
        assertEquals("laugh", decoded.emoteId)
    }
}
```

> If a `src/test/` source set doesn't exist on the client module, the codec test can also live as a `client/test` source set or run as a one-off `main()` smoke. Check the existing test layout under `StoryClient/src/` before creating the file — match what's already there.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew test --tests "com.canefe.storyclient.client.emote.NpcEmoteIconPayloadTest" 2>&1 | tail -20`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `NpcEmoteIconPayload.kt`**

Create `src/client/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayload.kt`:

```kotlin
package com.canefe.storyclient.client.emote

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client payload on `story:npc_emote_icon`.
 *
 * Wire format:
 *   int  entityId       (Bukkit entity id of the NPC; emote floats above this entity)
 *   UTF  emoteId        (free-form id; client allowlist drops unknowns)
 *
 * Stock allowlisted ids in v1: cry, anger, pain, laugh, shock. Unknown ids
 * are silently dropped by [EmoteRenderer].
 */
data class NpcEmoteIconPayload(
    val entityId: Int,
    val emoteId: String,
) : CustomPayload {

    companion object {
        val ID = CustomPayload.Id<NpcEmoteIconPayload>(Identifier.of("story", "npc_emote_icon"))

        val CODEC: PacketCodec<PacketByteBuf, NpcEmoteIconPayload> =
            PacketCodec.of(
                { value, buf ->
                    buf.writeInt(value.entityId)
                    buf.writeString(value.emoteId)
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val entityId = input.readInt()
                        val emoteId = input.readUTF()
                        NpcEmoteIconPayload(entityId, emoteId)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
```

- [ ] **Step 4: Run the codec test to verify it passes**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew test --tests "com.canefe.storyclient.client.emote.NpcEmoteIconPayloadTest" 2>&1 | tail -20`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayload.kt
# only add the test file if a test source set is wired up
[ -f src/test/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayloadTest.kt ] && \
    git add src/test/kotlin/com/canefe/storyclient/client/emote/NpcEmoteIconPayloadTest.kt
git commit -m "feat(emote): NpcEmoteIconPayload CustomPayload + codec for story:npc_emote_icon"
```

---

## Task 8: StoryClient — `EmoteRenderer` (data + scheduling, no GL yet)

**Files:**
- Create: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt`

Split the renderer into two passes: this task adds the data structures + `onEmote` enqueue + per-entity lifecycle bookkeeping. Task 9 adds the actual draw call. This keeps each task small and the data-side independently testable.

- [ ] **Step 1: Write the failing enqueue test**

Create `StoryClient/src/test/kotlin/com/canefe/storyclient/client/emote/EmoteRendererTest.kt`:

```kotlin
package com.canefe.storyclient.client.emote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class EmoteRendererTest {

    @Test
    fun `onEmote with known id installs an emote for the entity`() {
        EmoteRenderer.clearForTest()
        EmoteRenderer.onEmote(entityId = 1234, emoteId = "laugh", nowMs = 1_000L)
        val active = EmoteRenderer.activeForTest(1234)
        assertNotNull(active)
        assertEquals("laugh", active.emoteId)
        assertEquals(1_000L, active.startMs)
    }

    @Test
    fun `onEmote with unknown id is silently dropped`() {
        EmoteRenderer.clearForTest()
        EmoteRenderer.onEmote(entityId = 1234, emoteId = "not_a_real_emote", nowMs = 1_000L)
        assertNull(EmoteRenderer.activeForTest(1234))
    }

    @Test
    fun `new emote on same entity replaces the previous one`() {
        EmoteRenderer.clearForTest()
        EmoteRenderer.onEmote(1234, "laugh", 1_000L)
        EmoteRenderer.onEmote(1234, "anger", 1_200L)
        val active = EmoteRenderer.activeForTest(1234)
        assertNotNull(active)
        assertEquals("anger", active.emoteId)
        assertEquals(1_200L, active.startMs)
    }

    @Test
    fun `expired emotes are evicted by sweep`() {
        EmoteRenderer.clearForTest()
        EmoteRenderer.onEmote(1234, "laugh", 1_000L)
        EmoteRenderer.sweepForTest(nowMs = 1_000L + EmoteRenderer.TOTAL_MS + 1L)
        assertNull(EmoteRenderer.activeForTest(1234))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew test --tests "com.canefe.storyclient.client.emote.EmoteRendererTest" 2>&1 | tail -25`

Expected: FAIL — `EmoteRenderer` does not exist.

- [ ] **Step 3: Create `EmoteRenderer.kt` (data + scheduling only)**

Create `src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt`:

```kotlin
package com.canefe.storyclient.client.emote

import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders floating emote icons (laugh, cry, anger, pain, shock) above NPCs.
 *
 * Lifecycle per emote: RISE (350ms) → HOLD (900ms) → EXIT (400ms) — matches
 * the perception popup curve so the visual rhythm feels consistent. One emote
 * at a time per entity; a new emote replaces the previous one. Unknown emote
 * ids are silently dropped (forward-compat: sim can ship new ids before the
 * client knows them).
 *
 * Unlike [com.canefe.storyclient.client.perception.PerceptionPopupRenderer],
 * emotes are always visible — they do not gate on crosshair target. The point
 * of an emote is that players notice it from across the room.
 */
object EmoteRenderer {

    const val RISE_MS = 350L
    const val HOLD_MS = 900L
    const val EXIT_MS = 400L
    const val TOTAL_MS = RISE_MS + HOLD_MS + EXIT_MS

    data class Active(
        val emoteId: String,
        val texture: Identifier,
        val startMs: Long,
    )

    private val active = ConcurrentHashMap<Int, Active>()

    private val allowlist: Map<String, Identifier> = mapOf(
        "cry"   to Identifier.of("storyclient", "textures/emote/cry.png"),
        "anger" to Identifier.of("storyclient", "textures/emote/anger.png"),
        "pain"  to Identifier.of("storyclient", "textures/emote/pain.png"),
        "laugh" to Identifier.of("storyclient", "textures/emote/laugh.png"),
        "shock" to Identifier.of("storyclient", "textures/emote/shock.png"),
    )

    /** Server→client entry point. Called from the payload handler. */
    fun onEmote(entityId: Int, emoteId: String, nowMs: Long = System.currentTimeMillis()) {
        val tex = allowlist[emoteId] ?: return // unknown id — drop silently
        active[entityId] = Active(emoteId, tex, nowMs)
    }

    /** Drops emotes whose lifetime is exhausted. Call once per frame. */
    fun sweep(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - TOTAL_MS
        active.entries.removeIf { it.value.startMs < cutoff }
    }

    // ── test-only helpers ─────────────────────────────────────────────
    internal fun clearForTest() = active.clear()
    internal fun activeForTest(entityId: Int): Active? = active[entityId]
    internal fun sweepForTest(nowMs: Long) = sweep(nowMs)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew test --tests "com.canefe.storyclient.client.emote.EmoteRendererTest" 2>&1 | tail -20`

Expected: PASS — all four scheduling tests green.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt
[ -f src/test/kotlin/com/canefe/storyclient/client/emote/EmoteRendererTest.kt ] && \
    git add src/test/kotlin/com/canefe/storyclient/client/emote/EmoteRendererTest.kt
git commit -m "feat(emote): EmoteRenderer scheduling — allowlist, replace-on-collision, sweep"
```

---

## Task 9: StoryClient — `EmoteRenderer.render` draws the textured quad

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt`

No new test — rendering is GL-side and tested manually (see Task 11 smoke test). The data-side tests from Task 8 continue to pass.

- [ ] **Step 1: Add `render(context: WorldRenderContext)` to `EmoteRenderer`**

Open `EmoteRenderer.kt` and add the following imports at the top:

```kotlin
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.entity.Entity
import org.joml.Matrix4f
import kotlin.math.max
import kotlin.math.min
```

Add a `render(context: WorldRenderContext)` method below `sweep`:

```kotlin
    /**
     * Per-frame draw. For each active emote whose entity is still in the world,
     * draws a billboarded textured quad anchored at the entity's head + 0.5
     * blocks, rising another 0.5 blocks over the emote's lifetime, with alpha
     * easing in (RISE) and out (EXIT).
     */
    fun render(context: WorldRenderContext) {
        if (active.isEmpty()) return
        sweep()
        val mc = MinecraftClient.getInstance() ?: return
        val world = mc.world ?: return
        val cam = context.camera() ?: return
        val now = System.currentTimeMillis()
        val matrices = context.matrixStack() ?: return

        for ((entityId, emote) in active) {
            val entity: Entity = world.getEntityById(entityId) ?: continue
            val age = now - emote.startMs
            if (age < 0 || age > TOTAL_MS) continue

            val alpha = alphaFor(age)
            val rise = riseFor(age)
            val ex = entity.x
            val ey = entity.y + entity.height + 0.5 + rise  // head + base offset + rise
            val ez = entity.z

            matrices.push()
            matrices.translate(ex - cam.pos.x, ey - cam.pos.y, ez - cam.pos.z)
            matrices.multiply(cam.rotation)
            val m: Matrix4f = matrices.peek().positionMatrix

            // Half-size of the quad in world units (0.4 ≈ ~12px at default GUI scale).
            val half = 0.4f

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShader(GameRenderer::getPositionTexProgram)
            RenderSystem.setShaderTexture(0, emote.texture)
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha)

            val buf = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE,
            )
            buf.vertex(m, -half, -half, 0f).texture(0f, 1f)
            buf.vertex(m,  half, -half, 0f).texture(1f, 1f)
            buf.vertex(m,  half,  half, 0f).texture(1f, 0f)
            buf.vertex(m, -half,  half, 0f).texture(0f, 0f)
            BufferRenderer.drawWithGlobalProgram(buf.end())

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            RenderSystem.disableBlend()
            matrices.pop()
        }
    }

    private fun alphaFor(ageMs: Long): Float = when {
        ageMs < RISE_MS -> ageMs.toFloat() / RISE_MS.toFloat()
        ageMs < RISE_MS + HOLD_MS -> 1f
        else -> {
            val t = (ageMs - RISE_MS - HOLD_MS).toFloat() / EXIT_MS.toFloat()
            max(0f, 1f - t)
        }
    }

    /** Vertical lift in world units; total 0.5 blocks over [TOTAL_MS]. */
    private fun riseFor(ageMs: Long): Double {
        val t = min(1.0, ageMs.toDouble() / TOTAL_MS.toDouble())
        return 0.5 * t
    }
```

> **Look at `PerceptionPopupRenderer.kt`** before finalizing this code. Steal the exact `RenderSystem` / `Tessellator` calls it uses (the project may pin specific Minecraft 1.21.x API versions, and PerceptionPopupRenderer already works). Match the lift/timing/blend conventions to that renderer line-for-line where possible.

- [ ] **Step 2: Compile to make sure it builds**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew compileKotlin compileTestKotlin 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Rerun the data-side tests from Task 8 to confirm nothing broke**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew test --tests "com.canefe.storyclient.client.emote.EmoteRendererTest" 2>&1 | tail -20`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
git add src/client/kotlin/com/canefe/storyclient/client/emote/EmoteRenderer.kt
git commit -m "feat(emote): EmoteRenderer.render draws billboarded icon w/ rise + alpha fade"
```

---

## Task 10: StoryClient — register the payload codec + WorldRender callback

**Files:**
- Modify: the Fabric client entry point (look for `ClientModInitializer` / `onInitializeClient` — likely `StoryClient.kt` or a `*ClientInit*.kt`)

- [ ] **Step 1: Locate the registration site**

Run: `cd /Users/canefe/Projects/personal/StoryClient && grep -rn "NpcPerceptionPayload\|PayloadTypeRegistry\|ClientPlayNetworking.registerGlobalReceiver\|WorldRenderEvents" src/client/kotlin 2>&1 | head -20`

This shows you where `NpcPerceptionPayload` is registered (codec + receiver) and where `PerceptionPopupRenderer.render` is hooked into `WorldRenderEvents.AFTER_ENTITIES`. Mirror both sites for emote.

- [ ] **Step 2: Register the payload type and receiver next to the perception ones**

At the perception payload's registration site, add immediately after it:

```kotlin
PayloadTypeRegistry.playS2C().register(NpcEmoteIconPayload.ID, NpcEmoteIconPayload.CODEC)

ClientPlayNetworking.registerGlobalReceiver(NpcEmoteIconPayload.ID) { payload, _ ->
    EmoteRenderer.onEmote(payload.entityId, payload.emoteId)
}
```

Add the imports at the top of the file:

```kotlin
import com.canefe.storyclient.client.emote.EmoteRenderer
import com.canefe.storyclient.client.emote.NpcEmoteIconPayload
```

- [ ] **Step 3: Register the WorldRender callback next to the perception one**

At the site where `PerceptionPopupRenderer.render` is hooked (search for `PerceptionPopupRenderer.render` — likely a `WorldRenderEvents.AFTER_ENTITIES.register { ... }` lambda), add an analogous registration. Either extend the existing lambda body:

```kotlin
WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
    PerceptionPopupRenderer.render(ctx)
    EmoteRenderer.render(ctx)
}
```

…or register a new top-level handler — whichever matches the existing pattern.

- [ ] **Step 4: Compile**

Run: `cd /Users/canefe/Projects/personal/StoryClient && ./gradlew compileClientKotlin 2>&1 | tail -15`

(Use whichever compile task matches the client source set — `compileKotlin` may work as well.)

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient
# git add the exact file you modified
git add <client init file path>
git commit -m "feat(emote): register NpcEmoteIconPayload codec + receiver + WorldRender hook"
```

---

## Task 11: End-to-end manual smoke test

No new code. Confirm the whole pipeline lights up.

- [ ] **Step 1: Start the stack**

Run all five processes per the standard local-dev recipe:

```bash
# Terminal A — NATS
nats-server

# Terminal B — story-sim
cd /Users/canefe/Projects/personal/story-sim && cargo run

# Terminal C — story-go
cd /Users/canefe/Projects/personal/story-go && go run ./cmd/story-go

# Terminal D — Minecraft server (Paper, with the Story plugin built and dropped in)
cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew runServer

# Connect with Minecraft client that has StoryClient installed
```

If your local-dev workflow differs, follow the existing project run script.

- [ ] **Step 2: Trigger an emote from sim**

In a sim REPL / behavior file, run a Lua snippet that calls `char:emote("laugh")` on a spawned NPC. The fastest path: add a temporary line to any behavior's `OnStart` or `OnTick` hook for one NPC, e.g.

```lua
char:emote("laugh")
char:complete_step()
```

Or wire it into a one-shot debug command if one exists.

- [ ] **Step 3: Verify visually**

Expected behavior in the Minecraft client:
- Within ~1 tick of the Lua call, a `laugh.png` icon appears above the NPC's head.
- It rises ~0.5 blocks over ~1.65 seconds.
- It fades to transparent and disappears.
- Repeating the call with `"anger"`, `"pain"`, `"cry"`, `"shock"` shows each respective icon.
- Calling `char:emote("not_a_real_id")` shows nothing (silent drop).
- Two rapid calls on the same NPC: only the second's icon is visible (replace, not stack).

If any step fails, the diagnostic order: (a) check NATS subject in `nats sub story-sim.events` for the `npc.emote_icon` envelope; (b) check Story plugin log for "Emote icon intent" warnings; (c) check StoryClient logs at DEBUG for unknown-id drops; (d) confirm PNG files exist in the assets dir and were bundled into the built jar.

- [ ] **Step 4: Commit any incidental docs/debug-script changes (if any)**

If you added a temporary Lua trigger for testing, **revert it** before committing. There should be no production code changes from this task.

---

## Self-Review

**Spec coverage:**
- §Architecture: Task 1 (sim intent) + Task 2 (Lua hook) + (zero story-go) + Task 4+5 (StoryMC event + handler) + Task 7+8+9+10 (client payload + renderer + registration). ✓
- §Producer Authority (sim-only v1): Task 2 is the only producer added. ✓
- §Components: every component in the spec maps to a task. ✓
- §Error Handling (unknown id, NPC not resolvable, DM-grab, range, rapid-fire, despawn, bridge disabled): Task 5 handles NPC-not-resolvable + entity-null. Task 8 handles unknown id (silent) + rapid-fire (replace). Task 3 pins the DM-grab contract. Range/bridge-disabled inherit from the existing plugin behavior — explicit task not needed. Despawn is handled by `world.getEntityById(id) ?: continue` in Task 9. ✓
- §Testing: sim unit (Task 1+2), go regression guard (Task 3), MC wire round-trip + handler (Task 4+5), client codec + scheduling (Task 7+8), manual smoke (Task 11). ✓
- §Out of scope: not addressed by tasks (correct — out of scope). ✓
- §Files Touched: matches the task list 1:1, plus the spec's optional story-proto note is resolved as "skip" in Task 0. ✓

**Placeholder scan:**
- No TBD / TODO / "implement later".
- Two soft spots flagged for the engineer to verify at task time (not skipped):
  - Task 2's test harness — the file may or may not have a pre-existing `mod tests` with a Lua-scope helper. Plan instructs the engineer to mirror the existing speak/navigateTo test pattern.
  - Task 5 / Task 10's exact mock and registration sites — plan instructs the engineer to mirror `PerceptionBroadcaster` / `NpcPerceptionPayload` registration verbatim.
- These are necessary "look at the precedent" instructions, not placeholders, because both precedents exist in the working tree and are the canonical patterns.

**Type consistency check:**
- `IntentKind::Emote { emote_id }` (Task 1) ↔ `char:emote(emote_id)` (Task 2) ↔ wire `emoteId` (Task 1 to_data) ↔ `NPCEmoteIconIntent.emoteId` (Task 4) ↔ `NpcEmoteIconPayload.emoteId` (Task 7) ↔ `EmoteRenderer.onEmote(emoteId)` (Task 8). All consistent. ✓
- `eventType = "npc.emote_icon"` matches the envelope type from the sim Descriptor. ✓
- Plugin-message channel `story:npc_emote_icon` matches the CustomPayload Identifier `("story", "npc_emote_icon")`. ✓
- Wire bytes: `int entityId` + `UTF emoteId` — server (Task 5) and client (Task 7) match. ✓

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-27-emote-icons-pipeline.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task with two-stage review (good for a plan that crosses 4 repos and 11 tasks).

**2. Inline Execution** — execute the tasks here in this session with checkpoints.

**Which approach?**
