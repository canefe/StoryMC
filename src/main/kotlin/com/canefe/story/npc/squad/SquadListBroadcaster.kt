package com.canefe.story.npc.squad

import com.canefe.story.Story
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Pushes the player's commandable squads (own + shared) on demand and on a
 * 2-second heartbeat. The HUD listens to this and rebuilds its sidebar.
 *
 * Channel: `story:squad_list`
 *
 * Wire format (big-endian):
 *   short  count
 *   for each:
 *     UTF  squadId
 *     UTF  name
 *     int  rgbColor          (0xRRGGBB)
 *     short memberCount
 *     UTF  currentOrderLabel ("Idle", "Hold", "Move", "Follow", "Engage")
 *     UTF  formationLabel    ("Line", "Wedge", "Column", "Loose")
 *     // member NPC stable UUIDs (used for badge rendering)
 *     short  memberUuidCount
 *     for each member:
 *       long mostSig
 *       long leastSig
 */
class SquadListBroadcaster(
    private val plugin: Story,
) : org.bukkit.event.Listener {
    private val channelId = "story:squad_list"
    private val lastSentHash = mutableMapOf<UUID, Int>()

    private var taskId: Int = -1

    fun start() {
        if (taskId != -1) return
        // Register self for join/quit events so we can drop stale per-player hash cache.
        Bukkit.getPluginManager().registerEvents(this, plugin)
        taskId =
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable { broadcastAll() },
                40L,
                40L,
            )
    }

    @org.bukkit.event.EventHandler
    fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        // Drop the cached hash so the next broadcast tick is forced to send,
        // even if the squad data hasn't changed since the player's prior session.
        // Also push immediately on a 1-tick delay so character lookup has settled.
        lastSentHash.remove(event.player.uniqueId)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { push(event.player) }, 1L)
    }

    @org.bukkit.event.EventHandler
    fun onPlayerQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        lastSentHash.remove(event.player.uniqueId)
    }

    fun stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId)
        taskId = -1
        lastSentHash.clear()
    }

    /** Push immediately to one player (e.g. after a command mutates squad state). */
    fun push(player: Player) = broadcastTo(player)

    private fun broadcastAll() {
        if (!plugin.isSquadRegistryReady || !plugin.isCharacterRegistryReady) return
        for (player in Bukkit.getOnlinePlayers()) broadcastTo(player)
    }

    private fun broadcastTo(player: Player) {
        val charId = plugin.characterRegistry.getActiveCharacterForPlayer(player) ?: return
        val squads = plugin.squadRegistry.commandableBy(charId)

        val entries =
            squads.map { sq ->
                // Resolve members live each broadcast — auto-rebinds across NPC
                // respawns since SquadRecord stores characterIds, not entity uuids.
                val liveMembers = plugin.squadRegistry.resolveLiveMembers(sq)
                Entry(
                    id = sq.id,
                    name = sq.name,
                    color = SquadColorScheme.colorFor(sq.id),
                    memberCount = sq.memberCharacterIds.size,
                    orderLabel = orderLabel(plugin.squadOrderTracker.current(sq.id)),
                    formationLabel = sq.formation.name.lowercase().replaceFirstChar { it.uppercase() },
                    // Send client-facing UUIDs of currently-live members so the
                    // client badge renderer can match against world entities.
                    memberUuids = liveMembers.mapNotNull { it.clientFacingUuid },
                )
            }

        val hash = entries.hashCode()
        if (lastSentHash[player.uniqueId] == hash) return
        lastSentHash[player.uniqueId] = hash

        val bytes = encode(entries)
        try {
            PacketEvents.getAPI().playerManager.getUser(player).sendPacket(
                WrapperPlayServerPluginMessage(channelId, bytes),
            )
        } catch (e: Exception) {
            plugin.logger.warning("[SquadListBroadcaster] send failed for ${player.name}: ${e.message}")
        }
    }

    private fun orderLabel(order: SquadOrder): String =
        when (order) {
            SquadOrder.Idle -> "Idle"
            is SquadOrder.MoveTo -> "Move"
            SquadOrder.HoldPosition -> "Hold"
            is SquadOrder.FollowPlayer -> "Follow"
            is SquadOrder.Engage -> "Engage"
        }

    private fun tryParse(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }

    private fun encode(entries: List<Entry>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeShort(entries.size)
            for (e in entries) {
                out.writeUTF(e.id)
                out.writeUTF(e.name)
                out.writeInt(e.color)
                out.writeShort(e.memberCount)
                out.writeUTF(e.orderLabel)
                out.writeUTF(e.formationLabel)
                out.writeShort(e.memberUuids.size)
                for (uuid in e.memberUuids) {
                    out.writeLong(uuid.mostSignificantBits)
                    out.writeLong(uuid.leastSignificantBits)
                }
            }
        }
        return baos.toByteArray()
    }

    private data class Entry(
        val id: String,
        val name: String,
        val color: Int,
        val memberCount: Int,
        val orderLabel: String,
        val formationLabel: String,
        val memberUuids: List<UUID>,
    )
}
