package com.canefe.story.intelligence

import com.canefe.story.conversation.ConversationMessage

/**
 * A single piece of information extracted from a conversation by the intelligence layer.
 */
data class ExtractedInformation(
    val type: InformationType,
    val target: String,
    val importance: Importance,
    val information: String,
) {
    enum class InformationType { RUMOR, PERSONAL }

    enum class Importance { LOW, MEDIUM, HIGH }
}

/**
 * Input data for conversation information extraction.
 */
data class ConversationInformationRequest(
    val messages: List<ConversationMessage>,
    val npcNames: List<String>,
    val locationName: String,
    val relevantLocations: Map<String, String>,
)
