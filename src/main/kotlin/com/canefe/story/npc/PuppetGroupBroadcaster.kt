package com.canefe.story.npc

import com.canefe.story.Story
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Pushes the current puppet group to a single player on demand.
 *
 * Channel: `story:puppet_group`
 *
 * Wire format:
 *   short  count
 *   for each:
 *     UTF  characterId
 */
class PuppetGroupBroadcaster(
    private val plugin: Story,
) {
    private val channelId = "story:puppet_group"

    fun push(player: Player) {
        if (!plugin.isNpcRegistryReady) return
        if (!plugin.isCharacterRegistryReady) return
        val charIds =
            plugin.puppetManager
                .resolveGroup(player)
                .mapNotNull { plugin.characterRegistry.getCharacterIdForNPC(it) }
        val bytes = encode(charIds)
        try {
            val packet = WrapperPlayServerPluginMessage(channelId, bytes)
            PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
        } catch (e: Exception) {
            plugin.logger.warning("[PuppetGroupBroadcaster] send failed for ${player.name}: ${e.message}")
        }
    }

    private fun encode(charIds: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeShort(charIds.size)
            for (id in charIds) out.writeUTF(id)
        }
        return baos.toByteArray()
    }
}
