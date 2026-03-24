package com.canefe.story.intelligence

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.EssentialsUtils
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/**
 * Local implementation of StoryIntelligence that uses direct LLM calls
 * via the existing npcResponseService and promptService.
 */
class LocalIntelligence(
    private val plugin: Story,
) : StoryIntelligence {
    override fun generateNPCResponse(
        npc: StoryNPC,
        conversation: Conversation,
    ): CompletableFuture<String> {
        val npcContextGenerator = plugin.npcContextGenerator

        // Get only the messages from the conversation for context
        val recentMessages = conversation.history.map { it.content }

        // Prepare response context
        var responseContext =
            mutableListOf(
                "\n===APPEARANCES===\n" +
                    conversation.npcs.joinToString("\n") { convNpc ->
                        val ctx = npcContextGenerator.getOrCreateContextForNPC(convNpc)
                        "${convNpc.name}: ${ctx?.appearance ?: "No appearance information available."}"
                    } +
                    conversation.players.joinToString("\n") { playerId ->
                        val player = Bukkit.getPlayer(playerId) ?: return@joinToString ""
                        val nickname = EssentialsUtils.getNickname(player.name)
                        val ctx = npcContextGenerator.getOrCreateContextForNPC(nickname)
                        "$nickname: ${ctx?.appearance ?: "No appearance information available."}"
                    } +
                    "\n=========================",
                "====CURRENT CONVERSATION====\n" +
                    recentMessages.joinToString("\n") +
                    "\n=========================\n" +
                    "This is an active conversation and you are talking to multiple characters: ${
                        conversation.players.joinToString(", ") {
                            Bukkit.getPlayer(it)?.name?.let { name -> EssentialsUtils.getNickname(name) } ?: ""
                        }
                    }. " +
                    conversation.npcNames.filter { it != npc.name }.joinToString("\n") +
                    ". Respond in character as ${npc.name}. Message starts now:",
            )

        // Add relationship context
        val relationships = plugin.relationshipManager.getAllRelationships(npc.name)
        if (relationships.isNotEmpty()) {
            val relationshipContext =
                plugin.relationshipManager.buildRelationshipContext(
                    npc.name,
                    relationships,
                    conversation,
                )
            if (relationshipContext.isNotEmpty()) {
                responseContext.addFirst("===RELATIONSHIPS===\n$relationshipContext")
            }
        }

        return plugin.npcResponseService.generateNPCResponse(npc, responseContext, broadcast = false)
    }

    override fun selectNextSpeaker(conversation: Conversation): CompletableFuture<String?> =
        plugin.npcResponseService.determineNextSpeaker(conversation)

    override fun summarizeConversation(conversation: Conversation): CompletableFuture<Void> =
        plugin.npcResponseService.summarizeConversation(conversation)

    override fun generateNPCReactions(
        conversation: Conversation,
        speakerName: String,
        message: String,
    ): CompletableFuture<Map<String, String>> {
        val reactingNPCs =
            conversation.npcs.filter {
                it.name != speakerName && !conversation.mutedNPCs.contains(it)
            }
        if (reactingNPCs.isEmpty()) return CompletableFuture.completedFuture(emptyMap())

        val npcDescriptions =
            reactingNPCs.joinToString("\n") { npc ->
                val context = plugin.npcContextGenerator.getOrCreateContextForNPC(npc)
                val role = context?.role ?: "unknown"
                val personality = context?.context?.take(150) ?: "no details"
                "- ${npc.name} ($role): $personality"
            }

        val recentHistory =
            conversation.history
                .filter { it.role != "system" && it.content != "..." }
                .takeLast(6)
                .joinToString("\n") { it.content }

        val systemPrompt = plugin.promptService.getNpcReactionsPrompt()
        val prompts =
            mutableListOf(
                ConversationMessage("system", systemPrompt),
                ConversationMessage(
                    "user",
                    """NPCs in conversation:
$npcDescriptions

Recent conversation:
$recentHistory

The following was just said:
"$speakerName: $message"

Generate brief physical reactions for each NPC listed above.""",
                ),
            )

        val result = CompletableFuture<Map<String, String>>()
        plugin
            .getAIResponse(prompts, lowCost = true)
            .thenAccept { response ->
                if (response.isNullOrBlank()) {
                    result.complete(emptyMap())
                    return@thenAccept
                }

                val reactions = mutableMapOf<String, String>()
                for (line in response.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val colonIndex = trimmed.indexOf(':')
                    if (colonIndex == -1) continue
                    val npcName = trimmed.substring(0, colonIndex).trim()
                    val reaction = trimmed.substring(colonIndex + 1).trim()
                    if (reactingNPCs.any { it.name.equals(npcName, ignoreCase = true) }) {
                        reactions[npcName] = reaction
                    }
                }
                result.complete(reactions)
            }.exceptionally { e ->
                plugin.logger.warning("Error generating NPC reactions: ${e.message}")
                result.complete(emptyMap())
                null
            }
        return result
    }

    override fun summarizeMessageHistory(conversation: Conversation): CompletableFuture<String?> {
        val summaryPrompt = plugin.promptService.getMessageHistorySummaryPrompt()

        val history = conversation.history
        val recentMessagesToKeep = 3
        val splitIndex = history.size - recentMessagesToKeep
        val messagesToSummarize = history.subList(0, splitIndex)

        val existingSummary =
            messagesToSummarize
                .firstOrNull { it.role == "system" && it.content.startsWith("Summary of conversation") }
                ?.content

        val newMessages =
            messagesToSummarize
                .filter { it.content != "..." && it.role != "system" }
                .joinToString("\n") { it.content.replace("\n", " ") }

        val userMessage =
            if (existingSummary != null) {
                "Here is the existing summary of earlier events:\n---\n$existingSummary\n---\n\n" +
                    "Here are the new messages that happened after that summary:\n---\n$newMessages\n---\n\n" +
                    "Write a single combined summary that incorporates ALL details from the existing summary " +
                    "and the new messages. Do not drop any information from the existing summary."
            } else {
                "Summarize the following conversation transcript:\n---\n$newMessages\n---"
            }

        val prompts =
            listOf(
                ConversationMessage("system", summaryPrompt),
                ConversationMessage("user", userMessage),
            )

        return plugin.getAIResponse(prompts, lowCost = false)
    }

    override fun processConversationInformation(request: ConversationInformationRequest): CompletableFuture<Void> {
        if (request.messages.isEmpty()) return CompletableFuture.completedFuture(null)

        val prompts = buildAnalysisPrompt(request)

        val result = CompletableFuture<Void>()
        plugin
            .getAIResponse(prompts)
            .thenAccept { analysisResult ->
                try {
                    if (!analysisResult.isNullOrEmpty() && !analysisResult.contains("Nothing significant")) {
                        val findings = parseAnalysisResult(analysisResult)
                        storeFindings(
                            findings,
                            request.npcNames,
                            request.locationName,
                            request.relevantLocations.keys.toList(),
                        )
                    }
                    result.complete(null)
                } catch (e: Exception) {
                    plugin.logger.severe("Error analyzing conversation significance: ${e.message}")
                    result.completeExceptionally(e)
                }
            }.exceptionally { e ->
                plugin.logger.severe("Error getting AI response for conversation analysis: ${e.message}")
                result.completeExceptionally(e)
                null
            }
        return result
    }

    private fun buildAnalysisPrompt(request: ConversationInformationRequest): List<ConversationMessage> {
        val locationsDescription =
            request.relevantLocations.entries.joinToString("\n") { (name, context) ->
                "- $name: $context"
            }

        val prompt =
            """
            Analyze the following conversation between NPCs.
            Conversation location: ${request.locationName}
            NPCs involved: ${request.npcNames.joinToString(", ")}

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
        prompts.addAll(request.messages)
        return prompts
    }

    private fun parseAnalysisResult(analysis: String): List<ExtractedInformation> {
        val results = mutableListOf<ExtractedInformation>()
        val findings = analysis.split("---")

        for (finding in findings) {
            if (finding.trim().isEmpty()) continue

            val info = mutableMapOf<String, String>()
            for (line in finding.split("\n")) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue
                val parts = trimmedLine.split(":", limit = 2)
                if (parts.size != 2) continue
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.equals("Type", ignoreCase = true) ||
                    key.equals("Target", ignoreCase = true) ||
                    key.equals("Importance", ignoreCase = true) ||
                    key.equals("Information", ignoreCase = true)
                ) {
                    info[key] = value
                }
            }

            val type = info["Type"] ?: continue
            val target = info["Target"] ?: continue
            val information = info["Information"] ?: continue
            val importance = info["Importance"] ?: "MEDIUM"

            val infoType =
                when {
                    type.equals("RUMOR", ignoreCase = true) -> ExtractedInformation.InformationType.RUMOR
                    type.equals("PERSONAL", ignoreCase = true) -> ExtractedInformation.InformationType.PERSONAL
                    else -> continue
                }
            val infoImportance =
                when {
                    importance.equals("HIGH", ignoreCase = true) -> ExtractedInformation.Importance.HIGH
                    importance.equals("LOW", ignoreCase = true) -> ExtractedInformation.Importance.LOW
                    else -> ExtractedInformation.Importance.MEDIUM
                }

            results.add(ExtractedInformation(infoType, target, infoImportance, information))
        }
        return results
    }

    /**
     * Local fallback: stores extracted findings directly via plugin managers.
     * In bridge mode, this never runs — Go/story-bot handles persistence.
     */
    private fun storeFindings(
        findings: List<ExtractedInformation>,
        npcNames: List<String>,
        locationName: String,
        relevantLocations: List<String>,
    ) {
        for (finding in findings) {
            when (finding.type) {
                ExtractedInformation.InformationType.RUMOR -> {
                    when {
                        // Target is a known location
                        relevantLocations.any { it.equals(finding.target, ignoreCase = true) } -> {
                            addLocationRumor(finding.target, finding.information, finding.importance)
                        }
                        // Target is "all" or "location"
                        finding.target.equals("location", ignoreCase = true) ||
                            finding.target.equals("all", ignoreCase = true) -> {
                            for (loc in relevantLocations) {
                                addLocationRumor(loc, finding.information, finding.importance)
                                propagateToParentLocations(loc, finding.information, finding.importance)
                            }
                        }
                        // Target might be NPC names
                        else -> {
                            val targetNpcs = finding.target.split(",\\s*".toRegex())
                            val validTargetFound = targetNpcs.any { npcNames.contains(it.trim()) }
                            if (validTargetFound) {
                                addLocationRumor(locationName, "Rumor: ${finding.information}", finding.importance)
                            } else {
                                addLocationRumor(locationName, finding.information, finding.importance)
                            }
                        }
                    }
                }
                ExtractedInformation.InformationType.PERSONAL -> {
                    addPersonalKnowledge(finding.target, finding.information)
                }
            }
        }
    }

    private fun addLocationRumor(
        locationName: String,
        information: String,
        importance: ExtractedInformation.Importance,
    ) {
        val prefix =
            when (importance) {
                ExtractedInformation.Importance.HIGH -> "Major news: "
                ExtractedInformation.Importance.MEDIUM -> "Local rumor: "
                ExtractedInformation.Importance.LOW -> "Minor gossip: "
            }

        val significance =
            when (importance) {
                ExtractedInformation.Importance.HIGH -> 0.9
                ExtractedInformation.Importance.MEDIUM -> 0.6
                ExtractedInformation.Importance.LOW -> 0.3
            }

        plugin.storage.addRumor(
            content = "$prefix$information",
            location = locationName,
            significance = significance,
            gameCreatedAt = plugin.timeService.getCurrentGameTime(),
        )
    }

    private fun propagateToParentLocations(
        locationName: String,
        information: String,
        importance: ExtractedInformation.Importance,
    ) {
        val location = plugin.locationManager.getLocation(locationName) ?: return
        location.parentLocationName?.let { parentName ->
            val reducedImportance =
                when (importance) {
                    ExtractedInformation.Importance.HIGH -> ExtractedInformation.Importance.MEDIUM
                    ExtractedInformation.Importance.MEDIUM -> ExtractedInformation.Importance.LOW
                    ExtractedInformation.Importance.LOW -> return@let
                }
            addLocationRumor(parentName, "Distant rumor: $information", reducedImportance)
            propagateToParentLocations(parentName, information, reducedImportance)
        }
    }

    private fun addPersonalKnowledge(
        npcName: String,
        information: String,
    ) {
        plugin.storage.createMemory(npcName, information)
    }
}
