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
data class CapabilitiesRequest(
    val requestId: String,
    val method: String = Method.GET_CAPABILITIES,
)

/**
 * Constants for intelligence wire protocol.
 */
object EventType {
    const val INTELLIGENCE_REQUEST = "intelligence.request"
    const val INTELLIGENCE_RESPONSE = "intelligence.response"
    const val INTELLIGENCE_CAPABILITIES = "intelligence.capabilities"
}

/**
 * Constants for intelligence method names used in capability checks and wire protocol.
 */
object Method {
    const val GET_CAPABILITIES = "getCapabilities"
    const val GENERATE_NPC_RESPONSE = "generateNPCResponse"
    const val SELECT_NEXT_SPEAKER = "selectNextSpeaker"
    const val SUMMARIZE_CONVERSATION = "summarizeConversation"
    const val GENERATE_NPC_REACTIONS = "generateNPCReactions"
    const val SUMMARIZE_MESSAGE_HISTORY = "summarizeMessageHistory"
    const val PROCESS_CONVERSATION_INFORMATION = "processConversationInformation"
}
