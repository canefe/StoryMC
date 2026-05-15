package com.canefe.story.intelligence

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for intelligence requests sent to the Go orchestrator via WebSocket.
 * These are serialized to JSON and wrapped in a BridgeMessage envelope.
 */

@Serializable
data class MessageDTO(
    val role: String,
    val content: String,
)

@Serializable
data class GenerateNPCResponseRequest(
    val requestId: String,
    val method: String = Method.GENERATE_NPC_RESPONSE,
    val characterId: String,
    val conversationId: Int,
    val history: List<MessageDTO>,
    val characterIds: List<String>,
    val playerCharacterIds: List<String>,
)

@Serializable
data class SelectNextSpeakerRequest(
    val requestId: String,
    val method: String = Method.SELECT_NEXT_SPEAKER,
    val conversationId: Int,
    val characterIds: List<String>,
    val history: List<MessageDTO>,
)

@Serializable
data class GenerateNPCReactionsRequest(
    val requestId: String,
    val method: String = Method.GENERATE_NPC_REACTIONS,
    val conversationId: Int,
    val speakerCharacterId: String,
    val message: String,
    val characterIds: List<String>,
)

@Serializable
data class ProcessConversationInformationRequest(
    val requestId: String,
    val method: String = Method.PROCESS_CONVERSATION_INFORMATION,
    val locationName: String,
    val characterIds: List<String>,
    val messages: List<MessageDTO>,
    val relevantLocations: Map<String, String>,
)

@Serializable
data class SummarizeConversationRequest(
    val requestId: String,
    val method: String = Method.SUMMARIZE_CONVERSATION,
    val conversationId: Int,
    val characterIds: List<String>,
    val history: List<MessageDTO>,
    val gameCreatedAt: Long,
)

@Serializable
data class GMGhostwriteRequest(
    val requestId: String,
    val method: String = Method.GM_GHOSTWRITE,
    val characterId: String,
    val conversationId: Int,
    val draftMessage: String,
    val history: List<MessageDTO>,
    val characterIds: List<String>,
    val playerCharacterIds: List<String>,
)

@Serializable
data class CapabilitiesRequest(
    val requestId: String,
    val method: String = Method.GET_CAPABILITIES,
)

@Serializable
data class GenerateCharactersRequest(
    val requestId: String,
    val method: String = Method.GENERATE_CHARACTERS,
    val template: String,
    val count: Int,
    val locationOverride: String? = null,
)

/** One generated character returned from chargen. */
@Serializable
data class GeneratedCharacterDTO(
    val template: String,
    val name: String,
    val race: String,
    val gender: String = "unknown",
    val appearance: Map<String, String> = emptyMap(),
    val location: String? = null,
    /** Stranger-label built by chargen ("Pale-skinned Nord Guard with red hair"). */
    val descriptor: String = "",
)

// --- Recognition (story-recognition via story-go) ---

@Serializable
data class RecognizeRequest(
    val requestId: String,
    val method: String = Method.RECOGNIZE,
    val perceiverId: String,
    val targetId: String,
    val realName: String,
    val source: String = "gm",
)

@Serializable
data class ForgetRecognitionRequest(
    val requestId: String,
    val method: String = Method.FORGET_RECOGNITION,
    val perceiverId: String,
    val targetId: String,
)

@Serializable
data class KnowsCharacterRequest(
    val requestId: String,
    val method: String = Method.KNOWS_CHARACTER,
    val perceiverId: String,
    val targetId: String,
)

@Serializable
data class KnownOfRequest(
    val requestId: String,
    val method: String = Method.KNOWN_OF,
    val perceiverId: String,
)

@Serializable
data class ResolveNamesRequest(
    val requestId: String,
    val method: String = Method.RESOLVE_NAMES,
    val perceiverId: String,
    val targetIds: List<String>,
)

@Serializable
data class GetDescriptorRequest(
    val requestId: String,
    val method: String = Method.GET_DESCRIPTOR,
    val characterId: String,
)

@Serializable
data class SetDescriptorRequest(
    val requestId: String,
    val method: String = Method.SET_DESCRIPTOR,
    val characterId: String,
    val descriptor: String,
)

@Serializable
data class SetAppearanceRequest(
    val requestId: String,
    val method: String = Method.SET_APPEARANCE,
    val characterId: String,
    val gender: String = "unknown",
    val traits: Map<String, String> = emptyMap(),
)

@Serializable
data class GetAppearanceRequest(
    val requestId: String,
    val method: String = Method.GET_APPEARANCE,
    val characterId: String,
)

@Serializable
data class GetAppearanceTemplatesRequest(
    val requestId: String,
    val method: String = Method.GET_APPEARANCE_TEMPLATES,
)

@Serializable
data class AppearanceTemplatesDTO(
    val pronouns: Map<String, Map<String, String>> = emptyMap(),
    val slots: Map<String, String> = emptyMap(),
)

@Serializable
data class DescribeRequest(
    val requestId: String,
    val method: String = Method.DESCRIBE,
    val perceiverId: String,
    val targetId: String,
)

@Serializable
data class AppearanceDocDTO(
    val characterId: String,
    val gender: String = "unknown",
    val traits: Map<String, String> = emptyMap(),
    val prose: String = "",
)

/** Per-perceiver render of a target. `name` is real name when known, else null. */
@Serializable
data class DescribeResultDTO(
    val perceiverId: String,
    val targetId: String,
    val known: Boolean,
    val name: String? = null,
    val prose: String,
)

/** One row of a /resolve batch response. */
@Serializable
data class ResolvedTargetDTO(
    val targetId: String,
    val known: Boolean,
    val realName: String? = null,
    val descriptor: String,
    val shortLabel: String = "",
    val confidence: Int = 0,
)

// --- Decision System ---

@Serializable
data class DecisionNpcVoiceDTO(
    val characterId: String,
    val name: String,
    val opinion: String,
    val stance: String,
)

@Serializable
data class DecisionOptionDTO(
    val id: String,
    val label: String,
    val consequenceHint: String = "",
)

@Serializable
data class DecisionPromptDTO(
    val decisionId: String,
    val mode: String, // "leader" | "vote"
    val leaderId: String = "",
    val playerTargets: List<String> = emptyList(),
    val title: String,
    val context: String,
    val urgency: String, // "critical" | "ambient"
    val npcVoices: List<DecisionNpcVoiceDTO> = emptyList(),
    val options: List<DecisionOptionDTO> = emptyList(),
    val allowFreeform: Boolean = true,
    val timeoutSeconds: Int = 60,
)

@Serializable
data class DecisionObserveDTO(
    val decisionId: String,
    val leaderName: String,
    val options: List<DecisionOptionDTO> = emptyList(),
)

@Serializable
data class DecisionResponseDTO(
    val decisionId: String,
    val characterId: String = "",
    val choiceId: String? = null,
    val freeformText: String? = null,
)

/**
 * Inbound from Go: ask the DM whether story-go may proceed with some
 * side effect (memory writes today; generic for any future gate).
 * Plugin replies with PermissionResponseEvent over the bus.
 */
@Serializable
data class PermissionAskDTO(
    val requestId: String,
    val trigger: String,
    val permission: String = "story.dm",
    val timeoutSec: Int = 60,
    val description: String,
)

/**
 * Constants for intelligence wire protocol.
 */
object EventType {
    const val INTELLIGENCE_REQUEST = "intelligence.request"
    const val INTELLIGENCE_RESPONSE = "intelligence.response"
    const val INTELLIGENCE_CAPABILITIES = "intelligence.capabilities"
    const val DECISION_PROMPT = "decision.prompt"
    const val DECISION_OBSERVE = "decision.observe"
    const val DECISION_RESPONSE = "decision.response"
    const val PERMISSION_ASK = "permission.ask"
    const val PERMISSION_RESPONSE = "permission.response"
}

/**
 * Constants for intelligence method names used in capability checks and wire protocol.
 */
object Method {
    const val GET_CAPABILITIES = "getCapabilities"
    const val GENERATE_NPC_RESPONSE = "generateNPCResponse"
    const val GM_GHOSTWRITE = "gmGhostwrite"
    const val SELECT_NEXT_SPEAKER = "selectNextSpeaker"
    const val SUMMARIZE_CONVERSATION = "summarizeConversation"
    const val GENERATE_NPC_REACTIONS = "generateNPCReactions"
    const val SUMMARIZE_MESSAGE_HISTORY = "summarizeMessageHistory"
    const val PROCESS_CONVERSATION_INFORMATION = "processConversationInformation"
    const val GENERATE_CHARACTERS = "generateCharacters"
    const val RECOGNIZE = "recognize"
    const val FORGET_RECOGNITION = "forgetRecognition"
    const val KNOWS_CHARACTER = "knowsCharacter"
    const val KNOWN_OF = "knownOf"
    const val RESOLVE_NAMES = "resolveNames"
    const val GET_DESCRIPTOR = "getDescriptor"
    const val SET_DESCRIPTOR = "setDescriptor"
    const val SET_APPEARANCE = "setAppearance"
    const val GET_APPEARANCE = "getAppearance"
    const val DESCRIBE = "describe"
    const val GET_APPEARANCE_TEMPLATES = "getAppearanceTemplates"
}
