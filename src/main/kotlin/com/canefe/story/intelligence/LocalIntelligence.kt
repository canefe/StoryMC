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
}
