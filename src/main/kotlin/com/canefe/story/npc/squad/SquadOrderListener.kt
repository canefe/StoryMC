package com.canefe.story.npc.squad

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
import java.util.UUID

/**
 * Receives c2s squad order commands on `story:squad_order`.
 *
 * Wire format:
 *   UTF   squadId
 *   byte  opcode
 *   ... opcode-specific payload
 *
 * Opcodes:
 *   0x01 MOVE_TO        UTF worldKey, double x, double y, double z, float yaw
 *   0x02 HOLD
 *   0x03 FOLLOW_PLAYER  long playerUuidMost, long playerUuidLeast
 *   0x04 ENGAGE         bool targetIsPlayer, long targetMost, long targetLeast
 *   0x05 IDLE / cancel
 *   0x06 SET_FORMATION  byte formationOrdinal (0=LINE, 1=WEDGE, 2=COLUMN, 3=LOOSE)
 *
 * All commands validate that the calling player commands the squad
 * (owns it OR is in sharedWithCharacterIds).
 */
class SquadOrderListener(
    private val plugin: Story,
) : PacketListener {
    private val channelId = "story:squad_order"

    /**
     * True if [targetEntityUuid] resolves to a StoryNPC that's a member of
     * any squad the caller (identified by [callerCharacterId]) commands.
     */
    private fun isFriendlyTarget(callerCharacterId: String, targetEntityUuid: UUID): Boolean {
        val targetNpc =
            plugin.npcRegistry.getByClientFacingUuid(targetEntityUuid)
                ?: plugin.npcRegistry.get(targetEntityUuid)
                ?: return false
        val targetCharacterId =
            plugin.characterRegistry.getCharacterIdForNPC(targetNpc) ?: return false
        return plugin.squadRegistry
            .commandableBy(callerCharacterId)
            .any { it.memberCharacterIds.contains(targetCharacterId) }
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType !== PacketType.Play.Client.PLUGIN_MESSAGE) return
        val wrapper = WrapperPlayClientPluginMessage(event)
        if (wrapper.channelName != channelId) return

        val player = Bukkit.getPlayer(event.user.uuid) ?: return
        val data = wrapper.data
        Bukkit.getScheduler().runTask(plugin, Runnable { handle(player, data) })
    }

    private fun handle(player: Player, data: ByteArray) {
        if (!plugin.isSquadRegistryReady) return
        val charId = plugin.characterRegistry.getActiveCharacterForPlayer(player) ?: return

        try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                val squadId = input.readUTF()
                val squad = plugin.squadRegistry.getById(squadId) ?: return
                // Authorization: must be owner OR in sharedWith.
                if (squad.ownerCharacterId != charId &&
                    !squad.sharedWithCharacterIds.contains(charId)
                ) {
                    plugin.logger.warning("[SquadOrder] ${player.name} tried to command unauthorised squad ${squad.name}")
                    return
                }

                val order: SquadOrder? =
                    when (val op = input.readByte().toInt()) {
                        0x01 -> {
                            val worldKey = input.readUTF()
                            val x = input.readDouble()
                            val y = input.readDouble()
                            val z = input.readDouble()
                            val yaw = input.readFloat()
                            val w =
                                Bukkit.getWorlds().firstOrNull { it.key.key == worldKey }
                                    ?: Bukkit.getWorld(worldKey)
                                    ?: player.world
                            SquadOrder.MoveTo(Location(w, x, y, z), yaw)
                        }
                        0x02 -> SquadOrder.HoldPosition
                        0x03 -> {
                            val mu = input.readLong()
                            val ml = input.readLong()
                            SquadOrder.FollowPlayer(UUID(mu, ml))
                        }
                        0x04 -> {
                            val isPlayer = input.readBoolean()
                            val tm = input.readLong()
                            val tl = input.readLong()
                            val targetUuid = UUID(tm, tl)
                            // Reject friendly-fire: if the target NPC is in any squad
                            // this player commands, drop the order entirely.
                            if (!isPlayer && isFriendlyTarget(charId, targetUuid)) {
                                player.sendMessage(
                                    net.kyori.adventure.text.Component.text(
                                        "Cannot engage a member of your own squads.",
                                        net.kyori.adventure.text.format.NamedTextColor.RED,
                                    ),
                                )
                                return
                            }
                            SquadOrder.Engage(targetUuid, isPlayer)
                        }
                        0x05 -> SquadOrder.Idle
                        0x06 -> {
                            // SET_FORMATION — not an order, just a config update.
                            val ord = input.readByte().toInt()
                            val formation =
                                com.canefe.story.api.squad.SquadFormation.values().getOrNull(ord)
                            if (formation != null) {
                                plugin.squadRegistry.setFormation(squadId, formation)
                                plugin.squadListBroadcaster.push(player)
                            }
                            null
                        }
                        else -> {
                            plugin.logger.warning("[SquadOrder] unknown opcode $op from ${player.name}")
                            return
                        }
                    }

                if (order != null) {
                    plugin.squadOrderTracker.issue(squadId, order, player)
                    plugin.squadListBroadcaster.push(player)
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[SquadOrder] decode failed for ${player.name}: ${e.message}")
        }
    }
}
