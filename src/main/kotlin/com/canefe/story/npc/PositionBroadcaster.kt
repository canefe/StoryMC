package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.CharacterPositionEvent
import com.canefe.story.bridge.IntentExecutor
import com.canefe.story.bridge.NpcSpawnIntent
import com.canefe.story.util.characterId
import com.canefe.story.util.characterName
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity

/**
 * Periodically publishes NPC and player positions to the Go orchestrator so it can
 * keep MongoDB and the Bevy sim in sync without polling gRPC.
 *
 * Also runs a respawn check every 10 seconds to re-spawn any missing NPC entities.
 *
 * Tick: every 2 seconds (40 ticks).
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
        respawnMissing()

        val onlineCharIds = Bukkit.getOnlinePlayers()
            .mapNotNull { try { it.characterId } catch (_: Exception) { null } }
            .toSet()

        for (npc in plugin.npcRegistry.all()) {
            val entity = npc.entity as? LivingEntity ?: continue
            val loc = entity.location
            val world = loc.world?.name ?: continue
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            if (charId in onlineCharIds) {
                lastKnownPositions.remove(charId) // player online — don't respawn their stand-in
                continue
            }
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
            lastKnownPositions[charId] = PositionSnapshot(npc.name, loc.x, loc.y, loc.z, world)
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

    /**
     * Re-spawns any NPC whose entity is missing from the world.
     * Uses the last known position recorded during position broadcasting.
     */
    private fun respawnMissing() {
        if (!plugin.isCharacterRegistryReady) return
        val onlineCharIds = Bukkit.getOnlinePlayers()
            .mapNotNull { try { it.characterId } catch (_: Exception) { null } }
            .toSet()

        val spawnedCharIds = plugin.npcRegistry.all()
            .filter { it.isSpawned }
            .mapNotNull { npc ->
                npc.entity?.persistentDataContainer?.get(
                    com.canefe.story.npc.mythicmobs.MythicMobNPCKeys.CHARACTER_ID,
                    com.canefe.story.npc.mythicmobs.MythicMobNPCKeys.STRING,
                ) ?: plugin.characterRegistry.getCharacterIdForNPC(npc)
            }
            .toSet()

        val players = Bukkit.getOnlinePlayers()
        for ((charId, snapshot) in lastKnownPositions) {
            if (charId in onlineCharIds) continue
            if (charId in spawnedCharIds) continue
            if (snapshot.world.isBlank()) continue
            val nearPlayer = players.any { p ->
                val ploc = p.location
                ploc.world?.name == snapshot.world &&
                    sqDist(ploc.x, ploc.y, ploc.z, snapshot.x, snapshot.y, snapshot.z) <= RESPAWN_RADIUS_SQ
            }
            if (!nearPlayer) continue
            plugin.logger.info("[PositionBroadcaster] Respawning missing NPC $charId (${snapshot.name})")
            IntentExecutor.executeNpcSpawnIntent(
                plugin,
                NpcSpawnIntent(
                    characterId = charId,
                    name = snapshot.name,
                    x = snapshot.x,
                    y = snapshot.y,
                    z = snapshot.z,
                ),
            )
        }
    }

    /** Called when a NpcStateIntent arrives from the sim so positions are known before any entity spawns. */
    fun updateFromSim(characterId: String, name: String, x: Double, y: Double, z: Double, world: String) {
        val resolved = world.ifBlank { Bukkit.getWorlds().firstOrNull()?.name ?: "" }
        lastKnownPositions[characterId] = PositionSnapshot(name, x, y, z, resolved)
    }

    private data class PositionSnapshot(val name: String, val x: Double, val y: Double, val z: Double, val world: String = "")
    private val lastKnownPositions = mutableMapOf<String, PositionSnapshot>()

    private fun sqDist(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return dx * dx + dy * dy + dz * dz
    }

    private companion object {
        const val RESPAWN_RADIUS = 96.0
        const val RESPAWN_RADIUS_SQ = RESPAWN_RADIUS * RESPAWN_RADIUS
    }
}
