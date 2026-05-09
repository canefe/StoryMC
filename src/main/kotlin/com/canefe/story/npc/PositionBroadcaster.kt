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
 * Tick: every 2 seconds (40 ticks).
 *
 * NPC respawn is sim-authoritative: when a player enters a chunk, sim re-issues
 * NpcSpawnIntent for any NPC that should be present. StoryMC holds no position cache.
 */
class PositionBroadcaster(private val plugin: Story) {
    private var taskId: Int = -1

    fun start() {
        if (taskId != -1) return
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin,
            Runnable { tick() },
            40L,
            40L,
        )
    }

    fun stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId)
            taskId = -1
        }
    }

    private fun tick() {
        if (!plugin.isNpcRegistryReady) return

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
    }
}
