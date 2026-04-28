package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.intelligence.BridgeIntelligence
import com.canefe.story.util.characterId
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Pushes each player their recognition set — the map of `characterId → realName`
 * for everyone they currently recognize. Used by the StoryClient nametag
 * renderer to show the real name on top + descriptor on the bottom for
 * recognized characters.
 *
 * Channel: `story:recognition_set`
 *
 * Wire format (big-endian):
 *   short  count
 *   for each:
 *     UTF  characterId
 *     UTF  realName
 *
 * Push triggers:
 *   - PlayerJoinEvent (initial sync, +20 ticks to let the bridge come up)
 *   - [refresh] called explicitly after a /story recognize | forget command
 *
 * The whole set is resent on each push; deltas would be smaller but the set is
 * tiny in practice (one entry per character a perceiver has met).
 */
class RecognitionBroadcaster(
    private val plugin: Story,
) : Listener {
    private val channelId = "story:recognition_set"

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable { if (player.isOnline) push(player) },
            20L,
        )
    }

    /** Push the recognition set to all online players. Call after a /story recognize. */
    fun refreshAll() {
        for (player in Bukkit.getOnlinePlayers()) push(player)
    }

    /** Push the recognition set for a specific perceiver-as-player. */
    fun push(player: Player) {
        val perceiverId = player.characterId ?: run {
            // No bound character → empty set so the client can clear stale data.
            sendBytes(player, encode(emptyMap()))
            return
        }
        val bridge = plugin.intelligence as? BridgeIntelligence
        if (bridge == null || !bridge.isRecognitionSupported()) {
            sendBytes(player, encode(emptyMap()))
            return
        }

        bridge.knownOf(perceiverId)
            .thenAccept { known ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable { if (player.isOnline) sendBytes(player, encode(known)) },
                )
            }
            .exceptionally { e ->
                plugin.logger.warning(
                    "[RecognitionBroadcaster] knownOf failed for ${player.name}: ${e.message}",
                )
                null
            }
    }

    private fun encode(known: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeShort(known.size)
            for ((charId, realName) in known) {
                out.writeUTF(charId)
                out.writeUTF(realName)
            }
        }
        return baos.toByteArray()
    }

    private fun sendBytes(player: Player, bytes: ByteArray) {
        try {
            PacketEvents.getAPI().playerManager.getUser(player)
                .sendPacket(WrapperPlayServerPluginMessage(channelId, bytes))
        } catch (e: Exception) {
            plugin.logger.warning("[RecognitionBroadcaster] send failed for ${player.name}: ${e.message}")
        }
    }
}
