package com.canefe.story.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

@Serializable
data class Position(
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String,
)

/**
 * Typed perception details. Each source type defines its own data class.
 * Serializes to a [JsonObject] with a `type` discriminator.
 */
sealed interface PerceptionDetails {
    val type: String

    fun toJson(): JsonObject

    data class Combat(
        val attacker: String,
        val victim: String,
        val damage: Double,
    ) : PerceptionDetails {
        override val type: String = "combat"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("attacker", attacker)
                put("victim", victim)
                put("damage", damage)
            }
    }

    data class Death(
        val deceased: String,
        val killer: String? = null,
    ) : PerceptionDetails {
        override val type: String = "death"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("deceased", deceased)
                killer?.let { put("killer", it) }
            }
    }

    data class Weather(
        val state: String, // "rain", "clear", "thunder"
    ) : PerceptionDetails {
        override val type: String = "weather"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("state", state)
            }
    }

    data class Movement(
        val entity: String,
        val action: String, // "arrived", "left", "entered"
        val area: String? = null,
    ) : PerceptionDetails {
        override val type: String = "movement"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("entity", entity)
                put("action", action)
                area?.let { put("area", it) }
            }
    }

    data class Generic(
        val description: String,
    ) : PerceptionDetails {
        override val type: String = "generic"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("description", description)
            }
    }
}

/**
 * Bukkit event + [StoryEvent] fired when a character perceives something in the world.
 * Characters = NPCs + Players. Story is the eyes, external systems are the brain.
 *
 * Emitted by [PerceptionService] for each character within perception range of a world event.
 */
class PerceptionEvent(
    val characterId: String,
    val characterName: String,
    val source: String,
    val details: PerceptionDetails,
    val position: Position,
    val gameTimestamp: Long,
    val distance: Double,
) : Event(),
    StoryEvent {
    override val eventType: String get() = "character.perceived"

    override fun toWireData(): JsonObject =
        buildJsonObject {
            put("characterId", characterId)
            put("characterName", characterName)
            put("source", source)
            put("details", details.toJson())
            put(
                "position",
                buildJsonObject {
                    put("x", position.x)
                    put("y", position.y)
                    put("z", position.z)
                    put("world", position.world)
                },
            )
            put("timestamp", gameTimestamp)
            put("distance", distance)
        }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }

    override fun getHandlers(): HandlerList = HANDLERS
}
