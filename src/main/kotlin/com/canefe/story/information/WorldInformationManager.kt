package com.canefe.story.information

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.ConversationMessageRole
import java.util.concurrent.CompletableFuture

interface InformationSource {
    fun getSourceType(): String
    fun getSourceIdentifier(): String
    fun getSignificanceLevel(): Int
}

class WorldInformationManager(private val plugin: Story) {
    fun processInformation(source: InformationSource) {
        when(source.getSourceType()) {
            "conversation" -> handleConversationInformation(source as ConversationInformationSource)
            // Future types can be added here
        }
    }

    private fun handleConversationInformation(source: ConversationInformationSource) {
        val messages = source.messages
        val npcNames = source.npcNames
        val conversationLocation = source.locationName

        if (messages.isEmpty()) return

        // Collect all relevant locations for this conversation
        val relevantLocations = mutableMapOf<String, String>() // locationName -> brief context

        // Add conversation location
        plugin.locationManager.getLocation(conversationLocation)?.let { location ->
            relevantLocations[conversationLocation] = location.context.take(3).joinToString("; ")
        }

        // Add home locations of all NPCs involved
        for (npcName in npcNames) {
            val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
            npcContext?.location?.name?.let { homeLocation ->
                plugin.locationManager.getLocation(homeLocation)?.let { location ->
                    relevantLocations[homeLocation] = location.context.take(3).joinToString("; ")
                }
            }
        }

        // Create prompt for AI analysis
        val prompts = createAnalysisPrompt(messages, npcNames, conversationLocation, relevantLocations)

        // Send to AI for analysis
        CompletableFuture.runAsync {
            try {
                val analysisResult = plugin.getAIResponse(prompts) ?: return@runAsync
                if (analysisResult.isNotEmpty() && !analysisResult.contains("Nothing significant")) {
                    processSignificanceResults(analysisResult, npcNames, conversationLocation, relevantLocations.keys.toList())
                }
            } catch (e: Exception) {
                plugin.logger.severe("Error analyzing conversation significance: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun createAnalysisPrompt(
        messages: List<ConversationMessage>,
        npcNames: List<String>,
        conversationLocation: String,
        relevantLocations: Map<String, String>
    ): List<ConversationMessage> {
        val locationsDescription = relevantLocations.entries.joinToString("\n") { (name, context) ->
            "- $name: $context"
        }

        val prompt = """
        Analyze the following conversation between NPCs.
        Conversation location: $conversationLocation
        NPCs involved: ${npcNames.joinToString(", ")}
        
        Relevant locations with context:
        $locationsDescription
        
        Identify significant information that should be:
        1. Remembered by specific NPCs involved (personal knowledge)
        2. Spread as rumors throughout specific locations (location-based knowledge)
        3. Ignored as trivial conversation
        
        For each finding, specify:
        ---
        Type: [PERSONAL or RUMOR]
        Target: [NPC name or specific location name]
        Importance: [LOW, MEDIUM, HIGH]
        Information: [Concise description of what should be remembered]
        ---
        If nothing significant occurred, respond with 'Nothing significant.'
    """.trimIndent()

        val prompts = mutableListOf<ConversationMessage>()
        prompts.add(ConversationMessage(role = "system", content = prompt))
        prompts.addAll(messages)
        return prompts
    }

    private fun processSignificanceResults(
        analysis: String,
        npcNames: List<String>,
        locationName: String,
        relevantLocations: List<String>
    ) {
        // Split the analysis into separate findings
        val findings = analysis.split("---")

        for (finding in findings) {
            if (finding.trim().isEmpty()) continue

            val info = parseSignificanceInfo(finding)
            if (info.isEmpty()) continue

            val type = info["Type"]
            val target = info["Target"]
            val importance = info["Importance"]
            val information = info["Information"]

            if (type == null || target == null || information == null) continue

            // Process based on type
            when {
                type.equals("PERSONAL", ignoreCase = true) -> {
                    // Split target by comma to handle multiple NPCs
                    val targetNpcs = target.split(",\\s*".toRegex())

                    for (singleTarget in targetNpcs) {
                        val trimmedTarget = singleTarget.trim()
                        // Only add personal knowledge if target is a valid NPC name
                        if (npcNames.contains(trimmedTarget)) {
                            addPersonalKnowledge(trimmedTarget, information, importance ?: "MEDIUM")
                        } else {
                            plugin.logger.warning("Ignoring personal knowledge for unknown NPC: $trimmedTarget")
                        }
                    }
                }
                type.equals("RUMOR", ignoreCase = true) -> {
                    // Is the target a specific location?
                    if (relevantLocations.any { it.equals(target, ignoreCase = true) }) {
                        // Add rumor only to the specified location
                        addLocationRumor(target, information, importance ?: "MEDIUM")
                    }
                    else if (target.equals("location", ignoreCase = true) || target.equals("all", ignoreCase = true)) {
                        // Add rumor to all relevant locations
                        for (locationName in relevantLocations) {
                            addLocationRumor(locationName, information, importance ?: "MEDIUM")

                            // Also propagate to parent locations with reduced importance
                            propagateToParentLocations(locationName, information, importance ?: "MEDIUM")
                        }
                    } else {
                        // Split target by comma to handle multiple NPCs
                        val targetNpcs = target.split(",\\s*".toRegex())
                        var validTargetFound = false

                        for (singleTarget in targetNpcs) {
                            val trimmedTarget = singleTarget.trim()
                            // Only if target is a valid NPC name
                            if (npcNames.contains(trimmedTarget)) {
                                addPersonalKnowledge(trimmedTarget, information, importance ?: "MEDIUM")
                                validTargetFound = true
                            }
                        }

                        // Add location rumor only once, if at least one valid NPC was found
                        if (validTargetFound) {
                            addLocationRumor(locationName, "Rumor: $information", importance ?: "MEDIUM")
                        } else {
                            plugin.logger.warning("No valid NPCs found in target: $target, treating as location rumor")
                            addLocationRumor(locationName, information, importance ?: "MEDIUM")
                        }
                    }
                }
            }
        }
    }

    private fun propagateToParentLocations(locationName: String, information: String, importance: String) {
        // Get location
        val location = plugin.locationManager.getLocation(locationName) ?: return

        // If this location has a parent, propagate the rumor there with reduced importance
        location.parentLocationName?.let { parentName ->
            val reducedImportance = when(importance.uppercase()) {
                "HIGH" -> "MEDIUM"
                "MEDIUM" -> "LOW"
                else -> return@let // Don't propagate LOW importance rumors to parents
            }

            // Add rumor to parent with "Distant rumor" prefix
            addLocationRumor(parentName, "Distant rumor: $information", reducedImportance)

            // Recursively propagate to parent's parent with further reduced importance
            propagateToParentLocations(parentName, information, reducedImportance)
        }
    }

    private fun parseSignificanceInfo(finding: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        val lines = finding.split("\n")

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val parts = trimmedLine.split(":", limit = 2)
            if (parts.size != 2) continue

            val key = parts[0].trim()
            val value = parts[1].trim()

            if (key.equals("Type", ignoreCase = true) ||
                key.equals("Target", ignoreCase = true) ||
                key.equals("Importance", ignoreCase = true) ||
                key.equals("Information", ignoreCase = true)) {
                info[key] = value
            }
        }

        return info
    }

    private fun addPersonalKnowledge(npcName: String, information: String, importance: String) {
        try {
            // Get the NPC's existing data
            val npcData = plugin.npcDataManager.getNPCData(npcName) ?: return

            // Determine memory power based on importance
            val memoryPower = when {
                importance.equals("HIGH", ignoreCase = true) -> 1.0
                importance.equals("MEDIUM", ignoreCase = true) -> 0.8
                else -> 0.6
            }

            // Create a new memory with appropriate power
            npcData.addMemory(information, memoryPower)

            // Save the updated NPC data
            plugin.npcDataManager.saveNPCData(npcName, npcData)

            plugin.logger.info("Added memory to $npcName with power ${memoryPower}: $information")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to add memory to $npcName: ${e.message}")
        }
    }

    private fun addLocationRumor(locationName: String, information: String, importance: String) {
        try {
            // Get the location from the location manager
            val location = plugin.locationManager.getLocation(locationName) ?: return

            // Determine prefix based on importance
            val prefix = when {
                importance.equals("HIGH", ignoreCase = true) -> "Major news: "
                importance.equals("MEDIUM", ignoreCase = true) -> "Local rumor: "
                else -> "Minor gossip: "
            }

            // Add the rumor to the location's context list
            val currentContext = location.context
            currentContext.add("$prefix$information")

            // Save the updated location
            plugin.locationManager.saveLocation(location)

            plugin.logger.info("Added rumor to $locationName: $information")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to add rumor to $locationName: ${e.message}")
        }
    }
}

// Update the ConversationInformationSource to expose its properties
class ConversationInformationSource(
    val messages: List<ConversationMessage>,
    val npcNames: List<String>,
    val locationName: String,
    private val significance: Int
) : InformationSource {
    override fun getSourceType() = "conversation"
    override fun getSourceIdentifier() = npcNames.joinToString(",")
    override fun getSignificanceLevel() = significance
}