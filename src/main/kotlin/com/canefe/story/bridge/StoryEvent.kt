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
    val playerName: String,
    val message: String,
    val npcName: String? = null,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "player.message"
}

@Serializable
data class NPCDamagedEvent(
    val npcName: String,
    val attackerName: String?,
    val damage: Double,
    val cause: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.damaged"
}

@Serializable
data class NPCInteractionEvent(
    val npcName: String,
    val playerName: String,
    val type: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.interaction"
}

// ── Inbound intents (sim/LLM → Story) ──────────────────────────────

@Serializable
data class NPCSpeakIntent(
    val npcName: String,
    val message: String,
    val target: String? = null,
    val conversationId: Int? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.speak"
}

@Serializable
data class NPCMoveIntent(
    val npcName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.move"
}

@Serializable
data class NPCEmoteIntent(
    val npcName: String,
    val action: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.emote"
}
