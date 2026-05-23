# Sim Action & Item-Transfer Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make sim NPC actions and item exchanges legible in-world via a client-rendered fading action label (reusing the perception-popup path) and client-rendered item-transfer holograms (a new channel).

**Architecture:** story-sim emits `actionId`/`actionLabel` on the existing `npc.state` and a new `npc.item_transfer` event at the single transfer chokepoint; story-go relays both verbatim; StoryMC change-diffs labels into `PerceptionBroadcaster.sendActionPopup` (PopupType.ACTION) and resolves transfers through a new `ItemMapService` into a `story:item_transfer` packet; StoryClient adds a `PopupType.ACTION` style and an `ItemHologramRenderer` that arcs an `ItemStack` giver→receiver.

**Tech Stack:** Rust/Bevy + mlua (story-sim), Go (story-go relay), Kotlin/Paper + PacketEvents + kotlinx.serialization (StoryMC), Kotlin/Fabric + Fabric networking (StoryClient). Commits via `jj` (each repo already on a fresh WIP working-copy commit; use `jj commit -m "..."` to finalize a step and open a new WC, or `jj describe` + `jj new` — the plan uses `jj commit`).

**Spec:** `Story/docs/superpowers/specs/2026-05-23-sim-action-item-visualization-design.md`

---

## File Structure

**story-sim (Rust):**
- Modify `src/resources/mod.rs` — add `label: Option<String>` to `BehaviorDef`.
- Modify `src/plugins/definition_loader/behaviors.rs` — parse `Label`.
- Modify `src/plugins/entity_state_broadcast.rs` — resolve + emit `actionId`/`actionLabel`.
- Modify `src/plugins/lua_world_api.rs` — emit `npc.item_transfer` from `process_transfer_item_queue`; add `reason` to `TransferItemRequest` (struct lives here ~line 320).
- Modify `src/plugins/behavior/trade/lifecycle.rs` — set `reason="trade"` on pushed requests.
- Modify `packs/BaseGame/lua/defs/behaviors/*.lua` — author `BEHAVIOR.Label` (+ dynamic label in `head_to_known_location.lua`).

**story-go (Go):**
- Modify `pkg/events/events.go` — add `NpcItemTransfer` constant (hygiene only).

**StoryMC (Kotlin):**
- Create `src/main/kotlin/com/canefe/story/config/ItemMapService.kt` + `ItemRenderSpec`.
- Create `src/main/resources/items.yml` (default shipped config).
- Create `src/main/kotlin/com/canefe/story/bridge/ItemTransferPacketBridge.kt`.
- Modify `src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt` — `ACTION(5)` + `sendActionPopup`.
- Modify `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` — extend `NpcStateIntent`, add `NpcItemTransferIntent`.
- Modify `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — decode `npc.item_transfer`.
- Modify `src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` — change-diff labels; `executeItemTransferIntent`.
- Modify `src/main/kotlin/com/canefe/story/Story.kt` — register intent + construct `ItemMapService`.
- Modify `src/main/kotlin/com/canefe/story/config/ConfigService.kt` — load/reload items.yml.
- Tests under `src/test/kotlin/com/canefe/story/...`.

**StoryClient (Kotlin):**
- Create `src/client/kotlin/com/canefe/storyclient/client/sim/ItemTransferPayload.kt`.
- Create `src/client/kotlin/com/canefe/storyclient/client/sim/ItemHologramRenderer.kt`.
- Modify `src/client/kotlin/com/canefe/storyclient/client/perception/NpcPerceptionPayload.kt` — `ACTION(5)`.
- Modify `src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt` — ACTION style + empty-clear.
- Modify `src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` — register payload + render hook.
- Tests under `src/test/kotlin/...` (codec round-trip).

**Build/test commands:**
- story-sim: `cargo build` / `cargo test <name>` (cwd `story-sim`).
- StoryMC: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` / `./gradlew test --tests '<FQN>'` (cwd `Story`).
- StoryClient: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileTestKotlin` / `./gradlew test --tests '<FQN>'` (cwd `StoryClient`).
- story-go: `go build ./...` (cwd `story-go`).

---

## Phase 1 — StoryMC item map (no wire)

### Task 1: ItemRenderSpec + ItemMapService with lookup & fallback

**Files:**
- Create: `Story/src/main/kotlin/com/canefe/story/config/ItemMapService.kt`
- Create: `Story/src/main/resources/items.yml`
- Test: `Story/src/test/kotlin/com/canefe/story/config/ItemMapServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.canefe.story.config

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ItemMapServiceTest {
    private fun service(): ItemMapService {
        val svc = ItemMapService()
        svc.loadFromMap(
            mapOf(
                "items" to mapOf(
                    "bread" to mapOf("material" to "BREAD"),
                    "coin" to mapOf("material" to "GOLD_NUGGET"),
                    "potion" to mapOf("material" to "POTION", "customModelData" to 1001),
                    "bogus" to mapOf("material" to "NOT_A_REAL_MATERIAL"),
                ),
                "default" to mapOf("material" to "PAPER"),
            ),
        )
        return svc
    }

    @Test
    fun `known item resolves to its material`() {
        assertEquals(Material.BREAD, service().renderSpecFor("bread").material)
        assertEquals(Material.GOLD_NUGGET, service().renderSpecFor("coin").material)
    }

    @Test
    fun `customModelData is read when present and null otherwise`() {
        assertEquals(1001, service().renderSpecFor("potion").customModelData)
        assertNull(service().renderSpecFor("bread").customModelData)
    }

    @Test
    fun `unknown item falls back to default`() {
        assertEquals(Material.PAPER, service().renderSpecFor("unobtainium").material)
    }

    @Test
    fun `invalid material name falls back to default material`() {
        assertEquals(Material.PAPER, service().renderSpecFor("bogus").material)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.config.ItemMapServiceTest'`
Expected: FAIL — `ItemMapService` / `loadFromMap` / `renderSpecFor` unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.canefe.story.config

import org.bukkit.Material
import java.io.File

data class ItemRenderSpec(val material: Material, val customModelData: Int?)

/**
 * Maps sim item ids (e.g. "bread", "coin") to a Minecraft rendering spec.
 * Backed by items.yml. Unknown ids and bad material names fall back to `default`.
 */
class ItemMapService {
    @Volatile private var specs: Map<String, ItemRenderSpec> = emptyMap()
    @Volatile private var default: ItemRenderSpec = ItemRenderSpec(Material.PAPER, null)

    fun renderSpecFor(simId: String): ItemRenderSpec = specs[simId.lowercase()] ?: default

    /** Parse a snakeyaml-shaped map into specs. Exposed for tests and the file loader. */
    @Suppress("UNCHECKED_CAST")
    fun loadFromMap(root: Map<String, Any?>) {
        fun parseSpec(node: Any?, fallback: ItemRenderSpec): ItemRenderSpec {
            val m = node as? Map<String, Any?> ?: return fallback
            val matName = (m["material"] as? String)?.uppercase()
            val mat = matName?.let { runCatching { Material.valueOf(it) }.getOrNull() } ?: fallback.material
            val cmd = (m["customModelData"] as? Number)?.toInt()
            return ItemRenderSpec(mat, cmd)
        }
        default = parseSpec(root["default"], ItemRenderSpec(Material.PAPER, null))
        val items = root["items"] as? Map<String, Any?> ?: emptyMap()
        specs = items.entries.associate { (k, v) -> k.lowercase() to parseSpec(v, default) }
    }

    /** Load from items.yml in the plugin data folder. */
    fun loadFromFile(file: File) {
        if (!file.exists()) {
            specs = emptyMap(); default = ItemRenderSpec(Material.PAPER, null); return
        }
        val yaml = org.yaml.snakeyaml.Yaml()
        val root = file.inputStream().use { yaml.load<Map<String, Any?>>(it) } ?: emptyMap()
        loadFromMap(root)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.config.ItemMapServiceTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Create the default items.yml resource**

Create `Story/src/main/resources/items.yml`:
```yaml
# sim item id -> Minecraft rendering. Unknown ids fall back to `default`.
items:
  bread:  { material: BREAD }
  coin:   { material: GOLD_NUGGET }
  wheat:  { material: WHEAT }
  apple:  { material: APPLE }
default: { material: PAPER }
```

- [ ] **Step 6: Commit**

```bash
cd Story && jj commit -m "feat(items): ItemMapService + items.yml for sim-item -> material mapping"
```

### Task 2: Wire ItemMapService into ConfigService + Story

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/config/ConfigService.kt` (reload ~line 144)
- Modify: `Story/src/main/kotlin/com/canefe/story/Story.kt` (plugin fields / onEnable)

- [ ] **Step 1: Add the service to the plugin**

In `Story.kt`, add a lateinit/initialized field next to other services (search for where `configService` / `promptService` are declared) and construct it during enable, before `configService.reload()` runs the first time:
```kotlin
val itemMapService = com.canefe.story.config.ItemMapService()
```
(If services are constructed lazily/by ConfigService, place it where `promptService` is created. Match the surrounding pattern — a `val` property on the `Story` class.)

- [ ] **Step 2: Load items.yml on reload**

In `ConfigService.reload()` (~line 144, inside the `try` block alongside `plugin.promptService.reload()` etc.), add:
```kotlin
plugin.saveResource("items.yml", /* replace = */ false)
plugin.itemMapService.loadFromFile(java.io.File(plugin.dataFolder, "items.yml"))
```
`saveResource("items.yml", false)` copies the bundled default into the data folder on first run without overwriting an admin's edits (standard Bukkit pattern, same as config.yml).

- [ ] **Step 3: Compile**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd Story && jj commit -m "feat(items): load items.yml in ConfigService.reload, expose plugin.itemMapService"
```

---

## Phase 2 — sim emits npc.item_transfer

### Task 3: Add `reason` to TransferItemRequest

**Files:**
- Modify: `story-sim/src/plugins/lua_world_api.rs` (`struct TransferItemRequest` ~line 320)
- Modify: `story-sim/src/plugins/behavior/trade/lifecycle.rs` (push sites ~lines 212–235)

- [ ] **Step 1: Add the field with a default-friendly construction**

In `lua_world_api.rs`, extend the struct:
```rust
pub struct TransferItemRequest {
    pub from_entity_name: String,
    pub from_character_id: Option<String>,
    pub to_entity_name: String,
    pub to_character_id: Option<String>,
    pub item_id: String,
    pub qty: u32,
    /// Why the transfer happened, for frontend styling: "trade" | "gift" | "give".
    pub reason: String,
}
```

- [ ] **Step 2: Set reason at every push site**

In `lifecycle.rs::trade_settle_system`, both push sites (currency buyer→vendor and goods vendor→buyer) add `reason: "trade".to_string(),` to the `TransferItemRequest { ... }` literal.

Then `grep -rn "TransferItemRequest {" story-sim/src` and add `reason: "give".to_string(),` to every other construction site (e.g. `char:give_item_to` in `execution.rs` ~1611 — use `"gift"` there if it represents a gift, else `"give"`). Every literal must compile.

- [ ] **Step 3: Compile**

Run: `cd story-sim && cargo build`
Expected: compiles (fix any literal missing `reason`).

- [ ] **Step 4: Commit**

```bash
cd story-sim && jj commit -m "feat(sim): add reason field to TransferItemRequest (trade/gift/give)"
```

### Task 4: Emit npc.item_transfer from the transfer chokepoint

**Files:**
- Modify: `story-sim/src/plugins/lua_world_api.rs` (`process_transfer_item_queue` ~line 2935; success path ~line 3023)
- Test: `story-sim/tests/item_transfer_event.rs` (new integration test) OR a unit test in-module — see Step 1.

- [ ] **Step 1: Write a failing test for the payload shape**

Add a unit test that builds the JSON the same way the emit code will and asserts shape. Place at the bottom of `lua_world_api.rs` inside `#[cfg(test)] mod tests` (create the module if absent):
```rust
#[cfg(test)]
mod item_transfer_tests {
    use serde_json::json;

    // Mirror of the helper the system uses, kept here so the shape is asserted.
    fn build_item_transfer(from: &str, to: &str, item: &str, qty: u32, reason: &str) -> String {
        json!({
            "type": "npc.item_transfer",
            "data": {
                "fromCharacterId": from,
                "toCharacterId": to,
                "item": item,
                "qty": qty,
                "reason": reason,
            },
            "timestamp": 0,
            "source": "story-sim",
        })
        .to_string()
    }

    #[test]
    fn item_transfer_payload_has_expected_shape() {
        let s = build_item_transfer("char_a", "char_b", "bread", 3, "trade");
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["type"], "npc.item_transfer");
        assert_eq!(v["data"]["fromCharacterId"], "char_a");
        assert_eq!(v["data"]["toCharacterId"], "char_b");
        assert_eq!(v["data"]["item"], "bread");
        assert_eq!(v["data"]["qty"], 3);
        assert_eq!(v["data"]["reason"], "trade");
        assert_eq!(v["source"], "story-sim");
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or is absent)**

Run: `cd story-sim && cargo test item_transfer_payload_has_expected_shape`
Expected: FAIL/compile error until the test module + (next step) the real emit fn exist. If it passes immediately it's because the helper is self-contained — that's acceptable: this test pins the shape; Step 3 makes the real system use the same builder.

- [ ] **Step 3: Add MessageWriter param + emit on success**

Promote the test helper to a real module-level fn so the system and test share it:
```rust
pub(crate) fn build_item_transfer_payload(
    from_cid: &str, to_cid: &str, item: &str, qty: u32, reason: &str,
) -> String {
    serde_json::json!({
        "type": "npc.item_transfer",
        "data": {
            "fromCharacterId": from_cid,
            "toCharacterId": to_cid,
            "item": item,
            "qty": qty,
            "reason": reason,
        },
        "timestamp": 0,
        "source": "story-sim",
    }).to_string()
}
```
Update the test to call `super::build_item_transfer_payload(...)` instead of its local copy.

Add `MessageWriter<BridgePubSubOut>` to the system signature (import the same `BridgePubSubOut` + `SUBJECT` that `entity_state_broadcast.rs` uses):
```rust
pub(crate) fn process_transfer_item_queue(
    queues: Res<LuaCommandQueues>,
    item_registry: Res<crate::resources::ItemRegistry>,
    mut query: Query<( Entity, &crate::components::name::Name,
        &mut crate::components::inventory::Inventory,
        Option<&crate::components::external_control::ExternalId>, )>,
    mut out: MessageWriter<crate::plugins::event_bridge::BridgePubSubOut>,
) {
```
(Confirm the exact path/type of `BridgePubSubOut` + the `SUBJECT` const by matching the `use` lines in `entity_state_broadcast.rs`.)

On the **success** branch (after both inventories are mutated, near the `info!("TransferItem: ...")` at ~3023), resolve both character ids from the snapshotted `endpoints` (each entry is `(Entity, name, Option<cid>)`) — the request already prefers `from_character_id`/`to_character_id`, else the resolved entity's `ExternalId`. Emit only when **both** sides have a character id and `qty > 0` and `from != to`:
```rust
if let (Some(from_cid), Some(to_cid)) = (resolved_from_cid.as_ref(), resolved_to_cid.as_ref()) {
    if request.qty > 0 && from_cid != to_cid {
        out.write(crate::plugins::event_bridge::BridgePubSubOut {
            channel: /* SUBJECT, e.g. */ "story-sim.events".to_string(),
            payload: build_item_transfer_payload(
                from_cid, to_cid, &request.item_id, request.qty, &request.reason,
            ),
        });
    }
}
```
Use the actual `SUBJECT` constant rather than a literal if one is in scope (match `entity_state_broadcast.rs`).

- [ ] **Step 4: Run the shape test + build**

Run: `cd story-sim && cargo test item_transfer_payload_has_expected_shape && cargo build`
Expected: test PASS, build SUCCESS.

- [ ] **Step 5: Commit**

```bash
cd story-sim && jj commit -m "feat(sim): emit npc.item_transfer on successful inventory transfer"
```

### Task 5: story-go event constant (hygiene)

**Files:**
- Modify: `story-go/pkg/events/events.go` (~line 19 const block)

- [ ] **Step 1: Add the constant**

In the sim event const block add (do NOT add it to `IsSimEvent` in `internal/sim/events.go` — that would route it into `Handle()` which has no case and would drop it; leaving it out lets it fall through to `hub.Broadcast`):
```go
NpcItemTransfer = "npc.item_transfer"
```

- [ ] **Step 2: Build**

Run: `cd story-go && go build ./...`
Expected: success.

- [ ] **Step 3: Commit**

```bash
cd story-go && jj commit -m "chore(events): add NpcItemTransfer constant (relayed via passthrough)"
```

---

## Phase 3 — StoryMC item-transfer handler → packet

### Task 6: NpcItemTransferIntent DTO + wire decode (TDD)

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt`
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` (deserialize ~line 205)
- Test: `Story/src/test/kotlin/com/canefe/story/bridge/ItemTransferWireTest.kt`

- [ ] **Step 1: Write the failing test**

Mirror the existing `AuthoringIntentWireTest` style (read it first to match how it invokes deserialize — likely a small helper or reflection on the private fn; if `deserializeEvent` is private, test via the public path the existing test uses).
```kotlin
package com.canefe.story.bridge

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemTransferWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `npc_item_transfer json decodes into intent`() {
        val payload = """
            {"fromCharacterId":"a","toCharacterId":"b","item":"bread","qty":3,"reason":"trade"}
        """.trimIndent()
        val intent = json.decodeFromString<NpcItemTransferIntent>(payload)
        assertEquals("a", intent.fromCharacterId)
        assertEquals("b", intent.toCharacterId)
        assertEquals("bread", intent.item)
        assertEquals(3, intent.qty)
        assertEquals("trade", intent.reason)
        assertEquals("npc.item_transfer", intent.eventType)
    }

    @Test
    fun `reason defaults to give when absent`() {
        val intent = json.decodeFromString<NpcItemTransferIntent>(
            """{"fromCharacterId":"a","toCharacterId":"b","item":"coin","qty":1}""",
        )
        assertEquals("give", intent.reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ItemTransferWireTest'`
Expected: FAIL — `NpcItemTransferIntent` unresolved.

- [ ] **Step 3: Add the DTO**

In `DomainEvents.kt` (next to `NpcStateIntent`):
```kotlin
@Serializable
data class NpcItemTransferIntent(
    val fromCharacterId: String,
    val toCharacterId: String,
    val item: String,
    val qty: Int,
    val reason: String = "give",
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.item_transfer"
}
```

- [ ] **Step 4: Add the decode branch**

In `WebSocketTransport.kt::deserializeEvent` `when (message.type)`, add:
```kotlin
"npc.item_transfer" -> json.decodeFromString<NpcItemTransferIntent>(data)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ItemTransferWireTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
cd Story && jj commit -m "feat(bridge): NpcItemTransferIntent DTO + npc.item_transfer decode"
```

### Task 7: ItemTransferPacketBridge (encode + send)

**Files:**
- Create: `Story/src/main/kotlin/com/canefe/story/bridge/ItemTransferPacketBridge.kt`
- Test: `Story/src/test/kotlin/com/canefe/story/bridge/ItemTransferEncodeTest.kt`

- [ ] **Step 1: Write the failing encode test**

The encoder must be a pure function over primitives (no Bukkit) so it's unit-testable:
```kotlin
package com.canefe.story.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ItemTransferEncodeTest {
    @Test
    fun `encode round-trips through the documented layout`() {
        val bytes = ItemTransferPacketBridge.encode(
            fromEntityId = 11, toEntityId = 22,
            materialId = "minecraft:bread", customModelData = -1,
            qty = 3, reasonOrdinal = 0,
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(11, i.readInt())
            assertEquals(22, i.readInt())
            assertEquals("minecraft:bread", i.readUTF())
            assertEquals(-1, i.readInt())
            assertEquals(3, i.readShort().toInt())
            assertEquals(0, i.readByte().toInt())
        }
    }

    @Test
    fun `reasonOrdinal maps trade gift give`() {
        assertEquals(0, ItemTransferPacketBridge.reasonOrdinal("trade"))
        assertEquals(1, ItemTransferPacketBridge.reasonOrdinal("gift"))
        assertEquals(2, ItemTransferPacketBridge.reasonOrdinal("give"))
        assertEquals(2, ItemTransferPacketBridge.reasonOrdinal("unknown"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ItemTransferEncodeTest'`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement the bridge**

```kotlin
package com.canefe.story.bridge

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import com.canefe.story.Story
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ItemTransferPacketBridge(private val plugin: Story) {
    fun send(
        audience: Collection<Player>,
        fromEntityId: Int, toEntityId: Int,
        materialId: String, customModelData: Int, qty: Int, reason: String,
    ) {
        val bytes = encode(fromEntityId, toEntityId, materialId, customModelData, qty, reasonOrdinal(reason))
        val packet = WrapperPlayServerPluginMessage(CHANNEL, bytes)
        for (player in audience) {
            try {
                PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
            } catch (e: Exception) {
                plugin.logger.warning("[ItemTransfer] send failed for ${player.name}: ${e.message}")
            }
        }
    }

    companion object {
        const val CHANNEL = "story:item_transfer"

        fun reasonOrdinal(reason: String): Int = when (reason.lowercase()) {
            "trade" -> 0; "gift" -> 1; else -> 2 // "give" / unknown
        }

        fun encode(
            fromEntityId: Int, toEntityId: Int,
            materialId: String, customModelData: Int, qty: Int, reasonOrdinal: Int,
        ): ByteArray {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.writeInt(fromEntityId)
                out.writeInt(toEntityId)
                out.writeUTF(materialId)
                out.writeInt(customModelData)
                out.writeShort(qty)
                out.writeByte(reasonOrdinal)
            }
            return baos.toByteArray()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ItemTransferEncodeTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd Story && jj commit -m "feat(bridge): ItemTransferPacketBridge encode + story:item_transfer send"
```

### Task 8: executeItemTransferIntent + registration

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`
- Modify: `Story/src/main/kotlin/com/canefe/story/Story.kt` (construct bridge + register intent ~line 724)

- [ ] **Step 1: Construct the bridge on the plugin**

In `Story.kt`, alongside `itemMapService`:
```kotlin
val itemTransferBridge by lazy { com.canefe.story.bridge.ItemTransferPacketBridge(this) }
```
(or a `lateinit var` initialized in onEnable — match the surrounding service style.)

- [ ] **Step 2: Add the handler**

In `IntentExecutor.kt` (near `executeNpcStateIntent`):
```kotlin
fun executeItemTransferIntent(plugin: Story, intent: NpcItemTransferIntent) {
    if (!plugin.isNpcRegistryReady) return
    val from = resolveNPC(plugin, intent.fromCharacterId)?.entity ?: return
    val to = resolveNPC(plugin, intent.toCharacterId)?.entity ?: return
    val spec = plugin.itemMapService.renderSpecFor(intent.item)
    val materialId = spec.material.key.toString() // "minecraft:bread"
    val cmd = spec.customModelData ?: -1

    val renderDist = 64.0
    val audience = org.bukkit.Bukkit.getOnlinePlayers().filter { p ->
        (p.world == from.world && p.location.distanceSquared(from.location) <= renderDist * renderDist) ||
        (p.world == to.world && p.location.distanceSquared(to.location) <= renderDist * renderDist)
    }
    if (audience.isEmpty()) return
    plugin.itemTransferBridge.send(
        audience, from.entityId, to.entityId, materialId, cmd, intent.qty, intent.reason,
    )
}
```
(`Material.key` gives the namespaced id on modern Paper. If `key` is unavailable in this API version, use `"minecraft:" + spec.material.name.lowercase()` — verify against the Paper API by compiling.)

- [ ] **Step 3: Register the intent**

In `Story.kt::initializeEventBus()` (~line 724, with the other `eventBus.on<...>` lines):
```kotlin
eventBus.on<NpcItemTransferIntent> { IntentExecutor.executeItemTransferIntent(this, it) }
```

- [ ] **Step 4: Compile**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (resolve `Material.key` vs name fallback here).

- [ ] **Step 5: Commit**

```bash
cd Story && jj commit -m "feat(bridge): handle npc.item_transfer -> story:item_transfer packet to nearby players"
```

---

## Phase 4 — StoryClient item hologram

### Task 9: ItemTransferPayload + codec (TDD)

**Files:**
- Create: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/sim/ItemTransferPayload.kt`
- Test: `StoryClient/src/test/kotlin/com/canefe/storyclient/client/sim/ItemTransferCodecTest.kt`
  (If StoryClient has no JVM test source set, place a decode round-trip test in the existing test location; else verify via a `main`-style harness. Check `StoryClient/build.gradle` for a `test` task first — if absent, skip the test file and rely on Step 4 compile + manual verification, noting it.)

- [ ] **Step 1: Write the failing decode test**

```kotlin
package com.canefe.storyclient.client.sim

import net.minecraft.network.PacketByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ItemTransferCodecTest {
    @Test
    fun `decodes the documented layout`() {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { o ->
            o.writeInt(11); o.writeInt(22); o.writeUTF("minecraft:bread")
            o.writeInt(-1); o.writeShort(3); o.writeByte(0)
        }
        val buf = PacketByteBuf(Unpooled.wrappedBuffer(baos.toByteArray()))
        val p = ItemTransferPayload.CODEC.decode(buf)
        assertEquals(11, p.fromEntityId)
        assertEquals(22, p.toEntityId)
        assertEquals("minecraft:bread", p.materialId)
        assertEquals(-1, p.customModelData)
        assertEquals(3, p.qty)
        assertEquals(0, p.reasonOrdinal)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd StoryClient && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.storyclient.client.sim.ItemTransferCodecTest'`
Expected: FAIL — unresolved (or "no test task" — see file note above; if no test task, skip to Step 3 and gate on compile).

- [ ] **Step 3: Implement the payload (mirror HitOutcomePayload.kt)**

```kotlin
package com.canefe.storyclient.client.sim

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

data class ItemTransferPayload(
    val fromEntityId: Int,
    val toEntityId: Int,
    val materialId: String,
    val customModelData: Int,
    val qty: Int,
    val reasonOrdinal: Int,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ItemTransferPayload>(Identifier.of("story", "item_transfer"))

        val CODEC: PacketCodec<PacketByteBuf, ItemTransferPayload> =
            PacketCodec.of(
                { _, _ -> throw UnsupportedOperationException("ItemTransferPayload is server-bound only") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { i ->
                        ItemTransferPayload(
                            fromEntityId = i.readInt(),
                            toEntityId = i.readInt(),
                            materialId = i.readUTF(),
                            customModelData = i.readInt(),
                            qty = i.readShort().toInt(),
                            reasonOrdinal = i.readByte().toInt(),
                        )
                    }
                },
            )
    }
}
```
(Match `HitOutcomePayload.kt`'s exact `CustomPayload`/`getId` shape — if it omits an explicit `getId` override, follow that. Adjust to the version's API.)

- [ ] **Step 4: Run test (or compile) to verify**

Run: `cd StoryClient && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileTestKotlin` then the `--tests` command if a test task exists.
Expected: PASS / compiles.

- [ ] **Step 5: Commit**

```bash
cd StoryClient && jj commit -m "feat(sim): ItemTransferPayload + codec for story:item_transfer"
```

### Task 10: ItemHologramRenderer + registration (compile + manual)

**Files:**
- Create: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/sim/ItemHologramRenderer.kt`
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` (register ~167; render hook ~374)

> Rendering can't be unit-tested without a render context; gate this task on compile + the Phase 6 manual end-to-end. Keep the math (arc/scale/alpha) in pure helper functions so they *could* be tested, and add a tiny pure-math test if a test task exists.

- [ ] **Step 1: Implement the renderer**

```kotlin
package com.canefe.storyclient.client.sim

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.model.json.ModelTransformationMode
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import java.util.concurrent.ConcurrentLinkedQueue

object ItemHologramRenderer {
    private const val DURATION_MS = 800L
    private const val MAX_HOLOS = 32
    private const val APEX_LIFT = 1.2

    private data class Holo(
        val fromId: Int, val toId: Int, val stack: ItemStack, val qty: Int,
        val startMs: Long = System.currentTimeMillis(),
    )

    private val holos = ConcurrentLinkedQueue<Holo>()

    fun spawn(payload: ItemTransferPayload) {
        val item = Identifier.tryParse(payload.materialId)?.let { Registries.ITEM.get(it) } ?: return
        val stack = ItemStack(item)
        // customModelData via component if >=0 — version-dependent; skip if API differs.
        while (holos.size >= MAX_HOLOS) holos.poll()
        holos.add(Holo(payload.fromEntityId, payload.toEntityId, stack, payload.qty))
    }

    /** progress 0..1 -> arc-interpolated position with an apex lift. Pure; testable. */
    fun arc(from: Vec3d, to: Vec3d, t: Double, lift: Double = APEX_LIFT): Vec3d {
        val base = from.lerp(to, t)
        val arcY = 4.0 * lift * t * (1.0 - t) // parabola peaking at t=0.5
        return Vec3d(base.x, base.y + arcY, base.z)
    }

    fun render(ctx: WorldRenderContext) {
        if (holos.isEmpty()) return
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val now = System.currentTimeMillis()
        val cam = ctx.camera().pos
        val tickDelta = ctx.tickCounter().getTickDelta(false)
        val matrices = ctx.matrixStack() ?: return
        val it = holos.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val elapsed = now - h.startMs
            if (elapsed >= DURATION_MS) { it.remove(); continue }
            val from = world.getEntityById(h.fromId) ?: run { it.remove(); continue }
            val to = world.getEntityById(h.toId) ?: run { it.remove(); continue }
            val fromPos = Vec3d(
                from.prevX + (from.x - from.prevX) * tickDelta,
                from.prevY + (from.y - from.prevY) * tickDelta + from.height * 0.7,
                from.prevZ + (from.z - from.prevZ) * tickDelta,
            )
            val toPos = Vec3d(
                to.prevX + (to.x - to.prevX) * tickDelta,
                to.prevY + (to.y - to.prevY) * tickDelta + to.height * 0.7,
                to.prevZ + (to.z - to.prevZ) * tickDelta,
            )
            val t = elapsed.toDouble() / DURATION_MS
            val pos = arc(fromPos, toPos, t)
            val scale = scaleFor(t)
            val alpha = alphaFor(t) // (use for tint if item render supports it; else gate visibility)
            val spin = (elapsed % 1000L) / 1000f * 360f

            matrices.push()
            matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
            matrices.multiply(Quaternionf().rotationY(Math.toRadians(spin.toDouble()).toFloat()))
            matrices.scale(scale, scale, scale)
            mc.itemRenderer.renderItem(
                h.stack, ModelTransformationMode.GROUND, 0xF000F0, net.minecraft.client.render.OverlayTexture.DEFAULT_UV,
                matrices, ctx.consumers(), world, 0,
            )
            matrices.pop()
            // qty badge: if h.qty > 1, draw "x{qty}" via mc.textRenderer here (billboarded).
        }
    }

    fun scaleFor(t: Double): Float = when {
        t < 0.15 -> (t / 0.15 * 0.5).toFloat()       // scale-in 0 -> 0.5
        t > 0.75 -> ((1.0 - t) / 0.25 * 0.5).toFloat() // scale-out -> 0
        else -> 0.5f
    }

    fun alphaFor(t: Double): Float = if (t > 0.75) ((1.0 - t) / 0.25).toFloat() else 1f
}
```
(Verify `mc.itemRenderer.renderItem(...)` overload + `ModelTransformationMode` + `OverlayTexture` against the Fabric/Yarn version this repo targets; the call shape and class names may differ slightly. Adjust signatures to compile. The qty-badge text draw mirrors `PerceptionPopupRenderer`'s `TextRenderer` usage — copy that pattern.)

- [ ] **Step 2: Register payload + receiver + render hook**

In `NPCMessageParserClient.kt`, in the registration block (~167, next to the perception/combat registrations):
```kotlin
PayloadTypeRegistry.playS2C().register(
    com.canefe.storyclient.client.sim.ItemTransferPayload.ID,
    com.canefe.storyclient.client.sim.ItemTransferPayload.CODEC,
)
ClientPlayNetworking.registerGlobalReceiver(
    com.canefe.storyclient.client.sim.ItemTransferPayload.ID,
) { payload, _ ->
    com.canefe.storyclient.client.sim.ItemHologramRenderer.spawn(payload)
}
```
And in the `WorldRenderEvents.AFTER_ENTITIES.register { context -> ... }` block (~374), add:
```kotlin
com.canefe.storyclient.client.sim.ItemHologramRenderer.render(context)
```

- [ ] **Step 3: Compile**

Run: `cd StoryClient && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (resolve any item-render API mismatch here).

- [ ] **Step 4: Commit**

```bash
cd StoryClient && jj commit -m "feat(sim): ItemHologramRenderer arcs item giver->receiver on story:item_transfer"
```

---

## Phase 5 — Action labels end-to-end

### Task 11: BehaviorDef.label + loader (sim)

**Files:**
- Modify: `story-sim/src/resources/mod.rs` (`struct BehaviorDef` ~673)
- Modify: `story-sim/src/plugins/definition_loader/behaviors.rs` (`parse_one` ~195)

- [ ] **Step 1: Add the field**

After `pub description: Option<String>,` add:
```rust
pub label: Option<String>,
```
Then `cargo build` to surface every `BehaviorDef { ... }` literal that now misses `label` (tests/fixtures included).

- [ ] **Step 2: Parse it in the loader**

In `parse_one`, in the `BehaviorDef { ... }` literal (after `description:`):
```rust
label: extract_optional_string(&table, "Label"),
```
Add `label: None,` to any other `BehaviorDef { ... }` construction the compiler flags (test fixtures/defaults).

- [ ] **Step 3: Build**

Run: `cd story-sim && cargo build`
Expected: success once every literal has `label`.

- [ ] **Step 4: Commit**

```bash
cd story-sim && jj commit -m "feat(sim): BehaviorDef.label parsed from BEHAVIOR.Label"
```

### Task 12: Resolve + emit actionId/actionLabel on npc.state (sim)

**Files:**
- Modify: `story-sim/src/plugins/entity_state_broadcast.rs`
- Test: same file `#[cfg(test)]` for the resolver precedence.

- [ ] **Step 1: Write the failing resolver test**

Add a pure resolver fn and test it. In `entity_state_broadcast.rs`:
```rust
#[cfg(test)]
mod label_tests {
    use super::*;
    #[test]
    fn dynamic_label_wins_over_static() {
        assert_eq!(
            resolve_label(Some("dyn"), Some("static")), Some("dyn".to_string()),
        );
    }
    #[test]
    fn falls_back_to_static_then_none() {
        assert_eq!(resolve_label(None, Some("static")), Some("static".to_string()));
        assert_eq!(resolve_label(None, None), None);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd story-sim && cargo test dynamic_label_wins_over_static`
Expected: FAIL — `resolve_label` undefined.

- [ ] **Step 3: Implement resolver + wire into the system**

Add the pure helper:
```rust
/// Dynamic per-action label wins; else the behavior's static label; else none.
pub(crate) fn resolve_label(dynamic: Option<&str>, static_label: Option<&str>) -> Option<String> {
    dynamic.map(|s| s.to_string()).or_else(|| static_label.map(|s| s.to_string()))
}
```
Extend the system query with `Option<&components::behavior::ActiveActions>` and `Option<&components::blackboard::Blackboard>`, add `behavior_registry: Res<crate::resources::BehaviorRegistry>`.

Inside the loop, before building the payload:
```rust
// pick current action: Interaction > Movement > Passive
let current = active_actions.and_then(|aa| {
    use components::behavior::ActionSlot::*;
    aa.actions.get(&Interaction).or_else(|| aa.actions.get(&Movement)).or_else(|| aa.actions.get(&Passive))
});
let action_id = current.map(|c| c.action_id.clone()).unwrap_or_default();
let behavior_id = current.map(|c| c.behavior_id.clone())
    .or_else(|| blackboard.and_then(|b| b.active_behavior.clone()))
    .unwrap_or_default();
let dynamic = current.and_then(|c| match c.lua_state.get("label") {
    Some(components::behavior::ActionStateValue::String(s)) => Some(s.as_str()),
    _ => None,
});
let static_label = behavior_registry.get(&behavior_id).and_then(|d| d.label.as_deref());
let action_label = resolve_label(dynamic, static_label);
```
Add to the payload `"data"`:
```rust
"actionId": action_id,
"actionLabel": action_label, // serde_json renders None as null
```
(Confirm `behavior_registry.get(id)` returns `Option<&BehaviorDef>` per `resources::registry`.)

- [ ] **Step 4: Run resolver tests + build**

Run: `cd story-sim && cargo test resolve_label && cargo test dynamic_label_wins_over_static && cargo build`
Expected: tests PASS, build SUCCESS.

- [ ] **Step 5: Commit**

```bash
cd story-sim && jj commit -m "feat(sim): emit actionId/actionLabel on npc.state (dynamic over static)"
```

### Task 13: Author BEHAVIOR.Label across the pack (sim)

**Files:**
- Modify: `story-sim/packs/BaseGame/lua/defs/behaviors/{buy_item,request_alms,give_alms,eat_bread,cooperative_eating,approach_partner,cautious_retreat,mental_break_panic,head_to_known_location}.lua`

- [ ] **Step 1: Add static labels**

Add a `BEHAVIOR.Label = "..."` line (near `BEHAVIOR.Name`) per the decided copy:
- `buy_item.lua` → `BEHAVIOR.Label = "Buying"`
- `request_alms.lua` → `BEHAVIOR.Label = "Begging"`
- `give_alms.lua` → `BEHAVIOR.Label = "Giving alms"`
- `eat_bread.lua` → `BEHAVIOR.Label = "Eating"`
- `cooperative_eating.lua` → `BEHAVIOR.Label = "Eating together"`
- `approach_partner.lua` → `BEHAVIOR.Label = "Approaching"`
- `cautious_retreat.lua` → `BEHAVIOR.Label = "Backing away"`
- `mental_break_panic.lua` → `BEHAVIOR.Label = "Panicking"`
- `head_to_known_location.lua` → `BEHAVIOR.Label = "Heading somewhere"` (static fallback)
- Leave `idle_stand.lua` and `idle_look_around.lua` with NO label.

- [ ] **Step 2: Add the dynamic binding-aware label in head_to_known_location.lua**

In `OnStart`, immediately after `local target = char:get_known_location_target(tag, filter)` and the nil-check, set the dynamic label:
```lua
    char.action.set("label", "Heading to " .. (target.instance_name or "somewhere"))
```
In `OnTick`, after re-resolving `target` (and confirming non-nil), refresh it (the destination can drift under `filter="nearest"`):
```lua
    if target then
        char.action.set("label", "Heading to " .. (target.instance_name or "somewhere"))
    end
```
(Place these where `target` is in scope and non-nil — mirror the existing `dist_to(char, target)` guards.)

- [ ] **Step 3: Sanity-load (build/run)**

Run: `cd story-sim && cargo build`
Expected: success (Lua isn't compiled by cargo, but build confirms nothing else broke). Lua label correctness is verified in Phase 6 manual run.

- [ ] **Step 4: Commit**

```bash
cd story-sim && jj commit -m "content(behaviors): author BEHAVIOR.Label set + binding-aware head_to_known_location"
```

### Task 14: StoryMC extend NpcStateIntent + change-diff send (TDD)

**Files:**
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` (`NpcStateIntent`)
- Modify: `Story/src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt`
- Modify: `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`
- Test: `Story/src/test/kotlin/com/canefe/story/bridge/ActionLabelDiffTest.kt`

- [ ] **Step 1: Extend the DTO**

In `NpcStateIntent` add:
```kotlin
val actionId: String? = null,
val actionLabel: String? = null,
```

- [ ] **Step 2: Add ACTION type + sender to PerceptionBroadcaster**

In the `PopupType` enum (~226) add `, ACTION(5)`. Add:
```kotlin
fun sendActionPopup(npcUuid: java.util.UUID, label: String) {
    broadcastPerceptionPopup(npcUuid, label, PopupType.ACTION)
}
```

- [ ] **Step 3: Write the failing change-diff test**

The diff logic must be a pure, testable function. Add it to `IntentExecutor` as `shouldSendActionLabel(characterId, label)` backed by a map, and test it:
```kotlin
package com.canefe.story.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActionLabelDiffTest {
    @BeforeEach fun reset() = IntentExecutor.resetActionLabelCacheForTest()

    @Test fun `first label sends`() {
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
    }
    @Test fun `same label twice sends once`() {
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
        assertFalse(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
    }
    @Test fun `changed label sends again`() {
        IntentExecutor.shouldSendActionLabel("npc1", "Buying")
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Eating"))
    }
    @Test fun `blank clear sends once after a label`() {
        IntentExecutor.shouldSendActionLabel("npc1", "Buying")
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", ""))
        assertFalse(IntentExecutor.shouldSendActionLabel("npc1", ""))
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ActionLabelDiffTest'`
Expected: FAIL — `shouldSendActionLabel`/`resetActionLabelCacheForTest` unresolved.

- [ ] **Step 5: Implement the diff + wire into executeNpcStateIntent**

In `IntentExecutor` (object/companion scope so it's static-callable from tests):
```kotlin
private val lastActionLabel = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Returns true if this label differs from the last sent for the NPC (and records it). */
fun shouldSendActionLabel(characterId: String, label: String): Boolean {
    val prev = lastActionLabel.put(characterId, label)
    return prev != label
}

fun resetActionLabelCacheForTest() = lastActionLabel.clear()
```
At the end of `executeNpcStateIntent`, add:
```kotlin
val label = intent.actionLabel?.takeIf { it.isNotBlank() } ?: ""
if (shouldSendActionLabel(intent.characterId, label)) {
    npc.entity?.uniqueId?.let { plugin.perceptionBroadcaster.sendActionPopup(it, label) }
}
```
(Confirm `plugin.perceptionBroadcaster` is the accessor name for the broadcaster instance; match how it's referenced elsewhere — e.g. the existing perception send call site.)

- [ ] **Step 6: Run to verify it passes**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests 'com.canefe.story.bridge.ActionLabelDiffTest'`
Expected: PASS (4 tests).

- [ ] **Step 7: Compile the whole module**

Run: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
cd Story && jj commit -m "feat(bridge): action-label change-diff -> PerceptionBroadcaster.sendActionPopup (ACTION)"
```

### Task 15: StoryClient PopupType.ACTION + style + empty-clear

**Files:**
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/NpcPerceptionPayload.kt` (`PopupType`)
- Modify: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt`

- [ ] **Step 1: Add the enum variant**

In `PopupType` (currently `PERCEPTION(0)`..`AGGRESSION(4)`) add:
```kotlin
ACTION(5);
```
(Keep the `companion object { fun fromId ... }` intact.)

- [ ] **Step 2: Add the style**

In `PerceptionPopupRenderer`'s `styles` map add:
```kotlin
PopupType.ACTION to PopupStyle("➤", 0xC8E6C9),
```

- [ ] **Step 3: Empty-label clear in onPerception**

Replace `onPerception` body so a blank ACTION label clears that NPC's ACTION popups instead of enqueuing:
```kotlin
fun onPerception(npcUuid: UUID, perceivedLabel: String, type: PopupType = PopupType.PERCEPTION) {
    if (type == PopupType.ACTION && perceivedLabel.isBlank()) {
        popups[npcUuid]?.let { q -> q.removeAll { it.type == PopupType.ACTION } }
        return
    }
    if (type == PopupType.ACTION) {
        // collapse to a single live ACTION popup per NPC (label changes replace)
        popups[npcUuid]?.let { q -> q.removeAll { it.type == PopupType.ACTION } }
    }
    popups.getOrPut(npcUuid) { ArrayDeque() }.addLast(Popup(perceivedLabel, type))
}
```
(`Popup`'s fields are `(label, type, startMs)` per the existing data class — match exactly.)

- [ ] **Step 4: Compile**

Run: `cd StoryClient && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd StoryClient && jj commit -m "feat(perception): PopupType.ACTION style + empty-label clear for sim action labels"
```

---

## Phase 6 — End-to-end verification (manual)

### Task 16: Full-stack manual verification

**No code; verification only.** Use the spec's "Manual end-to-end" criteria.

- [ ] **Step 1: Build everything**

```bash
cd story-sim && cargo build
cd story-go && go build ./...
cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew build -x test
cd StoryClient && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew build -x test
```
Expected: all succeed.

- [ ] **Step 2: Run the stack and trigger the village trade chain**

Start sim + story-go + Paper server (bridge.enabled) + Fabric client. Spawn/seed the village demo NPCs (per the in-game authoring commands) so a vendor/buyer trade fires.

- [ ] **Step 3: Observe action labels**

Expected: NPCs show fading labels — "Buying" over a buyer at a stall, "Heading to market_square" while traveling, "Begging"/"Eating" as those behaviors run. Idle NPCs show nothing. Label clears promptly when the NPC goes idle.

- [ ] **Step 4: Observe item-transfer holograms**

Expected: on a settled trade, a bread/coin item model arcs from vendor to buyer (and coin buyer→vendor), spinning, scaling in then fading over ~0.8s, with an `xN` badge when qty>1.

- [ ] **Step 5: Confirm the wire (if any visual is missing)**

Tail story-go / sim logs for `npc.item_transfer` and `actionLabel` on `npc.state`. If the event reaches go but not the client visual, the gap is MC-side (resolveNPC/audience) or client registration. Use this to localize.

- [ ] **Step 6: Finalize**

Once verified, the per-repo jj commits already capture the work. Report results (which visuals confirmed, any deferred follow-ups).

---

## Self-Review Notes

- **Spec coverage:** Feature A (A1 label model→Tasks 11,13; A2 broadcast→Task 12; A3 go relay→no-op, noted; A4 MC→Task 14; A5 client→Task 15). Feature B (B1 sim emit→Tasks 3,4; B2 go→Task 5; B3 item map→Tasks 1,2; B4 MC handler→Tasks 6,7,8; B5 client→Tasks 9,10). Manual e2e→Task 16. All spec sections mapped.
- **Untestable-by-unit tasks** (10 renderer, 16 manual) gate on compile + manual run, with pure helpers (`arc`/`scaleFor`/`alphaFor`, `resolve_label`, `shouldSendActionLabel`, encode/decode) extracted for real tests where possible.
- **Type consistency:** `ItemRenderSpec(material, customModelData)`, `renderSpecFor`, `ItemTransferPacketBridge.encode/reasonOrdinal/CHANNEL`, `NpcItemTransferIntent` fields, `ItemTransferPayload` fields, `PopupType.ACTION(5)` (server byte 5 == client ordinal 5), `sendActionPopup`, `resolve_label`, `shouldSendActionLabel` — names consistent across producer/consumer tasks.
- **Verify-against-API flags** are called out inline (Material.key vs name; Fabric item-render overload; CustomPayload.getId shape; BridgePubSubOut path/SUBJECT) — resolve each at its compile step.
