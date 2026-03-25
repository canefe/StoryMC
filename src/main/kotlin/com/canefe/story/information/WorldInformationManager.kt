package com.canefe.story.information

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.intelligence.ConversationInformationRequest

interface InformationSource {
    fun getSourceType(): String

    fun getSourceIdentifier(): String

    fun getSignificanceLevel(): Int
}

/**
 * Thin coordinator that gathers Minecraft-side context (locations, NPC homes)
 * and delegates all LLM analysis + storage to the intelligence layer.
 */
class WorldInformationManager(
    private val plugin: Story,
) {
    fun processInformation(source: InformationSource) {
        when (source.getSourceType()) {
            "conversation" -> handleConversationInformation(source as ConversationInformationSource)
        }
    }

    private fun handleConversationInformation(source: ConversationInformationSource) {
        if (source.messages.isEmpty()) return

        // Gather Minecraft-side context: location descriptions, NPC home locations
        val relevantLocations = mutableMapOf<String, String>()

        plugin.locationManager.getLocation(source.locationName)?.let { location ->
            relevantLocations[source.locationName] = location.description.take(200)
        }

        for (npcName in source.npcNames) {
            val citizensNpc =
                net.citizensnpcs.api.CitizensAPI
                    .getNPCRegistry()
                    .firstOrNull { it.name.equals(npcName, ignoreCase = true) }
            citizensNpc?.entity?.location?.let { entityLoc ->
                plugin.locationManager.getLocationByPosition2D(entityLoc)?.let { storyLoc ->
                    relevantLocations[storyLoc.name] = storyLoc.description.take(200)
                }
            }
        }

        // Delegate everything to the intelligence layer
        val request =
            ConversationInformationRequest(
                messages = source.messages,
                npcNames = source.npcNames,
                locationName = source.locationName,
                relevantLocations = relevantLocations,
            )

        plugin.intelligence.processConversationInformation(request).exceptionally { e ->
            plugin.logger.severe("Error processing conversation information: ${e.message}")
            null
        }
    }
}

class ConversationInformationSource(
    val messages: List<ConversationMessage>,
    val npcNames: List<String>,
    val locationName: String,
    private val significance: Int,
) : InformationSource {
    override fun getSourceType() = "conversation"

    override fun getSourceIdentifier() = npcNames.joinToString(",")

    override fun getSignificanceLevel() = significance
}
