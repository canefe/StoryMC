package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.intelligence.DecisionObserveDTO
import com.canefe.story.intelligence.DecisionPromptDTO
import com.canefe.story.intelligence.DecisionResponseDTO
import com.canefe.story.intelligence.EventType
import com.canefe.story.util.characterId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * Bridges Go decision events to Fabric/plugin-message clients and back.
 *
 * Inbound from Go (via WebSocket → StoryEventBus):
 *   - [EventType.DECISION_PROMPT]  → [DecisionPromptDTO]   → plugin message → targeted players
 *   - [EventType.DECISION_OBSERVE] → [DecisionObserveDTO]  → plugin message → targeted players
 *
 * Inbound from clients (plugin message channel "story:decision_response"):
 *   - Parse [DecisionResponseDTO], stamp [characterId], emit to event bus → Go
 */
class DecisionRelay(private val plugin: Story) : PluginMessageListener {

    private val CHANNEL_OUT = "story:decision"
    private val CHANNEL_IN = "story:decision_response"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun register() {
        // Listen for Go → Plugin decision events
        plugin.eventBus.onType(EventType.DECISION_PROMPT) { event ->
            val data = event.toWireData() ?: return@onType
            val dto = try {
                json.decodeFromString<DecisionPromptDTO>(data.toString())
            } catch (e: Exception) {
                plugin.logger.warning("[DecisionRelay] Failed to parse DecisionPromptDTO: ${e.message}")
                return@onType
            }
            sendDecisionPrompt(dto)
        }

        plugin.eventBus.onType(EventType.DECISION_OBSERVE) { event ->
            val data = event.toWireData() ?: return@onType
            val dto = try {
                json.decodeFromString<DecisionObserveDTO>(data.toString())
            } catch (e: Exception) {
                plugin.logger.warning("[DecisionRelay] Failed to parse DecisionObserveDTO: ${e.message}")
                return@onType
            }
            sendDecisionObserve(dto)
        }

        // Register plugin message channels
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL_OUT)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL_IN, this)

        plugin.logger.info("[DecisionRelay] Registered channels: out=$CHANNEL_OUT, in=$CHANNEL_IN")
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_OUT)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_IN, this)
    }

    /**
     * Send a [DecisionPromptDTO] to targeted players.
     * If [DecisionPromptDTO.playerTargets] is empty, broadcasts to all online players.
     */
    private fun sendDecisionPrompt(dto: DecisionPromptDTO) {
        val payload = json.encodeToString(dto)
        val bytes = buildPacket("prompt", payload)

        val targets = resolveTargets(dto.playerTargets)
        if (targets.isEmpty()) {
            plugin.logger.warning("[DecisionRelay] No online targets for decision.prompt ${dto.decisionId}")
            return
        }

        for (player in targets) {
            player.sendPluginMessage(plugin, CHANNEL_OUT, bytes)
            plugin.logger.info("[DecisionRelay] Sent decision.prompt '${dto.decisionId}' to ${player.name}")
        }
    }

    /**
     * Send a [DecisionObserveDTO] to all online players (observer broadcast).
     */
    private fun sendDecisionObserve(dto: DecisionObserveDTO) {
        val payload = json.encodeToString(dto)
        val bytes = buildPacket("observe", payload)

        for (player in plugin.server.onlinePlayers) {
            player.sendPluginMessage(plugin, CHANNEL_OUT, bytes)
        }
        plugin.logger.info("[DecisionRelay] Broadcast decision.observe '${dto.decisionId}' to ${plugin.server.onlinePlayers.size} players")
    }

    /**
     * Resolve a list of character IDs to online [Player] instances.
     * Falls back to all online players when [targets] is empty.
     */
    private fun resolveTargets(targets: List<String>): List<Player> {
        if (targets.isEmpty()) return plugin.server.onlinePlayers.toList()
        return plugin.server.onlinePlayers.filter { player ->
            val cid = player.characterId
            cid != null && cid in targets
        }
    }

    /**
     * Build a plugin message packet: [subtype (1 byte)] + [UTF-8 JSON].
     * subtype: 0x01 = prompt, 0x02 = observe.
     */
    private fun buildPacket(subtype: String, jsonPayload: String): ByteArray {
        val typeFlag: Byte = when (subtype) {
            "prompt" -> 0x01
            "observe" -> 0x02
            else -> 0x00
        }
        val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1 + payloadBytes.size)
        buf[0] = typeFlag
        payloadBytes.copyInto(buf, destinationOffset = 1)
        return buf
    }

    /**
     * Receive a [DecisionResponseDTO] from a Fabric client, stamp the player's
     * character ID, and forward it to Go via the event bus.
     */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL_IN) return

        val jsonStr = try {
            String(message, Charsets.UTF_8)
        } catch (e: Exception) {
            plugin.logger.warning("[DecisionRelay] Failed to decode plugin message from ${player.name}: ${e.message}")
            return
        }

        val dto = try {
            json.decodeFromString<DecisionResponseDTO>(jsonStr)
        } catch (e: Exception) {
            plugin.logger.warning("[DecisionRelay] Failed to parse DecisionResponseDTO from ${player.name}: ${e.message}")
            return
        }

        // Stamp the authoritative characterId from the server-side player record
        val charId = player.characterId ?: run {
            plugin.logger.warning("[DecisionRelay] Player ${player.name} has no characterId, dropping decision response")
            return
        }

        val stamped = dto.copy(characterId = charId)
        plugin.eventBus.emit(DecisionResponseEvent(
            decisionId = stamped.decisionId,
            characterId = stamped.characterId,
            choiceId = stamped.choiceId,
            freeformText = stamped.freeformText,
        ))

        plugin.logger.info("[DecisionRelay] Forwarded decision response '${stamped.decisionId}' from ${player.name} (${charId})")
    }
}
