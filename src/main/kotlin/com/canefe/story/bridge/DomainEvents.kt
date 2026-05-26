package com.canefe.story.bridge

import kotlinx.serialization.SerialName
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

@Serializable
data class SessionStartEvent(
    val initialPlayerUuids: List<String>,
    val startTimeGame: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.session.start"
}

@Serializable
data class SessionEndEvent(
    val endTimeGame: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.session.end"
}

@Serializable
data class SessionAddPlayerEvent(
    val playerUuid: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.session.add_player"
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

@Serializable
data class SessionStartedIntent(
    val sessionId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.session.started"
}

@Serializable
data class SessionEndedIntent(
    val sessionId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.session.ended"
}

@Serializable
data class SessionNarrationIntent(
    val sessionId: String,
    val text: String,
    val playerNames: List<String>,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.session.narration"
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
    val actionId: String? = null,
    val behaviorId: String? = null,
    val actionLabel: String? = null,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.state"
}

/**
 * Go → Plugin: an item changed hands between two sim characters (trade/gift/give).
 * Emitted by the sim's transfer chokepoint; the plugin resolves both entities and
 * relays a story:item_transfer packet for the client to visualize.
 */
@Serializable
data class NpcItemTransferIntent(
    val fromCharacterId: String,
    val toCharacterId: String,
    val item: String,
    val qty: Int,
    val reason: String = "give",
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.item_transfer"
}

/**
 * Plugin → Go → story-sim: inject a perception stimulus into a character's StimulusBuffer.
 * perceiverCharId sees/hears targetCharId (or a static affordance).
 */
/**
 * Sim -> Go -> Plugin: walk an NPC to a world position and report the outcome.
 *
 * The single movement primitive: replaces the old `npc.move` (fire-and-forget,
 * no outcome) and the `navigate_to` frontend primitive. The plugin resolves the
 * NPC, kicks off pathing, and watches arrival -- emitting IntentCompletedEvent on
 * arrival or IntentRejectedEvent on stuck/timeout/missing-NPC, echoing intentId.
 *
 * `world` may be blank (fall back to the NPC's current world) and the thresholds
 * may be 0.0 (fall back to the watcher's built-in defaults).
 */
@Serializable
data class GoToExecIntent(
    val characterId: String,
    val intentId: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val world: String = "",
    val arrivalRange: Double = 0.0,
    val stallTimeout: Double = 0.0,
    val maxDuration: Double = 0.0,
) : SerializableStoryEvent {
    override val eventType: String get() = "go_to"
}

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
 * Plugin → Go: the DM's decision for a single permission.ask. accepted=false
 * covers explicit deny, timeout, and no-DM-online — story-go does not
 * distinguish between them. Carries the same requestId Go sent.
 */
@Serializable
data class PermissionResponseEvent(
    val requestId: String,
    val accepted: Boolean,
) : SerializableStoryEvent {
    override val eventType: String get() = "permission.response"
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
    // flee_from: anchor (XZ) the NPC should run away from + distance band
    val fromX: Double = 0.0,
    val fromZ: Double = 0.0,
    val minDist: Double = 0.0,
    val maxDist: Double = 0.0,
    // attempt_hit: directional combat swing direction. Null = plugin picks.
    // Wire-format string ("overhead"/"left"/"right"/"thrust") parsed via SwingDir.fromWire.
    val swingDirection: String? = null,
    // Sim-minted correlation id. Echoed back via IntentCompletedEvent/IntentRejectedEvent.
    // Empty string when absent (older sim builds).
    val intentId: String = "",
) : SerializableStoryEvent {
    override val eventType: String get() = "frontend.intent"
}

/**
 * Why an intent was rejected by the actuator layer. Typed so sim behaviors can
 * pattern-match on the reason instead of parsing strings.
 */
@Serializable
enum class RejectionReason {
    NPC_NOT_FOUND,
    TARGET_NOT_FOUND,
    OUT_OF_RANGE,
    NPC_DEAD,
    NPC_BUSY,
    INVALID_PRIMITIVE,
    UNSUPPORTED_BACKEND,
    EXECUTION_ERROR,

    /**
     * Pathfinding could not reach the destination: the NPC stopped making
     * progress (stuck) or the arrival watcher timed out before getting within
     * range. Sim's `navigate_to` arrival watcher emits this so behaviors can
     * re-plan instead of believing they arrived. See [IntentExecutor].
     */
    UNREACHABLE,

    /**
     * A newer intent for the same NPC superseded this one before it finished.
     * Emitted for the OLD intentId when a second go_to arrives while the first
     * is still walking, so the sim gets an outcome instead of leaking the old
     * PendingIntents entry. See [IntentExecutor.executeGoTo].
     */
    SUPERSEDED,

    /**
     * The intent's deadline elapsed before it could complete. Included for
     * parity with the sim's rejection-reason superset; the navigate_to watcher
     * currently maps timeouts to [UNREACHABLE].
     */
    TIMEOUT,
}

/**
 * Marker interface so listeners can subscribe to all intent outcomes at once
 * (`eventBus.on<IntentOutcomeEvent> { ... }`). Wire serialization stays on the
 * concrete subtypes — kotlinx polymorphic serializers are unused here.
 */
sealed interface IntentOutcomeEvent : SerializableStoryEvent {
    val intentId: String
    val characterId: String
    val primitive: String
    val timestamp: Long
}

/**
 * Plugin → Go → sim: an intent was successfully applied.
 * Sim's blackboard treats this as "stop re-issuing".
 */
@Serializable
data class IntentCompletedEvent(
    override val intentId: String,
    override val characterId: String,
    override val primitive: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : IntentOutcomeEvent {
    override val eventType: String get() = "intent.completed"
}

/**
 * Plugin → Go → sim: an intent could not be applied. `reason` is typed so sim
 * behaviors can decide whether to retry, re-plan, or abandon.
 */
@Serializable
data class IntentRejectedEvent(
    override val intentId: String,
    override val characterId: String,
    override val primitive: String,
    val reason: RejectionReason,
    override val timestamp: Long = System.currentTimeMillis(),
) : IntentOutcomeEvent {
    override val eventType: String get() = "intent.rejected"
}

/**
 * Plugin → sim: directional combat hit outcome stimulus (spec §6).
 * Sim consumers can use this to feed CombatBrain state, drive flee/morale
 * decisions, or update relationships based on combat events.
 *
 * Outcome wire values: "unblocked" / "parry" / "perfect_block" / "partial_block" / "bad_block"
 * Direction wire values: "overhead" / "left" / "right" / "thrust"
 */
@Serializable
data class CombatHitOutcomeEvent(
    val attackerCharId: String?,
    val defenderCharId: String?,
    val direction: String,
    val outcome: String,
    val damage: Double,
) : SerializableStoryEvent {
    override val eventType: String get() = "combat.hit_outcome"
}

/**
 * Plugin → Go: the DM grabbed or released an NPC for live puppeteering. story-go
 * updates its GrabRegistry; while grabbed, it drops that NPC's sim go_to/npc.speak.
 */
@Serializable
data class DMControlToggleEvent(
    val characterId: String,
    val grabbed: Boolean,
) : SerializableStoryEvent {
    override val eventType: String get() = "dm.control.toggle"
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

/**
 * Plugin → Go → sim: operator-issued pause request. story-go forwards this as
 * a NATS `frontend_pause` message that pauses story-sim's `Time<Virtual>` and
 * transitions phase back to Boot. A subsequent FrontendReadyEvent (manual
 * resume) re-unpauses the sim.
 */
@Serializable
data class FrontendPauseEvent(
    val world: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "frontend.pause"
}

// ── Sim-authoring intents (Plugin → Go → story-sim) ──────────────────

/**
 * Plugin → Go: request that story-go create a new location.
 * story-go resolves the template via the sim, merges defaults, writes mongo,
 * publishes spawn_location to the sim, and replies with [LocationCreateResponse].
 *
 * Field semantics:
 * - `radius == 0.0` means "no override" (use template's DefaultRadius).
 * - `tags == ""` means "no override" (use template's DefaultTags).
 * - `template == ""` means a bare instance (no template lookup).
 */
@Serializable
data class LocationCreateRequest(
    @SerialName("requestId") val requestId: String,
    val name: String,
    val template: String = "",
    val world: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val radius: Double = 0.0,
    val tags: String = "", // comma-joined; empty = inherit from template
) : SerializableStoryEvent {
    override val eventType: String get() = "location.create.request"
}

/**
 * Plugin → Go → sim: register a named location entity in the sim world.
 */
@Serializable
data class LocationSpawnIntent(
    val id: String,
    @SerialName("instance_name") val instanceName: String,
    // Optional location-def id the sim uses to initialize tags/radius when this
    // instance omits them (template-as-initializer). Empty = no template.
    val template: String = "",
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double = 8.0,
    val tags: String = "", // comma-joined
) : SerializableStoryEvent {
    override val eventType: String get() = "location.spawn"
}

/**
 * Plugin → Go → sim: set the trade offers available from a merchant NPC.
 * `offers` is a JSON array string forwarded verbatim to the sim.
 */
@Serializable
data class NpcSetOffersIntent(
    @SerialName("character_id") val characterId: String,
    val name: String,
    val offers: String, // JSON array string
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.set_offers"
}

/**
 * Plugin → Go → sim: give an item to an NPC's inventory.
 */
@Serializable
data class NpcGiveItemIntent(
    @SerialName("character_id") val characterId: String,
    val name: String,
    val item: String,
    val qty: Int,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.give_item"
}

/**
 * Plugin → Go → sim: add a personality / skill trait to an NPC.
 */
@Serializable
data class NpcGiveTraitIntent(
    @SerialName("character_id") val characterId: String,
    val name: String,
    val trait: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.give_trait"
}

/**
 * Plugin → Go → sim: set a named need value for an NPC (e.g. hunger, rest).
 */
@Serializable
data class NpcSetNeedIntent(
    @SerialName("character_id") val characterId: String,
    val name: String,
    val need: String,
    val value: Double,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.set_need"
}

/**
 * Plugin → Go → sim: set a named stat value for an NPC (e.g. strength, agility).
 */
@Serializable
data class NpcSetStatIntent(
    @SerialName("character_id") val characterId: String,
    val name: String,
    val stat: String,
    val value: Double,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.set_stat"
}

/**
 * Plugin → Go → sim: tell an NPC about a location (adds it to their known-locations set).
 */
@Serializable
data class NpcKnowIntent(
    @SerialName("character_id") val characterId: String,
    @SerialName("location_id") val locationId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "npc.know"
}
