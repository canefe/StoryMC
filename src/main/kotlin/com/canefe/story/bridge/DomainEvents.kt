package com.canefe.story.bridge

import kotlinx.serialization.Serializable

// ── Outbound domain events (Plugin → Go) ─────────────────────────────
// These replace direct storage mutations. The plugin observes/perceives
// and emits these events; Go owns all persistence and mutation logic.

@Serializable
data class CharacterSaveRequestedEvent(
    val characterId: String,
    val name: String,
    val role: String,
    val context: String,
    val appearance: String,
    val avatar: String,
    val locationName: String?,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.character.save"
}

@Serializable
data class CharacterDeleteRequestedEvent(
    val characterId: String,
    val name: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.character.delete"
}

@Serializable
data class MemoryObservedEvent(
    val characterId: String,
    val content: String,
    val significance: Double = 1.0,
    val gameCreatedAt: Long = 0,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.memory.observed"
}

@Serializable
data class RumorObservedEvent(
    val content: String,
    val location: String,
    val significance: Double,
    val gameCreatedAt: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.rumor.observed"
}

@Serializable
data class QuestAssignRequestedEvent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.assign"
}

@Serializable
data class QuestAssignFromIntentEvent(
    val playerName: String,
    val questId: String,
    val questTitle: String,
    val questDescription: String,
    val npcName: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.assign_from_intent"
}

@Serializable
data class QuestProgressObservedEvent(
    val playerName: String,
    val questId: String,
    val objectiveType: String? = null,
    val target: String? = null,
    val progress: Int = 1,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.progress"
}

@Serializable
data class QuestCompleteRequestedEvent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.complete"
}

@Serializable
data class SessionFeedEvent(
    val text: String,
    val force: Boolean = false,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.session.feed"
}

// ── Sim status (sim → Go → Plugin) ───────────────────────────────────

/**
 * Forwarded by the Go orchestrator when the Bevy sim publishes a heartbeat.
 * The plugin uses this to toggle off its own NPC simulation while the sim is active.
 */
@Serializable
data class SimStatusEvent(
    val running: Boolean,
) : SerializableStoryEvent {
    override val eventType: String get() = "sim.status"
}

// ── Inbound intents (Go → Plugin) ────────────────────────────────────
// Go processes domain events and sends intents back to update local state.

@Serializable
data class QuestAssignIntent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.assign"
}

@Serializable
data class QuestUpdateIntent(
    val playerName: String,
    val questId: String,
    val objectiveType: String? = null,
    val target: String? = null,
    val progress: Int = 1,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.update"
}

@Serializable
data class QuestCompleteIntent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.complete"
}

@Serializable
data class CharacterUpdateIntent(
    val characterId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.character.update"
}

/**
 * Go → Plugin: send a named MythicMobs signal to an NPC.
 * The signal name is a motor command (AI_Run, AI_Fight, AI_Idle, etc.).
 * Go/Bevy decides *when* to send it; Story just fires it at the mob.
 *
 * [sourceCharacterId] is optional — if provided, the signal's @trigger will
 * be that character's entity, so MythicMobs skills using @trigger work correctly.
 */
@Serializable
data class NPCSignalIntent(
    val characterId: String,
    val signal: String,
    val sourceCharacterId: String? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.signal"
}

/**
 * Go → Plugin: spawn an NPC into the Minecraft world if not already present.
 * Sent when the sim initialises and for each character near an online player.
 */
@Serializable
data class NpcSpawnIntent(
    val characterId: String,
    val name: String,
    val race: String = "human",
    val mobTemplate: String = "Character",
    val x: Double = 0.0,
    val y: Double = 64.0,
    val z: Double = 0.0,
    val world: String = "",
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.spawn"
}

/**
 * Go → Plugin: live position/health update for a sim-controlled NPC.
 * Forwarded from the Bevy sim's entity state broadcast.
 */
@Serializable
data class NpcStateIntent(
    val characterId: String,
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String = "",
    val health: Double = -1.0,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.state"
}

/**
 * Plugin → Go → story-sim: inject a perception stimulus into a character's StimulusBuffer.
 * perceiverCharId sees/hears targetCharId (or a static affordance).
 */
@Serializable
data class PerceptionStimulusEvent(
    val perceiverCharId: String,
    val targetCharId: String?,        // null for affordance targets
    val targetAffordanceId: String?,  // MongoDB affordance id if target is an affordance
    val stimulusType: String,         // "sight" | "proximity" | "sound"
    val strength: Float,
    val x: Double,
    val y: Double,
    val z: Double,
    val tags: List<String> = emptyList(),
) : SerializableStoryEvent {
    override val eventType: String get() = "perception.stimulus"
}

/**
 * Plugin → Go: register a world affordance (block/location) so story-sim can spawn it as an ECS entity.
 */
@Serializable
data class SpawnAffordanceEvent(
    val affordanceId: String,
    val affordanceTypeId: String,
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String,
    val capacity: Int = 0,
) : SerializableStoryEvent {
    override val eventType: String get() = "affordance.spawn"
}

/**
 * Go → Plugin: affordance type definitions published by story-sim on startup.
 * Cached by the plugin so GMs can register world blocks against known affordance IDs.
 */
@Serializable
data class AffordanceTypeDef(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val use_range: Double = 0.0,
    val use_duration: Double = 0.0,
    val capacity: Int = 0,
)

@Serializable
data class SimAffordanceRegistryEvent(
    val types: List<AffordanceTypeDef> = emptyList(),
) : SerializableStoryEvent {
    override val eventType: String get() = "sim.affordance_registry"
}

/**
 * Plugin → Go: live position for any character (NPC or player), sent periodically.
 */
@Serializable
data class CharacterPositionEvent(
    val characterId: String,
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String,
    val online: Boolean = false,
) : SerializableStoryEvent {
    override val eventType: String get() = "character.position"
}

/**
 * Plugin → Go: perception event from a MythicMobs NPC via the [AI_PERCEPT] chat channel.
 * Carries the raw event type and the trigger entity's name so Go can route to the right agent.
 */
@Serializable
data class NPCPerceptEvent(
    val characterId: String,
    val characterName: String,
    val perceptType: String,
    val triggerName: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.percept"
}

/**
 * Plugin → Go: a player's decision response forwarded from a Fabric client.
 * Stamped with the server-authoritative characterId before emission.
 */
@Serializable
data class DecisionResponseEvent(
    val decisionId: String,
    val characterId: String,
    val choiceId: String? = null,
    val freeformText: String? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "decision.response"
}

/**
 * Sim → Plugin: a frontend primitive intent from a behavior hook.
 * The plugin executes it against the in-world NPC.
 */
@Serializable
data class FrontendIntentEvent(
    val primitive: String,
    val characterId: String,
    val targetCharId: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val maxYaw: Float = 0f,
    val maxPitch: Float = 0f,
    val useEyeLocation: Boolean = false,
) : SerializableStoryEvent {
    override val eventType: String get() = "frontend.intent"
}

/**
 * Plugin → Go: ask story-go for alive NPCs near a point. Response carries
 * `NpcSpawnIntent`s for each character that should be in-world.
 */
@Serializable
data class NpcSpawnQueryEvent(
    val requestId: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.spawn_query"
}

/**
 * Go → Plugin: reply to a NpcSpawnQueryEvent, addressed to the originating
 * frontend. `requestId` echoes the request.
 */
@Serializable
data class NpcSpawnQueryResponseEvent(
    val requestId: String,
    val intents: List<NpcSpawnIntent> = emptyList(),
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.spawn_query_response"
}

/**
 * Plugin → Go → sim: declare that the plugin has finished initial
 * reconciliation and at least one position tick has been broadcast.
 * story-go forwards this as a NATS `frontend_ready` message that
 * unpauses story-sim's `Time<Virtual>`.
 */
@Serializable
data class FrontendReadyEvent(
    val world: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "frontend.ready"
}
