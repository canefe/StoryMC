package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Location

/**
 * Observes world events and emits [PerceptionEvent]s for nearby characters.
 * Characters = NPCs + Players. Story is the eyes, external systems are the brain.
 */
class PerceptionService(
    private val plugin: Story,
) {
    private val defaultPerceptionRadius: Double = 15.0
    private val characterRadii = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private var proximityTaskId: Int = -1

    /**
     * Starts periodic proximity publishing — every 5 seconds, emits which NPCs
     * are near each online player so the Go orchestrator can preemptively research them.
     */
    fun startProximityPublisher() {
        if (proximityTaskId != -1) {
            Bukkit.getScheduler().cancelTask(proximityTaskId)
        }
        proximityTaskId =
            Bukkit
                .getScheduler()
                .runTaskTimer(
                    plugin,
                    Runnable { publishProximity() },
                    100L, // 5 second delay
                    100L, // every 5 seconds
                ).taskId
    }

    fun stopProximityPublisher() {
        if (proximityTaskId != -1) {
            Bukkit.getScheduler().cancelTask(proximityTaskId)
            proximityTaskId = -1
        }
    }

    private fun publishProximity() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (plugin.playerManager.isPlayerDisabled(player)) continue

            val playerName =
                try {
                    com.canefe.story.util.EssentialsUtils
                        .getNickname(player.name)
                } catch (_: Exception) {
                    player.name
                }

            val nearbyNPCs =
                try {
                    NPCUtils
                        .getNearbyNPCs(player, defaultPerceptionRadius)
                        .map { it.name }
                } catch (_: Exception) {
                    emptyList()
                }

            if (nearbyNPCs.isNotEmpty()) {
                plugin.eventBus.emit(PlayerProximityEvent(playerName, nearbyNPCs))
            }
        }
    }

    /**
     * Sets the perception radius for a specific character.
     * Called when the sim pushes character stats via the bridge.
     */
    fun setPerceptionRadius(
        characterName: String,
        radius: Double,
    ) {
        characterRadii[characterName] = radius
    }

    /**
     * Gets the perception radius for a character, falling back to the default.
     */
    fun getPerceptionRadius(characterName: String): Double = characterRadii[characterName] ?: defaultPerceptionRadius

    /**
     * Observe an event at a location. Every character (NPC or player) within
     * perception radius gets a [PerceptionEvent] emitted to both Bukkit and the [StoryEventBus].
     *
     * @param details Structured details of what happened
     * @param epicenter Where it happened
     * @param source Category of the event (combat, death, weather, movement, etc.)
     * @param exclude Optional character name to exclude (e.g. the one who caused the event)
     */
    fun observe(
        details: PerceptionDetails,
        epicenter: Location,
        source: String,
        exclude: String? = null,
    ) {
        val world = epicenter.world ?: return
        val pos = Position(epicenter.x, epicenter.y, epicenter.z, world.name)
        val gameTime = plugin.timeService.getCurrentGameTime()

        // Find nearby NPCs
        try {
            for (citizenNpc in CitizensAPI.getNPCRegistry()) {
                if (!citizenNpc.isSpawned) continue
                val entity = citizenNpc.entity ?: continue
                if (entity.world != world) continue

                val npc: StoryNPC = CitizensStoryNPC(citizenNpc)
                val name = npc.name
                if (name == exclude) continue

                val distance = entity.location.distance(epicenter)
                if (distance > getPerceptionRadius(name)) continue

                val perception =
                    PerceptionEvent(
                        characterId = npc.uniqueId.toString(),
                        characterName = name,
                        source = source,
                        details = details,
                        position = pos,
                        gameTimestamp = gameTime,
                        distance = distance,
                    )
                Bukkit.getPluginManager().callEvent(perception)
                plugin.eventBus.emit(perception)
            }
        } catch (_: Exception) {
        }

        // Find nearby players
        for (player in world.players) {
            // Skip Citizens NPC players
            try {
                if (CitizensAPI.getNPCRegistry().isNPC(player)) continue
            } catch (_: Exception) {
            }

            val name =
                try {
                    EssentialsUtils.getNickname(player.name)
                } catch (_: Exception) {
                    player.name
                }
            if (name == exclude) continue

            val distance = player.location.distance(epicenter)
            if (distance > getPerceptionRadius(name)) continue

            val perception =
                PerceptionEvent(
                    characterId = player.uniqueId.toString(),
                    characterName = name,
                    source = source,
                    details = details,
                    position = pos,
                    gameTimestamp = gameTime,
                    distance = distance,
                )
            Bukkit.getPluginManager().callEvent(perception)
            plugin.eventBus.emit(perception)
        }
    }
}
