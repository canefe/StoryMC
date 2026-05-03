package com.canefe.story.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * Names in details are always raw; Go resolves per-perceiver labels via participantIds.
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

    data class Speech(
        val speakerId: String,
        val speakerName: String,
        val message: String,
        val addressedIds: List<String> = emptyList(),
        /** Character ID of the entity the speaker was looking directly at when speaking, if any. */
        val addressedToId: String? = null,
        /** Resolved name of the entity the speaker was looking at (filled in by Go per-perceiver). */
        val addressedToName: String? = null,
    ) : PerceptionDetails {
        override val type: String = "speech"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("speakerId", speakerId)
                put("speakerName", speakerName)
                put("message", message)
                if (addressedIds.isNotEmpty()) {
                    put(
                        "addressedIds",
                        buildJsonArray {
                            addressedIds.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
                addressedToId?.let { put("addressedToId", it) }
                addressedToName?.let { put("addressedToName", it) }
            }
    }

    /** Someone is looking directly at another character (tight gaze, ~15° cone). */
    data class Gaze(
        val gazerId: String,
        val gazerName: String,
        /** Null when this event is sent to the target themselves ("X is looking at you"). */
        val targetName: String? = null,
        val targetId: String? = null,
    ) : PerceptionDetails {
        override val type: String = "gaze"

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                put("gazerId", gazerId)
                put("gazerName", gazerName)
                targetName?.let { put("targetName", it) }
                targetId?.let { put("targetId", it) }
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
 *
 * [participantIds] maps each real name that appears in [details] to its stable character ID,
 * so the Go side can target the actual in-world entities regardless of what label the perceiver
 * knows them by.
 */
class PerceptionEvent(
    val characterId: String,
    val characterName: String,
    val source: String,
    val details: PerceptionDetails,
    val position: Position,
    val gameTimestamp: Long,
    val distance: Double,
    /** realName → characterId for every named participant in [details]. */
    val participantIds: Map<String, String> = emptyMap(),
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
            if (participantIds.isNotEmpty()) {
                put(
                    "participantIds",
                    buildJsonObject { participantIds.forEach { (name, id) -> put(name, id) } },
                )
            }
        }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }

    override fun getHandlers(): HandlerList = HANDLERS
}
