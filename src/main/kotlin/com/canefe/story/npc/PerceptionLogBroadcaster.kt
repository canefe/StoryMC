package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.intelligence.BridgeIntelligence
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Server→client push for the DM "perception log" wheel UI on
 * `story:perception_log`. Sent on demand in response to a `request` opcode
 * on `story:perception_command`.
 *
 * Wire format (big-endian):
 *   UTF   characterId
 *   short count
 *   for each entry:
 *     UTF    source
 *     UTF    description
 *     long   timestamp        (unix millis from story-go)
 *     UTF    perceiverId
 *     double distance
 *
 * Errors (bridge missing, etc.) send a payload with count=0; the client UI
 * shows "(no perceptions)".
 */
class PerceptionLogBroadcaster(
    private val plugin: Story,
) {
    private val channelId = "story:perception_log"

    fun sendTo(player: Player, characterId: String) {
        if (!player.hasPermission("story.dm")) return
        val bridge = if (plugin.isIntelligenceReady) plugin.intelligence as? BridgeIntelligence else null
        if (bridge == null) {
            send(player, characterId, emptyList())
            return
        }
        bridge.getPerceptions(characterId)
            .thenAccept { entries -> send(player, characterId, entries) }
            .exceptionally { e ->
                plugin.logger.warning("[PerceptionLog] getPerceptions failed for $characterId: ${e.message}")
                send(player, characterId, emptyList())
                null
            }
    }

    private fun send(
        player: Player,
        characterId: String,
        entries: List<com.canefe.story.intelligence.PerceptionEntryDTO>,
    ) {
        val bytes = encode(characterId, entries)
        val packet = WrapperPlayServerPluginMessage(channelId, bytes)
        try {
            PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
        } catch (e: Exception) {
            plugin.logger.warning("[PerceptionLog] send failed for ${player.name}: ${e.message}")
        }
    }

    private fun encode(
        characterId: String,
        entries: List<com.canefe.story.intelligence.PerceptionEntryDTO>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeUTF(characterId)
            out.writeShort(entries.size)
            for (e in entries) {
                out.writeUTF(e.source)
                out.writeUTF(e.description)
                out.writeLong(e.timestamp)
                out.writeUTF(e.perceiverId)
                out.writeDouble(e.distance)
            }
        }
        return baos.toByteArray()
    }
}
