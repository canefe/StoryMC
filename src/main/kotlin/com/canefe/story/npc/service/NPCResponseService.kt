package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.memory.Memory
import com.canefe.story.util.EssentialsUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min

class NPCResponseService(
    private val plugin: Story,
) {
    // generate get() reference to plugin.npcContextGenerator
    private val contextService = plugin.npcContextGenerator

    fun generateNPCResponse(
        npc: NPC? = null,
        responseContext: List<String>,
        broadcast: Boolean = true,
        player: Player? = null,
        rich: Boolean = false,
        isConversation: Boolean = true,
    ): CompletableFuture<String> {
        val prompts: MutableList<ConversationMessage> = ArrayList()

        // Fallback entity is a player character.
        val isPlayerCharacter = player != null && npc == null
        val originalCharName =
            if (isPlayerCharacter) {
                EssentialsUtils.getNickname(player.name)
            } else {
                npc?.name ?: "Unknown"
            }

        // Get NPC context - this might trigger name resolution and NPC replacement
        val npcContext = contextService.getOrCreateContextForNPC(originalCharName)

        // Check if NPC was replaced during context generation
        var actualNPC = npc
        var actualCharName = originalCharName

        if (!isPlayerCharacter && npc != null) {
            // Find the conversation this NPC is part of
            val conversation = plugin.conversationManager.getConversation(npc)
            if (conversation != null) {
                // Check if the original NPC is still in the conversation
                if (!conversation.hasNPC(npc)) {
                    // The NPC was replaced, find the new one by looking for NPCs with similar
                    // context
                    val newNPC =
                        conversation.npcs.find { conversationNPC ->
                            // Try to match by canonical name if available in context
                            npcContext?.let { context -> conversationNPC.name == context.name }
                                ?: false
                        }

                    if (newNPC != null) {
                        actualNPC = newNPC
                        actualCharName = newNPC.name
                        plugin.logger.info(
                            "NPC replacement detected: using ${newNPC.name} instead of ${npc.name}",
                        )

                        // Refresh the context for the new NPC to get updated bio if generated
                        contextService.getOrCreateContextForNPC(actualCharName)
                    } else {
                        // If we can't find a replacement, the conversation is probably broken
                        plugin.logger.warning(
                            "NPC ${npc.name} was replaced but no suitable replacement found in conversation",
                        )
                        return CompletableFuture.completedFuture(
                            "*The guard seems to have disappeared into the crowd.*",
                        )
                    }
                }
            }
        }

        // Add basic roleplay instruction with the actual character name
        prompts.add(
            ConversationMessage(
                "system",
                "You are roleplaying as $actualCharName in a fantasy medieval world.",
            ),
        )

        // Add location context with clear section header
        val location = npcContext?.location

        if (location != null) {
            prompts.add(
                ConversationMessage(
                    "system",
                    "===HOME(CONTEXT) LOCATION===\n" +
                        location.getContextForPrompt(plugin.locationManager),
                ),
            )
        }

        val entityPos =
            if (isPlayerCharacter) {
                player.location
            } else if (actualNPC != null) {
                plugin.conversationManager.getRealEntityForNPC(actualNPC)?.location
            } else {
                return CompletableFuture.completedFuture("")
            }

        // Check if NPC is spawned to determine actual location
        if (entityPos != null) {
            val actualLocation = plugin.locationManager.getLocationByPosition(entityPos, 150.0)

            // If actual location exists and differs from context location
            if (actualLocation != null && location != null) {
                // Check if they're exactly the same location
                val exactSameLocation = (location.name == actualLocation.name)

                // If not the exact same location
                if (!exactSameLocation) {
                    // Check if they share the same parent location
                    val sameParentLocation =
                        (
                            location.hasParent() &&
                                actualLocation.hasParent() &&
                                location.parentLocationName ==
                                actualLocation.parentLocationName
                        ) ||
                            (
                                !location.hasParent() &&
                                    actualLocation.parentLocationName == location.name
                            )

                    // Add current location information with appropriate detail level
                    val locationInfo =
                        if (sameParentLocation) {
                            // Only include this specific sublocation's context, not parent
                            // context
                            "===CURRENT SUBLOCATION===\n" +
                                "You are currently at ${actualLocation.name} (within ${actualLocation.parentLocationName}).\n" +
                                "Sublocation details:\n" +
                                actualLocation.context.joinToString("\n") { "- $it" }
                        } else {
                            // Include full context (with parent context) for completely
                            // different locations
                            "===CURRENT LOCATION===\n" +
                                "You are currently physically at ${actualLocation.name}.\n" +
                                actualLocation.getContextForPrompt(plugin.locationManager)
                        }

                    prompts.add(ConversationMessage("system", locationInfo))
                }
            }
        }

        // Lorebook context with clear section header
        val lorebookContexts = mutableListOf<String>()
        if (player != null) {
            plugin.conversationManager.getConversation(player)?.let { conversation ->
                lorebookContexts.addAll(
                    plugin.conversationManager.checkAndGetLoreContexts(conversation).map { lore ->
                        "${lore.loreName} - ${lore.context}"
                    },
                )
            }
        }

        if (actualNPC != null) {
            plugin.conversationManager.getConversation(actualNPC)?.let { conversation ->
                lorebookContexts.addAll(
                    plugin.conversationManager.checkAndGetLoreContexts(conversation).map { lore ->
                        "${lore.loreName} - ${lore.context}"
                    },
                )
            }
        }

        if (lorebookContexts.isNotEmpty()) {
            prompts.add(
                ConversationMessage(
                    "system",
                    "===KNOWLEDGE===\nYou know the following information:\n" +
                        lorebookContexts.joinToString("\n\n"),
                ),
            )
        }

        // Add the NPC context with clear section headers
        if (npcContext != null) {
            prompts.add(
                ConversationMessage(
                    "system",
                    "===CHARACTER INFORMATION===\n" + npcContext.context,
                ),
            )

            // Add appearance information if available
            if (npcContext.appearance.isNotEmpty()) {
                prompts.add(
                    ConversationMessage(
                        "system",
                        "===PHYSICAL APPEARANCE===\n" + npcContext.appearance,
                    ),
                )
            }
        }

        // Finally, add NPCs memories with clear section header
        npcContext?.getMemoriesForPrompt(plugin.timeService)?.let { memories ->
            prompts.add(
                ConversationMessage(
                    "system",
                    "===MEMORY===\n" + memories,
                ),
            )
        }

        // Include current time and season and date
        prompts.add(
            ConversationMessage(
                "system",
                "===CURRENT TIME===\n" +
                    "The current time is ${plugin.timeService.getHours()}:${plugin.timeService.getMinutes()} at date ${plugin.timeService.getFormattedDate()} " +
                    "in the ${plugin.timeService.getSeason()} season.",
            ),
        )

        // Add specific instructions at the end for emphasis
        // Add general context with clear section header
        if (isConversation) {
            prompts.add(ConversationMessage("system", "===INSTRUCTIONS===\n"))
            prompts.add(
                ConversationMessage(
                    "system",
                    contextService.getGeneralContexts().joinToString(separator = "\n"),
                ),
            )
        }

        // Add the response context with clear section header
        if (responseContext.isNotEmpty()) {
            prompts.add(
                ConversationMessage(
                    "system",
                    responseContext.joinToString(separator = "\n"),
                ),
            )
        }

        return plugin.getAIResponse(prompts, lowCost = !rich).thenApply { response ->
            val finalResponse = response?.trim() ?: ""

            // Fail the future if response is empty or null
            if (finalResponse.isEmpty()) {
                throw RuntimeException("AI response was empty or null")
            }

            if (broadcast) {
                if (npc != null) {
                    // Broadcast to all players in the vicinity
                    plugin.npcMessageService.broadcastNPCStreamMessage(
                        finalResponse,
                        npc,
                        npcContext = npcContext,
                    )
                    plugin.npcMessageService.broadcastNPCMessage(
                        finalResponse,
                        npc,
                        npcContext = npcContext,
                    )
                } else if (player != null) {
                    // Send to the player character
                    plugin.npcMessageService.broadcastPlayerMessage(
                        finalResponse,
                        player,
                    )
                }
            }
            finalResponse
        }
    }

    /**
     * Generates an NPC response with a typewriter effect visible to all nearby players
     *
     * @param npc The NPC that will speak
     * @param responseContext Context messages for generating the response
     * @param typingSpeed Characters to reveal per tick (default 2)
     * @param radius How far away players can see the typing effect
     * @return CompletableFuture<String> with the final response
     */
    fun generateNPCResponseWithTypingEffect(
        npc: NPC,
        npcContext: NPCContext?,
        responseContext: List<String>,
        typingSpeed: Int = 8,
        radius: Double = plugin.config.chatRadius,
    ): CompletableFuture<String> {
        val result = CompletableFuture<String>()

        // Generate the full response first
        generateNPCResponse(npc, responseContext, false).thenAccept { response ->
            // Start the typing effect for this NPC
            plugin.typingSessionManager.startTyping(
                npc = npc,
                npcContext = npcContext,
                fullText = response,
                typingSpeed = typingSpeed,
                radius = radius,
                messageFormat = "<npc_typing><npc_text>",
            )

            // When complete, maybe broadcast to chat if needed
            Bukkit
                .getScheduler()
                .runTaskLater(
                    plugin,
                    Runnable {
                        val npcContext = contextService.getOrCreateContextForNPC(npc.name)
                        plugin.npcMessageService.broadcastNPCStreamMessage(
                            response,
                            npc,
                            npcContext = npcContext,
                        )
                        plugin.npcMessageService.broadcastNPCMessage(
                            response,
                            npc,
                            npcContext = npcContext,
                        )

                        // remove holograms after 3 seconds
                        plugin.typingSessionManager.stopTyping(npc.uniqueId)

                        result.complete(response)
                    },
                    (response.length / typingSpeed + 10).toLong(),
                )
        }

        return result
    }

    /**
     * Generates a behavioral directive for an NPC to guide their response based on conversation
     * context.
     *
     * @param conversation The current conversation
     * @param npc The NPC who will be responding
     * @return CompletableFuture<String> containing the behavioral directive
     */
    fun generateBehavioralDirective(
        conversation: Conversation,
        npc: NPC,
    ): CompletableFuture<String> {
        // Get only the behavioral directive prompt - no need to duplicate context that
        // generateNPCResponse already adds

        val recentMessages =
            conversation.history
                .takeLast(10)
                .map { it.content }
                .joinToString("\n")

        var relationshipContext = ""

        // Add relationship context with clear section header
        val relationships = plugin.relationshipManager.getAllRelationships(npc.name)
        if (relationships.isNotEmpty()) {
            relationshipContext =
                plugin.relationshipManager.buildRelationshipContext(
                    npc.name,
                    relationships,
                    conversation,
                )
        }

        // Use PromptService to get the behavioral directive prompt
        val directivePrompt =
            plugin.promptService.getBehavioralDirectivePrompt(
                recentMessages,
                relationshipContext,
                npc.name,
            )

        val directivePromptList = listOf(directivePrompt)

        // Use the existing generateNPCResponse method with broadcast set to false
        return generateNPCResponse(npc, directivePromptList, false)
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

        // Use PromptService to get the speaker selection prompt
        val availableCharacters =
            java.lang.String.join(
                ", ",
                conversation.npcs.filterNot { conversation.mutedNPCs.contains(it) }.map {
                    it.name
                },
            )

        val speakerPrompt = plugin.promptService.getSpeakerSelectionPrompt(availableCharacters)

        // Add system prompt for NPC selection
        speakerSelectionPrompt.add(ConversationMessage("system", speakerPrompt))

        // Add conversation context
        speakerSelectionPrompt.addAll(contextMessages)

        // Add a default NPC if the list is empty to avoid errors
        if (conversation.npcNames.isEmpty()) {
            future.complete(null)
            return future
        }

        // Run this asynchronously to avoid blocking
        plugin
            .getAIResponse(speakerSelectionPrompt, lowCost = true)
            .thenApply { response ->
                val speakerSelection = response?.trim() ?: ""
                if (speakerSelection.isNotEmpty() &&
                    conversation.npcNames.contains(speakerSelection)
                ) {
                    speakerSelection
                } else {
                    conversation.npcNames[0]
                }
            }.thenAccept { future.complete(it) }
            .exceptionally { e ->
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
        // Use PromptService to get the NPC greeting prompt
        val greetingPrompt = plugin.promptService.getNpcGreetingPrompt(npc.name, target)

        val prompts: MutableList<String> = ArrayList()
        prompts.add(greetingPrompt)
        prompts.addAll(greetingContext ?: emptyList())

        val response =
            generateNPCResponse(npc, listOf(prompts.joinToString(separator = "\n")), false)
                .join()

        return response
    }

    fun generateNPCGoodbye(
        npc: NPC,
        goodbyeContext: List<String>? = null,
    ): CompletableFuture<String?> {
        // Use PromptService to get the NPC goodbye prompt
        val goodbyePrompt = plugin.promptService.getNpcGoodbyePrompt(npc.name)

        val prompts: MutableList<String> = ArrayList()
        prompts.addAll(goodbyeContext ?: emptyList())
        prompts.add(goodbyePrompt)

        val response =
            generateNPCResponse(npc, listOf(prompts.joinToString(separator = "\n")), false)
                .join()

        plugin.npcMessageService.broadcastNPCMessage(
            response,
            npc,
            npcContext = contextService.getOrCreateContextForNPC(npc.name),
        )

        return CompletableFuture.completedFuture(response)
    }

    fun summarizeConversation(conversation: Conversation): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val history = conversation.history
        val npcNames = conversation.npcNames
        // convert to EssentialUtils.getNickname(playerName(!))
        val playerNames =
            conversation.players.map {
                Bukkit.getPlayer(it).let { player ->
                    if (player != null) {
                        EssentialsUtils.getNickname(player.name)
                    } else {
                        null
                    }
                }
            }

        // Return early if not enough conversation history
        if (history.isEmpty() || history.size < 3) {
            if (plugin.config.debugMessages) {
                plugin.logger.info(
                    "Skipping conversation summary: insufficient history (${history.size} messages)",
                )
            }
            future.complete(null)
            return future
        }

        // Return early if no NPCs to summarize for
        if (npcNames.isEmpty()) {
            if (plugin.config.debugMessages) {
                plugin.logger.info("Skipping conversation summary: no NPCs to process")
            }
            future.complete(null)
            return future
        }

        val startTime = System.currentTimeMillis()
        if (plugin.config.debugMessages) {
            plugin.logger.info(
                "Starting conversation summary for ${npcNames.size} NPCs and ${playerNames.size} players",
            )
        }

        // Filter out generic NPCs upfront
        val npcNamesToProcess =
            npcNames.filter { npcName ->
                val npcData = plugin.npcDataManager.getNPCData(npcName)
                val isGeneric = npcData?.generic == true
                if (isGeneric && plugin.config.debugMessages) {
                    plugin.logger.info("Skipping memory creation for generic NPC: $npcName")
                }
                !isGeneric
            }

        // Create list of all futures for concurrent processing
        val allFutures = mutableListOf<CompletableFuture<Unit>>()

        // Process all NPCs concurrently
        npcNamesToProcess.forEach { npcName ->
            if (plugin.config.debugMessages) {
                plugin.logger.info("Starting summary processing for NPC: $npcName")
            }
            val npcFuture =
                summarizeConversationForSingleNPC(history, npcName).handle { _, throwable ->
                    if (throwable != null) {
                        plugin.logger.warning(
                            "Error summarizing conversation for $npcName: ${throwable.message}",
                        )
                    } else if (plugin.config.debugMessages) {
                        plugin.logger.info("Completed summary processing for NPC: $npcName")
                    }
                }
            allFutures.add(npcFuture)
        }

        // Process all players concurrently
        playerNames.filterNotNull().forEach { playerName ->
            if (plugin.config.debugMessages) {
                plugin.logger.info("Starting summary processing for player: $playerName")
            }
            val playerFuture =
                summarizeConversationForSingleNPC(history, playerName, isPlayer = true)
                    .handle { _, throwable ->
                        if (throwable != null) {
                            plugin.logger.warning(
                                "Error summarizing conversation for $playerName: ${throwable.message}",
                            )
                        } else if (plugin.config.debugMessages) {
                            plugin.logger.info(
                                "Completed summary processing for player: $playerName",
                            )
                        }
                    }
            allFutures.add(playerFuture)
        }

        // Wait for all futures to complete
        if (allFutures.isNotEmpty()) {
            CompletableFuture.allOf(*allFutures.toTypedArray()).whenComplete { _, _ ->
                val endTime = System.currentTimeMillis()
                if (plugin.config.debugMessages) {
                    plugin.logger.info(
                        "Conversation summary completed for all entities in ${endTime - startTime}ms",
                    )
                }
                future.complete(null)
            }
        } else {
            if (plugin.config.debugMessages) {
                plugin.logger.info("No entities to process for conversation summary")
            }
            future.complete(null)
        }

        return future
    }

    /**
     * Summarizes a conversation from the perspective of a single NPC. Used when an NPC leaves an
     * ongoing conversation.
     *
     * @param history The conversation history
     * @param npcName The name of the NPC who is leaving the conversation
     * @return CompletableFuture<Void> that completes when the memory has been saved
     */
    fun summarizeConversationForSingleNPC(
        history: List<ConversationMessage>,
        npcName: String,
        isPlayer: Boolean = false,
    ): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val startTime = System.currentTimeMillis()

        if (plugin.config.debugMessages) {
            plugin.logger.info(
                "Starting conversation summary for $npcName (${history.size} messages)",
            )
        }

        // Return early if not enough conversation history
        if (history.isEmpty() || history.size < 3) {
            if (plugin.config.debugMessages) {
                plugin.logger.info(
                    "Skipping summary for $npcName: insufficient history (${history.size} messages)",
                )
            }
            future.complete(null)
            return future
        }

        // Check if NPC is generic - skip memory creation for generic NPCs
        val npcData = plugin.npcDataManager.getNPCData(npcName)
        if (npcData?.generic == true && !isPlayer) {
            if (plugin.config.debugMessages) {
                plugin.logger.info("Skipping memory creation for generic NPC: $npcName")
            }
            future.complete(null)
            return future
        }

        // Find the NPC by name
        var npc =
            CitizensAPI.getNPCRegistry().firstOrNull {
                it.name.equals(npcName, ignoreCase = true) ||
                    it.name.trim().equals(npcName.trim(), ignoreCase = true)
            }

        // If still null, try searching across all registries
        if (npc == null) {
            if (plugin.config.debugMessages) {
                plugin.logger.info("Searching for NPC '$npcName' across all registries...")
            }
            for (registry in CitizensAPI.getNPCRegistries()) {
                registry.forEach {
                    if (it.name.equals(npcName, ignoreCase = true) ||
                        it.name.trim().equals(npcName.trim(), ignoreCase = true)
                    ) {
                        npc = it
                        if (plugin.config.debugMessages) {
                            plugin.logger.info(
                                "Found NPC '${it.name}' in registry ${registry.name}",
                            )
                        }
                        return@forEach
                    }
                }
            }
        }

        val player =
            Bukkit.getOnlinePlayers().firstOrNull {
                EssentialsUtils.getNickname(it.name) == npcName
            }

        // set npc to null if isPlayer is true
        if (isPlayer) {
            npc = null
        }
        if (player != null) {
            npc = null
        }

        // Convert conversation history to a format suitable for responseContext
        val conversationText =
            history.filter { it.role != "system" }.map { it.content }.joinToString("\n")

        // Use PromptService to get the conversation summary prompt
        val summaryPrompt = plugin.promptService.getConversationSummaryPrompt(npcName)
        val responseContext =
            listOf(
                "===COMBINED MEMORY TASK===",
                summaryPrompt,
                "",
                "===EVENT===\n$conversationText",
            )

        if (plugin.config.debugMessages) {
            plugin.logger.info("Generating combined summary and significance for $npcName...")
        }

        generateNPCResponse(
            npc,
            responseContext,
            false,
            player,
            rich = true,
            isConversation = false,
        ).thenAccept { combinedResponse ->
            if (combinedResponse.isNullOrBlank()) {
                plugin.logger.warning("Failed to get combined response for $npcName")
                future.complete(null)
                return@thenAccept
            }

            val npcDataForMemory = plugin.npcDataManager.getNPCData(npcName)
            if (npcDataForMemory == null) {
                plugin.logger.warning("No NPC data found for $npcName")
                future.complete(null)
                return@thenAccept
            }

            // Parse JSON response
            var memoryContent = ""
            var significance = 1.0

            try {
                // Clean the response by removing markdown code block formatting
                val cleanedResponse =
                    combinedResponse
                        .trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                val jsonResponse = JsonParser.parseString(cleanedResponse) as JsonObject

                // Extract memory content
                if (jsonResponse.has("memory")) {
                    memoryContent = jsonResponse.get("memory").asString.trim()
                }

                // Extract significance
                if (jsonResponse.has("significance")) {
                    val significanceElement = jsonResponse.get("significance")
                    significance =
                        when {
                            significanceElement.isJsonPrimitive &&
                                significanceElement.asJsonPrimitive.isNumber -> {
                                significanceElement.asDouble.coerceIn(1.0, 5.0)
                            }

                            significanceElement.isJsonPrimitive &&
                                significanceElement.asJsonPrimitive.isString -> {
                                significanceElement
                                    .asString
                                    .toDoubleOrNull()
                                    ?.coerceIn(1.0, 5.0)
                                    ?: 1.0
                            }

                            else -> 1.0
                        }
                }

                if (plugin.config.debugMessages) {
                    plugin.logger.info(
                        "Successfully parsed JSON response for $npcName - Memory: ${
                            memoryContent.take(
                                50,
                            )
                        }..., Significance: $significance",
                    )
                }
            } catch (e: JsonSyntaxException) {
                // Fallback: if JSON parsing fails, try to extract from text format
                if (plugin.config.debugMessages) {
                    plugin.logger.info(
                        "JSON parsing failed for $npcName, attempting text fallback: ${e.message}",
                    )
                }

                val lines = combinedResponse.trim().lines()
                var isMemorySection = false
                val memoryLines = mutableListOf<String>()

                for (line in lines) {
                    when {
                        line.startsWith("MEMORY:", ignoreCase = true) -> {
                            isMemorySection = true
                            val memoryStart = line.substringAfter("MEMORY:", "").trim()
                            if (memoryStart.isNotEmpty()) {
                                memoryLines.add(memoryStart)
                            }
                        }

                        line.startsWith("SIGNIFICANCE:", ignoreCase = true) -> {
                            isMemorySection = false
                            val significanceStr =
                                line.substringAfter("SIGNIFICANCE:", "").trim()
                            significance =
                                significanceStr.toDoubleOrNull()?.coerceIn(1.0, 5.0)
                                    ?: 1.0
                        }

                        isMemorySection && line.trim().isNotEmpty() -> {
                            memoryLines.add(line.trim())
                        }
                    }
                }

                memoryContent = memoryLines.joinToString(" ").trim()

                // Ultimate fallback: use entire response as memory content
                if (memoryContent.isEmpty()) {
                    memoryContent = combinedResponse.trim()
                    if (plugin.config.debugMessages) {
                        plugin.logger.info(
                            "Using complete response as fallback memory for $npcName",
                        )
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error parsing response for $npcName: ${e.message}")
                memoryContent = combinedResponse.trim()
                significance = 1.0
            }

            if (plugin.config.debugMessages) {
                plugin.logger.info(
                    "Parsed memory for $npcName (significance: $significance): ${
                        memoryContent.take(
                            100,
                        )
                    }...",
                )
            }

            val memory =
                Memory(
                    id =
                        "conversation_exit_summary_${System.currentTimeMillis()}_$npcName",
                    content = memoryContent,
                    gameCreatedAt = plugin.timeService.getCurrentGameTime(),
                    lastAccessed = plugin.timeService.getCurrentGameTime(),
                    power = 0.85,
                    _significance = significance,
                )

            npcDataForMemory.memory.add(memory)
            plugin.relationshipManager.updateRelationshipFromMemory(memory, npcName)
            plugin.npcDataManager.saveNPCData(npcName, npcDataForMemory)

            val endTime = System.currentTimeMillis()
            if (plugin.config.debugMessages) {
                plugin.logger.info(
                    "Completed conversation summary for $npcName in ${endTime - startTime}ms",
                )
            }

            future.complete(null)
        }.exceptionally { e ->
            val endTime = System.currentTimeMillis()
            plugin.logger.warning(
                "Error summarizing conversation for $npcName after ${endTime - startTime}ms: ${e.message}",
            )
            future.completeExceptionally(e)
            null
        }

        return future
    }

    fun evaluateMemorySignificance(memoryContent: String): CompletableFuture<Double> {
        val prompt = mutableListOf<ConversationMessage>()

        // Use PromptService to get the memory significance prompt
        val significancePrompt = plugin.promptService.getMemorySignificancePrompt()

        prompt.add(ConversationMessage("system", significancePrompt))
        prompt.add(ConversationMessage("user", memoryContent))

        return plugin.getAIResponse(prompt, lowCost = true).thenApply { response ->
            val significanceValue = response?.trim()?.toDoubleOrNull() ?: 1.0

            // Ensure value is within valid range
            significanceValue.coerceIn(1.0, 5.0)
        }
    }

    /**
     * Generates a memory for an NPC based on the provided context and type.
     * @param npcName The name of the NPC
     * @param type The type of memory (event, conversation, observation, experience)
     * @param context The context for the memory
     * @return CompletableFuture<Memory?> that completes with the created memory or null if faileds
     */
    fun generateNPCMemory(
        npcName: String,
        type: String,
        context: String,
    ): CompletableFuture<Memory?> {
        val future = CompletableFuture<Memory?>()

        // Check if NPC exists
        val npcData = plugin.npcDataManager.getNPCData(npcName)
        if (npcData == null) {
            future.complete(null)
            return future
        }

        // Create synthetic conversation history
        val syntheticConversation = mutableListOf<ConversationMessage>()

        // First message: Memory type explanation
        syntheticConversation.add(
            ConversationMessage(
                "system",
                when (type) {
                    "event" ->
                        "This is an important event that $npcName experienced or witnessed."

                    "conversation" ->
                        "This is a conversation that $npcName had with someone."

                    "observation" -> "This is something that $npcName observed or noticed."
                    "experience" -> "This is a personal experience or memory of $npcName."
                    else -> "This is an important memory for $npcName."
                },
            ),
        )

        // Second message: NPC context from data
        val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)?.context
        if (npcContext != null) {
            syntheticConversation.add(ConversationMessage("system", npcContext))
        }

        // Use PromptService to get the NPC memory generation prompt
        val memoryPrompt = plugin.promptService.getNpcMemoryGenerationPrompt(npcName)

        // Third message: Instruction
        syntheticConversation.add(ConversationMessage("system", memoryPrompt))

        // Add the player-provided context
        syntheticConversation.add(ConversationMessage("user", context))

        // Process using existing functionality
        summarizeConversationForSingleNPC(syntheticConversation, npcName)
            .thenAccept {
                val latestMemory =
                    plugin.npcDataManager
                        .getNPCData(npcName)
                        ?.memory
                        ?.lastOrNull()

                future.complete(latestMemory)
            }.exceptionally { e ->
                plugin.logger.warning("Failed to create memory for $npcName: ${e.message}")
                future.completeExceptionally(e)
                null
            }

        return future
    }

    /** Generates a culturally appropriate name based on location and context */
    fun generateNPCName(
        location: String,
        role: String,
        context: String,
    ): CompletableFuture<String> {
        val messages = mutableListOf<ConversationMessage>()

        // Get location context for cultural information
        val storyLocation = plugin.locationManager.getLocation(location)
        if (storyLocation != null) {
            messages.add(
                ConversationMessage(
                    "system",
                    storyLocation.getContextForPrompt(plugin.locationManager),
                ),
            )
        }

        // Add general world context
        messages.add(
            ConversationMessage(
                "system",
                plugin.npcContextGenerator.getGeneralContexts().joinToString("\n"),
            ),
        )

        // Find relevant lore for naming conventions
        val loreContexts = plugin.lorebookManager.findLoresByKeywords("$location culture naming")
        if (loreContexts.isNotEmpty()) {
            messages.add(
                ConversationMessage(
                    "system",
                    "Cultural and naming lore:\n" +
                        loreContexts.joinToString("\n\n") {
                            "- ${it.loreName}: ${it.context}"
                        },
                ),
            )
        }

        // Use PromptService to get the NPC name generation prompt
        val namePrompt = plugin.promptService.getNpcNameGenerationPrompt(location, role, context)

        messages.add(ConversationMessage("system", namePrompt))

        return plugin.getAIResponse(messages, lowCost = true).thenApply { response ->
            response?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Generated_NPC_${System.currentTimeMillis()}"
        }
    }
}
