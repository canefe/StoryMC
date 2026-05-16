package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.intelligence.BridgeIntelligence
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import org.bukkit.Bukkit
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Receives client-bound perception-log commands on `story:perception_command`.
 *
 * Wire format (big-endian):
 *   byte opcode
 *   ... opcode-specific payload
 *
 * Opcodes:
 *   0x01 REQUEST UTF characterId            — Pushes a `story:perception_log` payload back
 *   0x02 FORGET  UTF characterId, int index — Drops that entry in story-go memory, then re-pushes
 *
 * All commands are gated on `story.dm`.
 */
class PerceptionCommandListener(
    private val plugin: Story,
    private val broadcaster: PerceptionLogBroadcaster,
) : PacketListener {
    private val channelId = "story:perception_command"

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType !== PacketType.Play.Client.PLUGIN_MESSAGE) return
        val wrapper = WrapperPlayClientPluginMessage(event)
        if (wrapper.channelName != channelId) return

        val player = Bukkit.getPlayer(event.user.uuid) ?: return
        if (!player.hasPermission("story.dm")) return

        val data = wrapper.data
        try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                when (val op = input.readByte().toInt()) {
                    0x01 -> {
                        val charId = input.readUTF()
                        Bukkit.getScheduler().runTask(
                            plugin,
                            Runnable { broadcaster.sendTo(player, charId) },
                        )
                    }
                    0x02 -> {
                        val charId = input.readUTF()
                        val index = input.readInt()
                        val bridge =
                            if (plugin.isIntelligenceReady) plugin.intelligence as? BridgeIntelligence else null
                        if (bridge == null) {
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable { broadcaster.sendTo(player, charId) },
                            )
                            return
                        }
                        bridge.forgetPerception(charId, index)
                            .whenComplete { _, _ ->
                                Bukkit.getScheduler().runTask(
                                    plugin,
                                    Runnable { broadcaster.sendTo(player, charId) },
                                )
                            }
                    }
                    else -> plugin.logger.warning("[PerceptionCommand] unknown opcode $op from ${player.name}")
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[PerceptionCommand] decode failed for ${player.name}: ${e.message}")
        }
    }
}
