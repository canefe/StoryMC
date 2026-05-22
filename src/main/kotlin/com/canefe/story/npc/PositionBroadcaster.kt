package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.CharacterPositionEvent
import com.canefe.story.util.characterId
import com.canefe.story.util.characterName
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity

/**
 * Periodically publishes NPC and player positions to the Go orchestrator so it can
 * keep MongoDB and the Bevy sim in sync without polling gRPC.
 *
 * Tick: every 200ms (4 ticks). Behavior systems in story-sim read these positions
 * each Update; slower rates make NPCs read stale positions and overshoot/loop.
 *
 * NPC respawn is sim-authoritative: when a player enters a chunk, sim re-issues
 * NpcSpawnIntent for any NPC that should be present. StoryMC holds no position cache.
 */
class PositionBroadcaster(private val plugin: Story) {
    private var taskId: Int = -1
    private var reconcileTaskId: Int = -1
    private val reconcileRadius: Double = 64.0
    private val reconcileIntervalTicks: Long = 100L

    fun start() {
        if (taskId == -1) {
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable { tick() },
                4L,
                4L,
            )
        }
        if (reconcileTaskId == -1) {
            reconcileTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable { reconcileTick() },
                reconcileIntervalTicks,
                reconcileIntervalTicks,
            )
        }
    }

    fun stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId)
            taskId = -1
        }
        if (reconcileTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reconcileTaskId)
            reconcileTaskId = -1
        }
    }

    private fun reconcileTick() {
        if (!plugin.configService.bridgeEnabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            val loc = player.location
            val world = loc.world?.name ?: continue
            plugin.reconciliationService.requestNearby(
                world = world,
                x = loc.x,
                y = loc.y,
                z = loc.z,
                radius = reconcileRadius,
                source = "interval:player=${player.name}",
            )
        }
    }

    private fun tick() {
        if (!plugin.isNpcRegistryReady) return
        if (!plugin.isCharacterRegistryReady) return

        val onlineCharIds = Bukkit.getOnlinePlayers()
            .mapNotNull { try { it.characterId } catch (_: Exception) { null } }
            .toSet()

        for (npc in plugin.npcRegistry.all()) {
            val entity = npc.entity as? LivingEntity ?: continue
            val loc = entity.location
            val world = loc.world?.name ?: continue
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            if (charId in onlineCharIds) continue
            plugin.eventBus.emit(
                CharacterPositionEvent(
                    characterId = charId,
                    name = npc.name,
                    x = loc.x,
                    y = loc.y,
                    z = loc.z,
                    world = world,
                    online = false,
                ),
            )
        }

        for (player in Bukkit.getOnlinePlayers()) {
            val charId = try { player.characterId } catch (_: Exception) { null } ?: continue
            val name = try { player.characterName } catch (_: Exception) { player.name }
            val loc = player.location
            val world = loc.world?.name ?: continue
            plugin.eventBus.emit(
                CharacterPositionEvent(
                    characterId = charId,
                    name = name,
                    x = loc.x,
                    y = loc.y,
                    z = loc.z,
                    world = world,
                    online = true,
                ),
            )
        }
        try {
            plugin.frontendReadinessTracker.markPositionsTicked()
        } catch (_: UninitializedPropertyAccessException) {
            // tracker not yet wired during tests — ignore
        }
    }
}
