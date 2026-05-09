package com.canefe.story.npc

import com.canefe.story.Story
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens to ChunkLoadEvent. Finds the nearest online player; if within
 * 8 chunks of the loaded chunk, schedules a debounced reconciliation
 * sweep at the player's live position. Only one pending task per player
 * at a time — new chunk loads cancel and reschedule, coalescing
 * chunk-stream bursts into one request per debounce window.
 */
class ChunkLoadReconciler(private val plugin: Story) : Listener {

    private val pending = ConcurrentHashMap<UUID, Int>() // playerId → taskId

    private companion object {
        const val MAX_DISTANCE_CHUNKS = 8
        const val MAX_DISTANCE_BLOCKS = MAX_DISTANCE_CHUNKS * 16
        const val MAX_DISTANCE_BLOCKS_SQ = MAX_DISTANCE_BLOCKS * MAX_DISTANCE_BLOCKS
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        val world = chunk.world
        val cx = chunk.x * 16 + 8
        val cz = chunk.z * 16 + 8

        val nearest = Bukkit.getOnlinePlayers()
            .filter { it.world == world }
            .minByOrNull {
                val dx = it.location.x - cx
                val dz = it.location.z - cz
                dx * dx + dz * dz
            } ?: return

        val ndx = nearest.location.x - cx
        val ndz = nearest.location.z - cz
        if (ndx * ndx + ndz * ndz > MAX_DISTANCE_BLOCKS_SQ) return

        val playerId = nearest.uniqueId
        pending[playerId]?.let { Bukkit.getScheduler().cancelTask(it) }

        val task = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                pending.remove(playerId)
                val player = Bukkit.getPlayer(playerId) ?: return@Runnable
                if (!player.isOnline) return@Runnable
                plugin.reconciliationService.requestNearby(
                    world = player.world.name,
                    x = player.location.x,
                    y = player.location.y,
                    z = player.location.z,
                    radius = plugin.configService.reconcileRadius,
                    source = "chunk_load",
                )
            },
            plugin.configService.chunkLoadDebounceTicks,
        )
        pending[playerId] = task.taskId
    }
}
