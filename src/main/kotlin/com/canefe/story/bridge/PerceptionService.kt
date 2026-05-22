package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.util.*
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
        // Sim owns spatial awareness when active — suppress plugin-side proximity spam.
        // Also suppress while operator-paused so we don't render stimuli for a frozen world.
        if (plugin.simActive) return
        if (plugin.simPaused) return
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
                plugin.eventBus.emit(
                    PlayerProximityEvent(
                        playerCharacterId = player.characterId,
                        playerName = playerName,
                        nearbyCharacterIds = nearbyNPCs,
                    ),
                )
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
     * Emit a perception directly to a single named character regardless of distance.
     * Used when the character is the direct participant (e.g. the attacker in combat).
     *
     * [participants] maps real name → characterId for every named entity in [details].
     * Go resolves recognition labels from these IDs; Kotlin sends the raw names + IDs only.
     */
    fun observeOne(
        characterName: String,
        perceiverCharId: String,
        entity: org.bukkit.entity.Entity,
        details: PerceptionDetails,
        epicenter: Location,
        source: String,
        participants: Map<String, String> = emptyMap(),
    ) {
        if (plugin.simPaused) return
        val world = epicenter.world ?: return
        val pos = Position(epicenter.x, epicenter.y, epicenter.z, world.name)
        val gameTime = plugin.timeService.getCurrentGameTime()
        val distance = entity.location.distance(epicenter)
        val perception =
            PerceptionEvent(
                characterId = perceiverCharId,
                characterName = characterName,
                source = source,
                details = details,
                position = pos,
                gameTimestamp = gameTime,
                distance = distance,
                participantIds = participants,
            )
        Bukkit.getPluginManager().callEvent(perception)
        plugin.eventBus.emit(perception)
    }

    /**
     * Observe an event at a location. Every character (NPC or player) within
     * perception radius gets a [PerceptionEvent] emitted to both Bukkit and the [StoryEventBus].
     *
     * [participants] maps real name → characterId for every named entity in [details].
     * Names in [details] are always raw; Go resolves what each perceiver calls them via
     * the recognition system using the IDs in [participantIds].
     */
    fun observe(
        details: PerceptionDetails,
        epicenter: Location,
        source: String,
        exclude: String? = null,
        participants: Map<String, String> = emptyMap(),
        excludeSet: Set<String> = emptySet(),
    ) {
        if (plugin.simPaused) return
        val world = epicenter.world ?: return
        val pos = Position(epicenter.x, epicenter.y, epicenter.z, world.name)
        val gameTime = plugin.timeService.getCurrentGameTime()
        val emitted = mutableSetOf<String>()

        fun emitFor(perceiverCharId: String, perceiverName: String, distance: Double) {
            if (!emitted.add(perceiverCharId)) return
            if (perceiverName in excludeSet) return
            val perception =
                PerceptionEvent(
                    characterId = perceiverCharId,
                    characterName = perceiverName,
                    source = source,
                    details = details,
                    position = pos,
                    gameTimestamp = gameTime,
                    distance = distance,
                    participantIds = participants,
                )
            Bukkit.getPluginManager().callEvent(perception)
            plugin.eventBus.emit(perception)
        }

        // Find nearby NPCs — Citizens registry
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
                val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: npc.uniqueId.toString()
                emitFor(charId, name, distance)
            }
        } catch (_: Exception) {
        }

        // Find nearby NPCs — StoryNPCRegistry (e.g. MythicMob-backed NPCs)
        if (plugin.isNpcRegistryReady && plugin.isCharacterRegistryReady) {
            for (npc in plugin.npcRegistry.all()) {
                val entity = npc.entity ?: continue
                if (entity.world != world) continue
                val name = npc.name
                if (name == exclude) continue
                val distance = entity.location.distance(epicenter)
                if (distance > getPerceptionRadius(name)) continue
                val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: npc.uniqueId.toString()
                emitFor(charId, name, distance)
            }
        }

        // Find nearby players
        for (player in world.players) {
            try {
                if (CitizensAPI.getNPCRegistry().isNPC(player)) continue
            } catch (_: Exception) {
            }
            val name = try { player.characterName } catch (_: Exception) { player.name }
            if (name == exclude) continue
            val distance = player.location.distance(epicenter)
            if (distance > getPerceptionRadius(name)) continue
            val charId = player.characterId ?: player.uniqueId.toString()
            emitFor(charId, name, distance)
        }
    }
}
