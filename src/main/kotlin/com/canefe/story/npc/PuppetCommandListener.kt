package com.canefe.story.npc

import com.canefe.story.Story
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Receives client-bound puppet commands on `story:puppet_command`.
 *
 * Wire format (big-endian):
 *   byte   opcode
 *   ... opcode-specific payload
 *
 * Opcodes:
 *   0x01 MOVE_TO     UTF world, double x, double y, double z
 *   0x02 ADD         UTF npcName
 *   0x03 REMOVE      UTF npcName
 *   0x04 TOGGLE      UTF npcName
 *   0x05 CLEAR
 *   0x06 SPEAK_AT    UTF targetName, UTF text   (move group to target then say text)
 *
 * All commands are gated on `story.dm` permission server-side.
 */
class PuppetCommandListener(
    private val plugin: Story,
) : PacketListener {
    private val channelId = "story:puppet_command"

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType !== PacketType.Play.Client.PLUGIN_MESSAGE) return
        val wrapper = WrapperPlayClientPluginMessage(event)
        if (wrapper.channelName != channelId) return

        val player = Bukkit.getPlayer(event.user.uuid) ?: return
        if (!player.hasPermission("story.dm")) return

        val data = wrapper.data
        // Hop to the main thread for any registry/world mutation.
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable { handle(player, data) },
        )
    }

    private fun handle(player: Player, data: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                when (val op = input.readByte().toInt()) {
                    0x01 -> {
                        val worldKey = input.readUTF()
                        val x = input.readDouble()
                        val y = input.readDouble()
                        val z = input.readDouble()
                        // Client sends the Minecraft registry path ("overworld",
                        // "the_nether", etc). Bukkit world names are configurable
                        // ("world", "world_nether", ...). Try registry key match
                        // first, fall back to exact name, then to the player's
                        // current world.
                        val w =
                            Bukkit.getWorlds().firstOrNull { it.key.key == worldKey }
                                ?: Bukkit.getWorld(worldKey)
                                ?: player.world
                        plugin.puppetManager.moveAll(player, Location(w, x, y, z))
                    }
                    0x02 -> {
                        val name = input.readUTF()
                        val npc = plugin.npcRegistry.getByName(name) ?: return
                        plugin.puppetManager.add(player, npc)
                    }
                    0x03 -> {
                        val name = input.readUTF()
                        val npc = plugin.npcRegistry.getByName(name) ?: return
                        plugin.puppetManager.remove(player, npc)
                    }
                    0x04 -> {
                        val name = input.readUTF()
                        val npc = plugin.npcRegistry.getByName(name) ?: return
                        plugin.puppetManager.toggle(player, npc)
                    }
                    0x05 -> plugin.puppetManager.clear(player)
                    0x06 -> {
                        val targetName = input.readUTF()
                        val text = input.readUTF()
                        // Move group to target's location, then have one of them speak.
                        val targetNpc = plugin.npcRegistry.getByName(targetName)
                        val targetLoc = targetNpc?.location ?: plugin.server.getPlayerExact(targetName)?.location
                        if (targetLoc != null) plugin.puppetManager.moveAll(player, targetLoc)
                        // Speak: pick the first NPC in the group and broadcast the text.
                        plugin.puppetManager
                            .resolveGroup(player)
                            .firstOrNull()
                            ?.let { plugin.npcMessageService.broadcastNPCMessage(text, it) }
                    }
                    else -> plugin.logger.warning("[PuppetCommand] unknown opcode $op from ${player.name}")
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[PuppetCommand] decode failed for ${player.name}: ${e.message}")
        }
    }
}
