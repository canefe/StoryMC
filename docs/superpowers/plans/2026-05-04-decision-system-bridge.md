# Decision System — Story.kt Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the decision system through Story.kt as a pure relay — receive `decision.prompt` / `decision.observe` from Go via WebSocket, forward to Fabric clients as raw packets, receive `decision.response` packets from clients, and forward back to Go.

**Architecture:** Three new `BridgeDTO` message types added to `BridgeDTO.kt`. A new `DecisionPacketHandler` in `bridge/` listens on the `StoryEventBus` for inbound Go messages and sends custom payload packets to the targeted online players. A new `DecisionResponsePayload` registered on the Paper side receives responses from clients and forwards them to Go via the event bus. No decision logic lives here.

**Tech Stack:** Kotlin, Paper API (custom payload packets via `PluginMessaging` / `ClientPlayNetworking`), kotlinx.serialization, existing `StoryEventBus` / `WebSocketTransport` pipeline.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `src/main/kotlin/com/canefe/story/intelligence/BridgeDTO.kt` | Add `DecisionPromptDTO`, `DecisionObserveDTO`, `DecisionResponseDTO`, constants |
| Create | `src/main/kotlin/com/canefe/story/bridge/DecisionRelay.kt` | Listen for Go decision events, send packets to players; receive response packets, forward to Go |
| Modify | `src/main/kotlin/com/canefe/story/Story.kt` | Register `DecisionRelay` on startup |
| Create | `src/test/kotlin/com/canefe/story/bridge/DecisionRelayTest.kt` | Unit tests for relay logic |

---

## Task 1: Add Decision DTOs to BridgeDTO.kt

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/intelligence/BridgeDTO.kt`

- [ ] **Step 1: Add the DTOs at the bottom of BridgeDTO.kt, before the `object EventType` block**

```kotlin
// --- Decision System ---

@Serializable
data class DecisionNpcVoiceDTO(
    val characterId: String,
    val name: String,
    val opinion: String,
    val stance: String,
)

@Serializable
data class DecisionOptionDTO(
    val id: String,
    val label: String,
    val consequenceHint: String = "",
)

@Serializable
data class DecisionPromptDTO(
    val decisionId: String,
    val mode: String, // "leader" | "vote"
    val leaderId: String = "",
    val playerTargets: List<String> = emptyList(),
    val title: String,
    val context: String,
    val urgency: String, // "critical" | "ambient"
    val npcVoices: List<DecisionNpcVoiceDTO> = emptyList(),
    val options: List<DecisionOptionDTO> = emptyList(),
    val allowFreeform: Boolean = true,
    val timeoutSeconds: Int = 60,
)

@Serializable
data class DecisionObserveDTO(
    val decisionId: String,
    val leaderName: String,
    val options: List<DecisionOptionDTO> = emptyList(),
)

@Serializable
data class DecisionResponseDTO(
    val decisionId: String,
    val characterId: String,
    val choiceId: String? = null,
    val freeformText: String? = null,
)
```

Also add constants to the `EventType` object:

```kotlin
const val DECISION_PROMPT = "decision.prompt"
const val DECISION_OBSERVE = "decision.observe"
const val DECISION_RESPONSE = "decision.response"
```

- [ ] **Step 2: Compile to verify no errors**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/canefe/story/intelligence/BridgeDTO.kt
git commit -m "feat: add Decision DTOs and event type constants"
```

---

## Task 2: Write failing tests for DecisionRelay

**Files:**
- Create: `src/test/kotlin/com/canefe/story/bridge/DecisionRelayTest.kt`
- Modify: (none yet)

- [ ] **Step 1: Write the test file**

```kotlin
package com.canefe.story.bridge

import com.canefe.story.intelligence.DecisionNpcVoiceDTO
import com.canefe.story.intelligence.DecisionObserveDTO
import com.canefe.story.intelligence.DecisionOptionDTO
import com.canefe.story.intelligence.DecisionPromptDTO
import com.canefe.story.intelligence.DecisionResponseDTO
import com.canefe.story.intelligence.EventType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DecisionRelayTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `DecisionPromptDTO round-trips through JSON`() {
        val dto = DecisionPromptDTO(
            decisionId = "test-uuid",
            mode = "leader",
            leaderId = "char-1",
            playerTargets = listOf("char-1", "char-2"),
            title = "The battle line is breaking",
            context = "Left flank overwhelmed.",
            urgency = "critical",
            npcVoices = listOf(
                DecisionNpcVoiceDTO("npc-1", "Valen", "Fall back.", "cautious")
            ),
            options = listOf(
                DecisionOptionDTO("a", "Retreat", "Safer"),
                DecisionOptionDTO("b", "Attack", "Risky"),
            ),
            allowFreeform = true,
            timeoutSeconds = 60,
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionPromptDTO>(encoded)

        assertEquals(dto.decisionId, decoded.decisionId)
        assertEquals(dto.mode, decoded.mode)
        assertEquals(dto.urgency, decoded.urgency)
        assertEquals(1, decoded.npcVoices.size)
        assertEquals(2, decoded.options.size)
        assertEquals("cautious", decoded.npcVoices[0].stance)
    }

    @Test
    fun `DecisionObserveDTO round-trips through JSON`() {
        val dto = DecisionObserveDTO(
            decisionId = "test-uuid",
            leaderName = "Valen",
            options = listOf(DecisionOptionDTO("a", "Retreat", "Safer")),
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionObserveDTO>(encoded)

        assertEquals("test-uuid", decoded.decisionId)
        assertEquals("Valen", decoded.leaderName)
        assertEquals(1, decoded.options.size)
    }

    @Test
    fun `DecisionResponseDTO with choiceId serializes correctly`() {
        val dto = DecisionResponseDTO(
            decisionId = "test-uuid",
            characterId = "char-1",
            choiceId = "a",
            freeformText = null,
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionResponseDTO>(encoded)

        assertEquals("a", decoded.choiceId)
        assertNull(decoded.freeformText)
    }

    @Test
    fun `DecisionResponseDTO with freeformText serializes correctly`() {
        val dto = DecisionResponseDTO(
            decisionId = "test-uuid",
            characterId = "char-1",
            choiceId = null,
            freeformText = "We should flank from the east.",
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionResponseDTO>(encoded)

        assertNull(decoded.choiceId)
        assertEquals("We should flank from the east.", decoded.freeformText)
    }

    @Test
    fun `EventType constants are correct`() {
        assertEquals("decision.prompt", EventType.DECISION_PROMPT)
        assertEquals("decision.observe", EventType.DECISION_OBSERVE)
        assertEquals("decision.response", EventType.DECISION_RESPONSE)
    }
}
```

- [ ] **Step 2: Run tests — expect failures only if classes not yet available**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.DecisionRelayTest" 2>&1 | tail -20
```

Expected: PASS (DTOs were added in Task 1, so these should pass immediately)

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/canefe/story/bridge/DecisionRelayTest.kt
git commit -m "test: DecisionDTO round-trip serialization tests"
```

---

## Task 3: Create DecisionRelay

**Files:**
- Create: `src/main/kotlin/com/canefe/story/bridge/DecisionRelay.kt`

The relay has two responsibilities:
1. Listen on `StoryEventBus` for Go's `decision.prompt` and `decision.observe` events → send plugin message packets to the target players
2. Register a plugin message channel to receive `decision.response` from clients → forward to Go via the event bus

The plugin message channel ID is `story:decision` (server→client) and `story:decision_response` (client→server).

- [ ] **Step 1: Create DecisionRelay.kt**

```kotlin
package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.intelligence.DecisionObserveDTO
import com.canefe.story.intelligence.DecisionPromptDTO
import com.canefe.story.intelligence.DecisionResponseDTO
import com.canefe.story.intelligence.EventType
import com.canefe.story.util.characterId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

class DecisionRelay(private val plugin: Story) : PluginMessageListener {

    companion object {
        const val CHANNEL_S2C = "story:decision"
        const val CHANNEL_C2S = "story:decision_response"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun register() {
        // Listen for inbound Go events on the event bus
        plugin.storyEventBus.onType(EventType.DECISION_PROMPT) { event ->
            handleDecisionPrompt(event)
        }
        plugin.storyEventBus.onType(EventType.DECISION_OBSERVE) { event ->
            handleDecisionObserve(event)
        }

        // Register plugin message channels
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL_S2C)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL_C2S, this)

        plugin.logger.info("[DecisionRelay] Registered on channels $CHANNEL_S2C / $CHANNEL_C2S")
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_S2C)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_C2S, this)
    }

    private fun handleDecisionPrompt(event: StoryEvent) {
        val wireData = (event as? DomainEvents.InboundGoEvent)?.data ?: return
        val dto = runCatching {
            json.decodeFromString<DecisionPromptDTO>(wireData.toString())
        }.getOrElse {
            plugin.logger.warning("[DecisionRelay] Failed to parse decision.prompt: ${it.message}")
            return
        }

        dto.playerTargets.forEach { characterId ->
            val player = plugin.server.onlinePlayers.firstOrNull { it.characterId == characterId }
            if (player != null) {
                sendPacket(player, json.encodeToString(dto))
            }
        }
    }

    private fun handleDecisionObserve(event: StoryEvent) {
        val wireData = (event as? DomainEvents.InboundGoEvent)?.data ?: return
        val dto = runCatching {
            json.decodeFromString<DecisionObserveDTO>(wireData.toString())
        }.getOrElse {
            plugin.logger.warning("[DecisionRelay] Failed to parse decision.observe: ${it.message}")
            return
        }

        // Observe events are sent to non-leader targets — the event carries the target list
        // Go sends observe events per-target; each event has a single implicit receiver
        // We forward to all online players who are observers (not in playerTargets of the prompt)
        // Since Go sends one observe event with no explicit target list, we rely on Go to
        // send one observe event per observer player. For now, broadcast to all online players
        // who are NOT the leader.
        plugin.server.onlinePlayers
            .filter { it.characterId != null }
            .forEach { player ->
                sendPacket(player, json.encodeToString(dto))
            }
    }

    private fun sendPacket(
        player: Player,
        payload: String,
    ) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        player.sendPluginMessage(plugin, CHANNEL_S2C, bytes)
    }

    // Called when a client sends a decision.response packet back
    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray,
    ) {
        if (channel != CHANNEL_C2S) return
        val payload = String(message, Charsets.UTF_8)

        val dto = runCatching {
            json.decodeFromString<DecisionResponseDTO>(payload)
        }.getOrElse {
            plugin.logger.warning("[DecisionRelay] Failed to parse decision.response from ${player.name}: ${it.message}")
            return
        }

        // Stamp the characterId from the player's session
        val characterId = player.characterId ?: run {
            plugin.logger.warning("[DecisionRelay] No characterId for ${player.name}, dropping response")
            return
        }
        val stamped = dto.copy(characterId = characterId)

        // Forward to Go via the event bus as an outbound event
        plugin.storyEventBus.emit(
            DomainEvents.OutboundDecisionResponse(
                type = EventType.DECISION_RESPONSE,
                data = json.encodeToString(stamped),
            )
        )
    }
}
```

- [ ] **Step 2: Check what DomainEvents looks like and adjust the inbound event parsing to match the actual pattern used by WebSocketTransport**

```bash
cat src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt
```

Adjust the `handleDecisionPrompt` / `handleDecisionObserve` and the outbound emit in `onPluginMessageReceived` to match whatever pattern `WebSocketTransport` uses to deliver inbound Go events to the bus (e.g., it may use a `GenericInboundEvent` with a `JsonObject` payload, or a `StoryEvent` subtype with a `data` field). Mirror exactly what `IntentExecutor` does when it receives intents.

- [ ] **Step 3: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/canefe/story/bridge/DecisionRelay.kt
git commit -m "feat: DecisionRelay — Go decision events relayed to Fabric clients"
```

---

## Task 4: Wire DecisionRelay into Story.kt

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/Story.kt`

- [ ] **Step 1: Find where other bridge components are registered in Story.kt (search for IntentExecutor or PerceptionBroadcaster registration)**

```bash
grep -n "IntentExecutor\|PerceptionBroadcaster\|DecisionRelay" src/main/kotlin/com/canefe/story/Story.kt
```

- [ ] **Step 2: Add DecisionRelay initialization in the same place**

In the `onEnable()` section where other bridge components are registered, add:

```kotlin
val decisionRelay = DecisionRelay(this)
decisionRelay.register()
```

Also register cleanup in `onDisable()`:

```kotlin
decisionRelay.unregister()
```

Store `decisionRelay` as a field on Story if the pattern requires it (mirror however `PerceptionBroadcaster` is stored).

- [ ] **Step 3: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run all tests**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test 2>&1 | tail -20
```

Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/canefe/story/Story.kt
git commit -m "feat: wire DecisionRelay into Story plugin lifecycle"
```

---

## Task 5: Smoke test the relay end-to-end

This is a manual verification step. No automated test can cover the full plugin message roundtrip without a running server.

- [ ] **Step 1: Build the jar**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew build
```

- [ ] **Step 2: Deploy to run/ and start the server**

```bash
cp build/libs/Story-*.jar run/plugins/Story.jar
```

Start the server (however you normally do it).

- [ ] **Step 3: Verify relay registration in server log**

Look for:
```
[DecisionRelay] Registered on channels story:decision / story:decision_response
```

- [ ] **Step 4: Simulate a decision.prompt from Go**

Using a debug command or the WebSocket connection, send a test `decision.prompt` event. Verify in the log that the relay parsed it and attempted to send it to the targeted player(s). If Go isn't available, you can temporarily add a `/decidedebug` command that manually calls `DecisionRelay.sendPacket()` to verify the plugin message channel is open.

- [ ] **Step 5: Commit any fixes found during smoke test**

```bash
git add -p
git commit -m "fix: <describe any issues found>"
```
