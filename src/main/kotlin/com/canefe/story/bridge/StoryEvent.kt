package com.canefe.story.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for all Story events that flow through the event bus.
 *
 * Implemented by:
 * - Pure data events (@Serializable data classes) for wire-only events
 * - Bukkit Event subclasses that also need to go over the wire
 *
 * Events that need serialization (for Redis) must implement [toWireData].
 */
interface StoryEvent {
    val eventType: String

    /**
     * Serialize this event to a JsonObject for transport over Redis.
     * Returns null if this event is local-only and shouldn't be published.
     */
    fun toWireData(): JsonObject? = null
}

/**
 * Marker for events that are fully serializable data classes.
 * These automatically provide [toWireData] via kotlinx serialization.
 */
interface SerializableStoryEvent : StoryEvent

/**
 * Envelope for serialized events going over Redis.
 */
@Serializable
data class BridgeMessage(
    val type: String,
    val data: JsonObject,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "story",
)

// ── Pure data events (wire-safe, no Bukkit dependencies) ────────────
// Note: ConversationStartEvent and ConversationEndEvent are Bukkit events
// that implement StoryEvent directly (see com.canefe.story.api.event).

@Serializable
data class PlayerMessageEvent(
    val characterId: String?,
    val playerName: String,
    val message: String,
    val npcCharacterId: String? = null,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "player.message"
}

@Serializable
data class NPCDamagedEvent(
    val characterId: String,
    val attackerCharacterId: String?,
    val damage: Double,
    val cause: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.damaged"
}

@Serializable
data class NPCInteractionEvent(
    val characterId: String,
    val playerCharacterId: String?,
    val playerName: String,
    val type: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.interaction"
}

// ── Inbound intents (sim/LLM → Story) ──────────────────────────────

@Serializable
data class NPCSpeakIntent(
    val characterId: String,
    val message: String,
    val target: String? = null,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.speak"
}

@Serializable
data class NPCMoveIntent(
    val characterId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.move"
}

@Serializable
data class NPCEmoteIntent(
    val characterId: String,
    val action: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.emote"
}

/**
 * Emitted periodically with the list of NPCs near each player.
 * Used by Go's AgentManager to preemptively research NPC context.
 */
@Serializable
data class PlayerProximityEvent(
    val playerCharacterId: String?,
    val playerName: String,
    val nearbyCharacterIds: List<String>,
) : SerializableStoryEvent {
    override val eventType: String get() = "player.proximity"
}

/**
 * Updates a character's perception radius. Sent by the sim when character stats change.
 */
@Serializable
data class CharacterStatsUpdate(
    val characterId: String,
    val perceptionRadius: Double? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "character.stats_update"
}

/**
 * Emitted when a GM uses /g to speak as an NPC.
 * Lets the orchestrator log and optionally enrich GM narration.
 */
@Serializable
data class GMSpeakEvent(
    val playerCharacterId: String?,
    val gmName: String,
    val npcCharacterId: String?,
    val npcName: String,
    val message: String,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "gm.speak"
}

/**
 * Emitted after an NPC message has been broadcast to nearby players.
 * Covers all NPC speech (GM-directed, LLM-generated, intent-driven).
 */
@Serializable
data class CharacterSpokeEvent(
    val characterId: String?,
    val characterName: String,
    val message: String,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "character.spoke"
}
