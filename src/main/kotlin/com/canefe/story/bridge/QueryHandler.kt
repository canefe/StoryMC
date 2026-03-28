package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Location
import kotlin.math.sqrt

// ── Response DTOs ────────────────────────────────────────────────────────

@Serializable
data class StoryLocationDTO(
    val name: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String,
)

@Serializable
data class NearbyLocationDTO(
    val name: String,
    val distance: Double,
)

@Serializable
data class NearbyEntityDTO(
    val name: String,
    val distance: Double,
)

@Serializable
data class CharacterStateDTO(
    val type: String,
    val characterId: String? = null,
    val name: String,
    val location: StoryLocationDTO,
    val nearbyLocations: List<NearbyLocationDTO>,
    val nearbyPlayers: List<NearbyEntityDTO>,
    val nearbyNPCs: List<NearbyEntityDTO>,
    val inConversation: Boolean,
    val conversationId: Int? = null,
    val conversationParticipants: List<String>? = null,
)

@Serializable
data class WorldPlayerDTO(
    val name: String,
    val location: String?,
)

@Serializable
data class WorldNPCDTO(
    val name: String,
    val location: String?,
)

@Serializable
data class WorldStateDTO(
    val onlinePlayers: List<WorldPlayerDTO>,
    val spawnedNPCs: List<WorldNPCDTO>,
)

@Serializable
data class QueryErrorDTO(
    val error: String,
)

// ── Handler ──────────────────────────────────────────────────────────────

class QueryHandler(
    private val plugin: Story,
) {
    private val json = Json { encodeDefaults = true }

    fun initialize() {
        plugin.eventBus.onType("query.request") { event ->
            val data = event.toWireData() ?: return@onType
            val requestId = data["requestId"]?.toString()?.trim('"') ?: return@onType
            val method = data["method"]?.toString()?.trim('"') ?: return@onType

            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    val result =
                        when (method) {
                            "character_state" -> handleCharacterState(data)
                            "world_state" -> toJsonObject(handleWorldState())
                            else -> toJsonObject(QueryErrorDTO("Unknown query method: $method"))
                        }

                    val response =
                        buildJsonObject {
                            put("requestId", requestId)
                            put("method", method)
                            put("result", result)
                        }

                    plugin.eventBus.emit(
                        object : StoryEvent {
                            override val eventType: String = "query.response"

                            override fun toWireData(): JsonObject = response
                        },
                    )
                },
            )
        }
    }

    private fun handleCharacterState(data: JsonObject): JsonObject {
        val characterId =
            data["characterId"]?.toString()?.trim('"')
                ?: return toJsonObject(QueryErrorDTO("Missing characterId"))
        val radius = data["radius"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 30.0

        val citizensNpc = resolveNPC(characterId)
        if (citizensNpc != null) {
            return buildNPCState(citizensNpc, characterId, radius)
        }

        val player =
            Bukkit.getOnlinePlayers().find { p ->
                plugin.characterRegistry.getCharacterIdForPlayer(p) == characterId ||
                    p.uniqueId.toString() == characterId ||
                    p.name.equals(characterId, ignoreCase = true)
            }
        if (player != null) {
            return buildPlayerState(player, radius)
        }

        return toJsonObject(QueryErrorDTO("Character '$characterId' not found"))
    }

    private fun buildNPCState(
        npc: CitizensStoryNPC,
        characterId: String,
        radius: Double,
    ): JsonObject {
        val loc =
            npc.location
                ?: return toJsonObject(QueryErrorDTO("NPC '$characterId' has no location (not spawned?)"))

        val nearbyPlayers =
            NPCUtils.getNearbyPlayers(loc, radius).map { p ->
                NearbyEntityDTO(p.name, sqrt(p.location.distanceSquared(loc)))
            }

        val nearbyNPCs =
            CitizensAPI
                .getNPCRegistry()
                .filter { other ->
                    other.isSpawned &&
                        other.id != npc.id &&
                        other.entity.location.world == loc.world &&
                        other.entity.location.distanceSquared(loc) <= radius * radius
                }.map { other ->
                    NearbyEntityDTO(other.name, sqrt(other.entity.location.distanceSquared(loc)))
                }

        val conversation = plugin.conversationManager.getConversation(npc)
        val participants =
            conversation?.let {
                it.npcNames + it.players.mapNotNull { uuid -> Bukkit.getPlayer(uuid)?.name }
            }

        return toJsonObject(
            CharacterStateDTO(
                type = "npc",
                characterId = characterId,
                name = npc.name,
                location = resolveStoryLocation(loc),
                nearbyLocations = findNearbyLocations(loc, radius),
                nearbyPlayers = nearbyPlayers,
                nearbyNPCs = nearbyNPCs,
                inConversation = conversation != null,
                conversationId = conversation?.id,
                conversationParticipants = participants,
            ),
        )
    }

    private fun buildPlayerState(
        player: org.bukkit.entity.Player,
        radius: Double,
    ): JsonObject {
        val loc = player.location

        val nearbyNPCs =
            NPCUtils.getNearbyNPCs(player, radius).mapNotNull { storyNpc ->
                val npcLoc = storyNpc.location ?: return@mapNotNull null
                NearbyEntityDTO(storyNpc.name, sqrt(npcLoc.distanceSquared(loc)))
            }

        val nearbyPlayers =
            Bukkit
                .getOnlinePlayers()
                .filter { other ->
                    other.uniqueId != player.uniqueId &&
                        other.location.world == loc.world &&
                        other.location.distanceSquared(loc) <= radius * radius
                }.map { other ->
                    NearbyEntityDTO(other.name, sqrt(other.location.distanceSquared(loc)))
                }

        val conversation = plugin.conversationManager.getConversation(player)

        return toJsonObject(
            CharacterStateDTO(
                type = "player",
                name = player.name,
                location = resolveStoryLocation(loc),
                nearbyLocations = findNearbyLocations(loc, radius),
                nearbyPlayers = nearbyPlayers,
                nearbyNPCs = nearbyNPCs,
                inConversation = conversation != null,
                conversationId = conversation?.id,
            ),
        )
    }

    private fun handleWorldState(): WorldStateDTO {
        val players =
            Bukkit.getOnlinePlayers().map { p ->
                val storyLoc = plugin.locationManager.getLocationByPosition2D(p.location, 150.0)
                WorldPlayerDTO(p.name, storyLoc?.name)
            }

        val npcs =
            CitizensAPI
                .getNPCRegistry()
                .filter { it.isSpawned }
                .map { npc ->
                    val storyLoc = plugin.locationManager.getLocationByPosition2D(npc.entity.location, 150.0)
                    WorldNPCDTO(npc.name, storyLoc?.name)
                }

        return WorldStateDTO(players, npcs)
    }

    private fun resolveStoryLocation(loc: Location): StoryLocationDTO {
        val storyLoc = plugin.locationManager.getLocationByPosition2D(loc, 150.0)
        return StoryLocationDTO(
            name = storyLoc?.name,
            x = loc.x,
            y = loc.y,
            z = loc.z,
            world = loc.world?.name ?: "unknown",
        )
    }

    private fun findNearbyLocations(
        loc: Location,
        radius: Double,
    ): List<NearbyLocationDTO> =
        plugin.locationManager
            .getAllLocations()
            .filter { storyLoc ->
                val bl = storyLoc.bukkitLocation
                bl != null && bl.world == loc.world
            }.map { storyLoc ->
                val dist = storyLoc.bukkitLocation!!.distance(loc)
                NearbyLocationDTO(storyLoc.name, dist)
            }.filter { it.distance <= radius }
            .sortedBy { it.distance }

    private inline fun <reified T> toJsonObject(dto: T): JsonObject = json.encodeToJsonElement(dto).jsonObject

    private fun resolveNPC(characterId: String): CitizensStoryNPC? {
        val record =
            try {
                plugin.characterRegistry.getById(characterId)
            } catch (_: UninitializedPropertyAccessException) {
                null
            }

        if (record != null) {
            val config =
                try {
                    plugin.characterRegistry.getMinecraftConfig(characterId)
                } catch (_: UninitializedPropertyAccessException) {
                    null
                }

            config?.citizensUuid?.let { uuid ->
                val npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid)
                if (npc != null) return CitizensStoryNPC(npc)
            }

            config?.citizensNpcId?.let { id ->
                val npc = CitizensAPI.getNPCRegistry().getById(id)
                if (npc != null) return CitizensStoryNPC(npc)
            }

            val npc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == record.name }
            if (npc != null) return CitizensStoryNPC(npc)
        }

        return null
    }
}
