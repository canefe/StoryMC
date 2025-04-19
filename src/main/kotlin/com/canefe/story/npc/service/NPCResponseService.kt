package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.memory.Memory
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class NPCResponseService(private val plugin: Story) {
	// generate get() reference to plugin.npcContextGenerator
	private val contextService = plugin.npcContextGenerator

	fun generateNPCResponse(
		npc: NPC,
		responseContext: List<String>,
		broadcast: Boolean = true,
	): CompletableFuture<String> {
		val prompts: MutableList<ConversationMessage> = ArrayList()

		// Add general context
		contextService.getGeneralContexts().forEach {
			prompts.add(
				ConversationMessage(
					"system",
					it,
				),
			)
		}

		// Get NPC context
		val npcContext = contextService.getOrCreateContextForNPC(npc.name)

		// Add location context
		val location = npcContext?.location

		if (location != null) {
			prompts.add(
				ConversationMessage(
					"system",
					location.getContextForPrompt(plugin.locationManager),
				),
			)
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
							"You know following information: ${lore.loreName} - ${lore.context}",
						),
					)
				}
			}
		}

		// Add the NPC context
		if (npcContext != null) {
			prompts.add(
				ConversationMessage(
					"system",
					npcContext.context,
				),
			)
		}

		// Finally, add NPCs memories
		npcContext?.getMemoriesForPrompt(plugin.timeService)?.let { memories ->
			prompts.add(
				ConversationMessage(
					"system",
					memories,
				),
			)
		}

		// Add the response context (this could be either current conversation or a specific message)
		if (responseContext.isNotEmpty()) {
			prompts.add(
				ConversationMessage(
					"system",
					responseContext.joinToString(separator = "\n"),
				),
			)
		}

		return plugin.getAIResponse(prompts).thenApply { response ->
			val finalResponse = response ?: "No response generated."
			if (broadcast) {
				plugin.npcMessageService.broadcastNPCMessage(finalResponse, npc)
			}
			// Get the current player in conversation with this NPC, if any
			val targetPlayer =
				plugin.conversationManager.getConversation(npc)?.players?.firstOrNull()?.let {
					Bukkit.getPlayer(it)
				}
			// Analyze response for action intents asynchronously
			if (targetPlayer != null) {
				plugin.npcActionIntentRecognizer.recognizeActionIntents(npc, finalResponse, targetPlayer)
			}
			finalResponse
		}
	}

	fun determineNextSpeaker(conversation: Conversation): CompletableFuture<String?> {
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
		val contextMessages =
			recentHistory.subList(
				max(0.0, (recentHistory.size - historySize).toDouble()).toInt(),
				recentHistory.size,
			)

		// Add system prompt for NPC selection
		speakerSelectionPrompt.add(
			ConversationMessage(
				"system",
				"""
            Based on the conversation history below, determine which character should speak next. Consider: who was addressed in the last message, who has relevant information, and who hasn't spoken recently. Available characters: ${
					java.lang.String.join(
						", ",
						conversation.npcNames,
					)
				}
            
            Respond with ONLY the name of who should speak next. No explanation or additional text.
            """.trimIndent(),
			),
		)

		// Add conversation context
		speakerSelectionPrompt.addAll(contextMessages)

		// Add a default NPC if the list is empty to avoid errors
		if (conversation.npcNames.isEmpty()) {
			future.complete(null)
			return future
		}

		// Run this asynchronously to avoid blocking
		plugin.getAIResponse(speakerSelectionPrompt).thenApply { response ->
			val speakerSelection = response?.trim() ?: ""
			if (speakerSelection.isNotEmpty() && conversation.npcNames.contains(speakerSelection)) {
				speakerSelection
			} else {
				conversation.npcNames[0]
			}
		}.thenAccept { future.complete(it) }.exceptionally { e ->
			plugin.logger.warning("Error determining next speaker: ${e.message}")
			future.complete(conversation.npcNames[0])
			null
		}

		return future
	}

	fun generateNPCGreeting(
		npc: NPC,
		target: String,
		greetingContext: List<String>? = null,
	): String? {
		val prompt = "You are ${npc.name}. You've noticed $target nearby and decided to initiate a conversation. Your greeting must reflect your personality, your relationship and recent memories, especially with the target. Generate a brief greeting."

		val prompts: MutableList<String> = ArrayList()
		prompts.add(prompt)
		prompts.addAll(greetingContext ?: emptyList())

		val response = generateNPCResponse(npc, listOf(prompts.joinToString(separator = "\n")), false).join()

		return response
	}

	fun summarizeConversation(
		history: List<ConversationMessage>,
		npcNames: List<String>,
		playerName: String? = null,
	): CompletableFuture<Void> {
		val future = CompletableFuture<Void>()

		// Return early if not enough conversation history
		if (history.isEmpty() || history.size < 3) {
			future.complete(null)
			return future
		}

		val messages = mutableListOf<ConversationMessage>()

		messages.addAll(history)
		messages.add(
			ConversationMessage(
				"system",
				"Summarize this conversation from the perspective of each participant. Use this format exactly:\n\n" +
					"NPC_NAME: <their individual perspective and key takeaway>\n\n" +
					"Avoid extra formatting like asterisks, role labels (e.g., NPC/Player), or markdown. Keep the tone consistent with how each participant might naturally reflect on the interaction. Be concise and insightful.",
			),
		)

		plugin.getAIResponse(messages).thenAccept { summaryResponse ->
			if (summaryResponse.isNullOrEmpty()) {
				plugin.logger.warning("Failed to get summary response for conversation")
				future.complete(null)
				return@thenAccept
			}

			val pendingNPCs = npcNames.size
			val completedNPCs = AtomicInteger(0)

			for (npcName in npcNames) {
				val npcSummaryMatch =
					Regex("(?m)^$npcName:\\s*(.*?)(?=^\\w+:|\$)", RegexOption.DOT_MATCHES_ALL)
						.find(summaryResponse)
						?.groupValues
						?.getOrNull(1)
						?.trim()

				if (!npcSummaryMatch.isNullOrEmpty()) {
					val npcData = plugin.npcDataManager.getNPCData(npcName) ?: continue

					evaluateMemorySignificance(npcSummaryMatch).thenAccept { significance ->
						val memory =
							Memory(
								id = "conversation_summary_${System.currentTimeMillis()}_$npcName",
								content = npcSummaryMatch,
								gameCreatedAt = plugin.timeService.getCurrentGameTime(),
								lastAccessed = plugin.timeService.getCurrentGameTime(),
								power = 0.85,
								_significance = significance,
							)

						npcData.memory.add(memory)
						plugin.relationshipManager.updateRelationshipFromMemory(memory, npcName)
						plugin.npcDataManager.saveNPCData(npcName, npcData)

						if (completedNPCs.incrementAndGet() >= pendingNPCs) {
							future.complete(null)
						}
					}.exceptionally { ex ->
						plugin.logger.warning("Error evaluating memory significance for $npcName: ${ex.message}")
						val memory =
							Memory(
								id = "conversation_summary_${System.currentTimeMillis()}_$npcName",
								content = npcSummaryMatch,
								gameCreatedAt = plugin.timeService.getCurrentGameTime(),
								lastAccessed = plugin.timeService.getCurrentGameTime(),
								power = 0.85,
								_significance = 1.0,
							)

						npcData.memory.add(memory)
						plugin.npcDataManager.saveNPCData(npcName, npcData)

						if (completedNPCs.incrementAndGet() >= pendingNPCs) {
							future.complete(null)
						}

						null
					}
				} else {
					plugin.logger.warning("No summary match found for $npcName in response")
					if (completedNPCs.incrementAndGet() >= pendingNPCs) {
						future.complete(null)
					}
				}
			}

			if (npcNames.isEmpty()) {
				future.complete(null)
			}
		}.exceptionally { e ->
			plugin.logger.warning("Error summarizing conversation: ${e.message}")
			future.completeExceptionally(e)
			null
		}

		return future
	}

	/**
	 * Summarizes a conversation from the perspective of a single NPC.
	 * Used when an NPC leaves an ongoing conversation.
	 *
	 * @param history The conversation history
	 * @param npcName The name of the NPC who is leaving the conversation
	 * @return CompletableFuture<Void> that completes when the memory has been saved
	 */
	fun summarizeConversationForSingleNPC(
		history: List<ConversationMessage>,
		npcName: String,
	): CompletableFuture<Void> {
		val future = CompletableFuture<Void>()

		// Return early if not enough conversation history
		if (history.isEmpty() || history.size < 3) {
			future.complete(null)
			return future
		}

		val messages = mutableListOf<ConversationMessage>()

		messages.addAll(history)
		messages.add(
			ConversationMessage(
				"system",
				"""
        Summarize this conversation from $npcName's perspective only.
        Create a concise summary of what happened in this conversation and what $npcName learned or felt about it.
        Focus only on $npcName's experience and perspective, not other participants.
        Keep the tone consistent with how $npcName would naturally reflect on this interaction.
        Be concise and insightful.
        """,
			),
		)

		plugin.getAIResponse(messages).thenAccept { summaryResponse ->
			if (summaryResponse.isNullOrEmpty()) {
				plugin.logger.warning("Failed to get summary response for $npcName")
				future.complete(null)
				return@thenAccept
			}

			val npcData = plugin.npcDataManager.getNPCData(npcName)
			if (npcData == null) {
				plugin.logger.warning("No NPC data found for $npcName")
				future.complete(null)
				return@thenAccept
			}

			// Clean up the summary if needed
			val cleanSummary = summaryResponse.trim()

			evaluateMemorySignificance(cleanSummary).thenAccept { significance ->
				val memory =
					Memory(
						id = "conversation_exit_summary_${System.currentTimeMillis()}_$npcName",
						content = cleanSummary,
						gameCreatedAt = plugin.timeService.getCurrentGameTime(),
						lastAccessed = plugin.timeService.getCurrentGameTime(),
						power = 0.85,
						_significance = significance,
					)

				npcData.memory.add(memory)
				plugin.relationshipManager.updateRelationshipFromMemory(memory, npcName)
				plugin.npcDataManager.saveNPCData(npcName, npcData)

				future.complete(null)
			}.exceptionally { ex ->
				plugin.logger.warning("Error evaluating memory significance for $npcName: ${ex.message}")

				// Create memory with default significance on error
				val memory =
					Memory(
						id = "conversation_exit_summary_${System.currentTimeMillis()}_$npcName",
						content = cleanSummary,
						gameCreatedAt = plugin.timeService.getCurrentGameTime(),
						lastAccessed = plugin.timeService.getCurrentGameTime(),
						power = 0.85,
						_significance = 1.0,
					)

				npcData.memory.add(memory)
				plugin.npcDataManager.saveNPCData(npcName, npcData)

				future.complete(null)
				null
			}
		}.exceptionally { e ->
			plugin.logger.warning("Error summarizing conversation for $npcName: ${e.message}")
			future.completeExceptionally(e)
			null
		}

		return future
	}

	fun evaluateMemorySignificance(memoryContent: String): CompletableFuture<Double> {
		val prompt = mutableListOf<ConversationMessage>()

		prompt.add(
			ConversationMessage(
				"system",
				"""
        Evaluate the emotional significance of this memory on a scale from 1.0 (ordinary, mundane) to 5.0 (deeply emotional, traumatic, or life-changing).
        
        Consider these factors:
        - Emotional intensity (anger, joy, sorrow, etc.)
        - Long-term impact on relationships
        - Contains betrayal, heartbreak, or pivotal information
        - Personal importance to the character
        
        Respond ONLY with a number between 1.0 and 5.0.
        """,
			),
		)

		prompt.add(ConversationMessage("user", memoryContent))

		return plugin.getAIResponse(prompt).thenApply { response ->
			val significanceValue = response?.trim()?.toDoubleOrNull() ?: 1.0

			// Ensure value is within valid range
			significanceValue.coerceIn(1.0, 5.0)
		}
	}
}
