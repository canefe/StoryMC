package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min


class NPCResponseService(private val plugin: Story) {

    // generate get() reference to plugin.npcContextGenerator
    private val contextService = plugin.npcContextGenerator

    fun generateNPCResponse(npc: NPC, responseContext: List<String>, broadcast: Boolean = true): CompletableFuture<String> {
        val prompts: MutableList<ConversationMessage> = ArrayList()

        // Add general context
        contextService.getGeneralContexts().forEach { prompts.add(
            ConversationMessage(
                "system",
                it
            ) ) }

        // Get NPC context
        val npcContext = contextService.getOrCreateContextForNPC(npc.name)

        // Add location context
        val locationContexts = npcContext?.location?.context
        if (locationContexts != null) {
            for (context in locationContexts) {
                prompts.add(
                    ConversationMessage(
                        "system",
                        context
                    )
                )
            }
        }

        // Lorebook context (the knowledge this NPC has)
        // Get the current conversation
        plugin.conversationManager.getConversation(npc).let { conversation ->
            // Check if the conversation is null
            if (conversation != null) {
                // Get the lorebook context for the current conversation
                val lorebookContexts = plugin.conversationManager.checkAndGetLoreContexts(conversation)
                for (lore in lorebookContexts) {
                    prompts.add(
                        ConversationMessage(
                            "system",
                            "You know following information: ${lore.loreName} - ${lore.context}"
                        )
                    )
                }
            }
        }

        // Add the NPC context
        if (npcContext != null) {
            prompts.add(
                ConversationMessage(
                    "system",
                    npcContext.context
                )
            )
        }

        // Finally, add NPCs memories
        npcContext?.getMemoriesForPrompt()?.let { memories ->
            prompts.add(
                ConversationMessage(
                    "system",
                    memories
                )
            )
        }

        // Add the response context (this could be either current conversation or a specific message)
        if (responseContext.isNotEmpty()) {
            prompts.add(
                ConversationMessage(
                    "system",
                    responseContext.joinToString(separator = "\n")
                )
            )
        }


        return CompletableFuture.supplyAsync {
            val response = plugin.getAIResponse(prompts) ?: "No response generated."

            if (broadcast) {
                plugin.npcMessageService.broadcastNPCMessage(response, npc)
            }

            response
        }
    }

    fun determineNextSpeaker(conversation: Conversation, player: Player): CompletableFuture<String?> {
        val future = CompletableFuture<String?>()


        // Short-circuit for the simple case of only one NPC
        if (conversation.npcNames.size == 1) {
            future.complete(conversation.npcNames[0])
            return future
        }


        // Create a list of Messages for the AI to analyze
        val speakerSelectionPrompt: MutableList<ConversationMessage> = ArrayList()

        // Get recent conversation history (last 10 messages)
        val recentHistory: List<ConversationMessage> = conversation.history
        val historySize = min(recentHistory.size.toDouble(), 10.0).toInt()
        val contextMessages = recentHistory.subList(
            max(0.0, (recentHistory.size - historySize).toDouble()).toInt(),
            recentHistory.size
        )

        // Add system prompt for NPC selection
        speakerSelectionPrompt.add(
            ConversationMessage(
                "system",
                """
            Based on the conversation history below, determine which character should speak next. Consider: who was addressed in the last message, who has relevant information, and who hasn't spoken recently. Available characters: ${
                    java.lang.String.join(
                        ", ",
                        conversation.npcNames
                    )
                }
            
            Respond with ONLY the name of who should speak next. No explanation or additional text.
            """.trimIndent()
            )
        )

        // Add conversation context
        speakerSelectionPrompt.addAll(contextMessages)

        // Add a default NPC if the list is empty to avoid errors
        if (conversation.npcNames.isEmpty()) {
            future.complete(null)
            return future
        }

        // Run this asynchronously to avoid blocking
        CompletableFuture.runAsync {
            try {
                var speakerSelection = CompletableFuture.supplyAsync {
                    // Get the AI's response for the next speaker
                    plugin.getAIResponse(speakerSelectionPrompt)
                }.thenApply { response ->
                    response ?: "No response generated."
                }.get()

                // Clean up the response and validate
                if (speakerSelection.isNotEmpty()) {
                    speakerSelection = speakerSelection.trim { it <= ' ' }

                    // Check if the selected speaker is a valid NPC in the conversation
                    if (conversation.npcNames.contains(speakerSelection)) {
                        future.complete(speakerSelection)
                    } else {
                        // Fall back to the first NPC if the selected speaker is invalid
                        future.complete(conversation.npcNames[0])
                    }
                } else {
                    // Fall back to the first NPC if no response
                    future.complete(conversation.npcNames[0])
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error determining next speaker: " + e.message)
                // Fall back to the first NPC on error
                future.complete(conversation.npcNames[0])
            }
        }

        return future
    }

    fun generateNPCGreeting(npc: NPC, target: String, greetingContext: List<String>? = null): String? {
        val prompt = "You are ${npc.name}. You've noticed $target nearby and decided to initiate a conversation. Your greeting must reflect your personality, your relationship and recent memories, especially with the target. Generate a brief greeting."

        val prompts: MutableList<String> = ArrayList()
        prompts.add(prompt)
        prompts.addAll(greetingContext ?: emptyList())

        val response = generateNPCResponse(npc, listOf(prompts.joinToString(separator = "\n")), false).join()

        return response
    }

    fun summarizeConversation(history: List<ConversationMessage>, npcNames: List<String>, playerName: String? = null) {
        if (history.isEmpty() || history.size < 3) return

        // Create a prompt for the AI to summarize the conversation
        val messages = mutableListOf<ConversationMessage>()

        messages.add(ConversationMessage(
            "system",
            "Summarize this conversation from the perspective of each participant. " +
                    "Format each summary as: NPC_NAME: their individual perspective and key takeaway"
        ))
        messages.addAll(history)

        // Get AI response
        val summaryResponse = plugin.getAIResponse(messages) ?: return

        // Process each NPC's perspective and add as memory
        for (npcName in npcNames) {
            val npcSummaryMatch = Regex("(?:^|\\n)$npcName:\\s*(.*?)(?=\\n\\w+:|$)", RegexOption.DOT_MATCHES_ALL)
                .find(summaryResponse)?.groupValues?.getOrNull(1)?.trim()

            if (!npcSummaryMatch.isNullOrEmpty()) {
                // Get NPC data
                val npcData = plugin.npcDataManager.getNPCData(npcName) ?: continue

                // Create a memory with medium power (this is a personal experience)
                npcData.addMemory(npcSummaryMatch, 0.85)

                // Save updated NPC data
                plugin.npcDataManager.saveNPCData(npcName, npcData)
            }
        }
    }
}