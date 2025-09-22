package com.canefe.story.conversation

import com.canefe.story.Story
import com.canefe.story.api.event.*
import com.canefe.story.audio.VoiceManager
import com.canefe.story.information.ConversationInformationSource
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.lore.LoreBookManager.LoreContext
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendInfo
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture

class ConversationManager private constructor(
    private val plugin: Story,
    private val npcContextGenerator: NPCContextGenerator,
    private val npcResponseService: NPCResponseService,
    private val worldInformationManager: WorldInformationManager, // Add this dependency
) {
    private val repository = ConversationRepository()
    private val hologramManager = ConversationHologramManager(plugin)
    private val voiceManager = VoiceManager(plugin) // Add VoiceManager

    // Map to store scheduled tasks by conversation
    private val scheduledTasks = mutableMapOf<Conversation, Int>()

    // Map to store debounce timers for each conversation
    private val responseTimers = mutableMapOf<Int, Int>()

    private val endingConversations = Collections.synchronizedSet(mutableSetOf<Int>())

    // Getter for scheduled tasks
    fun getScheduledTasks(): MutableMap<Conversation, Int> = scheduledTasks

    val activeConversations: List<Conversation>
        get() = repository.getAllActiveConversations()

    // Core conversation management methods
    fun startConversation(
        player: Player,
        npcs: List<NPC>,
    ): Conversation {
        // Check if player is already in a conversation and end it
        val existingConversation = repository.getConversationByPlayer(player)
        existingConversation?.let {
            endConversation(it)
        }

        // Create a new conversation
        val participants = mutableListOf(player.uniqueId)
        val conversation =
            Conversation(
                _players = participants,
                initialNPCs = npcs,
            )

        // If chat is not enabled, allow manual conversation
        if (!plugin.config.chatEnabled) {
            conversation.chatEnabled = false
        }

        // Add to repository
        repository.addConversation(conversation)

        // Schedule proximity check for this conversation
        scheduleProximityCheck(conversation)

        // Notify player
        val npcNames = npcs.joinToString(", ") { it.name }
        player.sendInfo("You are now in a conversation with: <yellow>$npcNames</yellow>")

        // Call StartConversationEvent
        val startEvent = ConversationStartEvent(player, npcs, conversation)
        if (System.getProperty("mockbukkit") == "true") {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    Bukkit.getPluginManager().callEvent(startEvent)
                },
            )
        } else {
            Bukkit.getPluginManager().callEvent(startEvent)
        }

        // Check if cancelled
        if (startEvent.isCancelled) {
            plugin.conversationManager
                .endConversation(conversation)
                .exceptionally { ex ->
                    plugin.logger.warning("Error ending cancelled conversation: ${ex.message}")
                    null
                }
            player.sendInfo("The conversation couldn't be started.")
        }

        return conversation
    }

    // start conversation, no players
    fun startConversation(npcs: List<NPC>): CompletableFuture<Conversation> {
        // Empty player ist
        val participants = mutableListOf<UUID>()

        // Create a new conversation
        val conversation =
            Conversation(
                _players = participants,
                initialNPCs = npcs,
            )

        // If chat is not enabled, allow manual conversation
        if (!plugin.config.chatEnabled) {
            conversation.chatEnabled = false
        }

        // Add to repository
        repository.addConversation(conversation)

        return CompletableFuture.completedFuture(conversation)
    }

    // Start player-to-player conversation (no NPCs required)
    fun startPlayerConversation(
        initiatingPlayer: Player,
        targetPlayers: List<Player>,
    ): Conversation {
        // Check if initiating player is already in a conversation and end it
        val existingConversation = repository.getConversationByPlayer(initiatingPlayer)
        existingConversation?.let {
            endConversation(it)
        }

        // Create participant list including the initiating player
        val participants = mutableListOf(initiatingPlayer.uniqueId)
        participants.addAll(targetPlayers.map { it.uniqueId })

        // Create a new conversation with no NPCs
        val conversation =
            Conversation(
                _players = participants,
                initialNPCs = emptyList(),
            )

        // If chat is not enabled, allow manual conversation
        if (!plugin.config.chatEnabled) {
            conversation.chatEnabled = false
        }

        // Add to repository
        repository.addConversation(conversation)

        // Schedule proximity check for this conversation
        scheduleProximityCheck(conversation)

        // Notify all players in the conversation
        val allPlayers = listOf(initiatingPlayer) + targetPlayers
        val playerNames = allPlayers.joinToString(", ") { it.name }
        allPlayers.forEach { player ->
            player.sendInfo("You are now in a conversation with: <yellow>$playerNames</yellow>")
        }

        return conversation
    }

    fun endConversationWithGoodbye(
        conversation: Conversation,
        goodbyeContext: List<String>? = null,
    ) {
        // Get a random NPC
        val npc =
            conversation.npcs.randomOrNull()
                ?: run {
                    plugin.logger.warning("No NPCs found in the conversation.")
                    return
                }

        npcResponseService.generateNPCGoodbye(npc, goodbyeContext)
        // wait a few seconds
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                // End the conversation
                endConversation(conversation)
            },
            20L * 2, // 5 seconds delay
        )
    }

    fun endConversationWithGoodbye(
        npc: NPC,
        goodbyeContext: List<String>? = null,
    ) {
        // Get the conversation
        val conversation =
            repository.getConversationByNPC(npc)
                ?: run {
                    plugin.logger.warning("No conversation found for NPC ${npc.name}.")
                    return
                }

        npcResponseService.generateNPCGoodbye(npc, goodbyeContext)
        // wait a few seconds
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                // End the conversation
                endConversation(conversation)
            },
            20L * 2, // 2 seconds delay
        )
    }

    fun endConversation(
        conversation: Conversation,
        dontRemember: Boolean = false,
    ): CompletableFuture<Void> {
        // Check if the conversation is already being ended
        if (endingConversations.contains(conversation.id)) {
            // Return a completed future since the conversation is already being ended
            plugin.logger.info("Conversation ${conversation.id} is already being ended, ignoring duplicate request")
            return CompletableFuture.completedFuture(null)
        }

        // Mark this conversation as being ended
        endingConversations.add(conversation.id)

        val future = CompletableFuture<Void>()

        // Clean up scheduled proximity check task if it exists
        val taskId = scheduledTasks[conversation]
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId)
            scheduledTasks.remove(conversation)
        }

        // If we are streaming, remove dialogue boxes by sending a secret message to client players
        // "<npc_typing_end>id:npc_uuid"
        if (plugin.config.streamMessages) {
            val lastSpeaker = conversation.lastSpeakingNPC
            if (lastSpeaker != null) {
                val lastSpeakerUUID = lastSpeaker.uniqueId
                conversation.players.forEach { uuid ->
                    val player = Bukkit.getPlayer(uuid)
                    // This will trigger the client to remove the dialogue box (Story Client MOD)
                    player?.sendInfo("<npc_typing_end>id:$lastSpeakerUUID")
                }
            }
        }
        // size of user messages
        val userMessageCount =
            conversation.history.count { it.role != "system" }
        // Only process conversation data if significant
        if (userMessageCount > 2 && !dontRemember) {
            conversation.players.forEach { uuid ->
                val player = Bukkit.getPlayer(uuid)
                player?.sendInfo("<i>The conversation is ending...")

                // Call EndConversationEvent
                if (player != null) {
                    val endEvent = ConversationEndEvent(player, conversation.npcs, conversation)
                    Bukkit.getPluginManager().callEvent(endEvent)
                }
            }

            // Get the conversation location (where the conversation is happening)
            val conversationLocation =
                conversation.npcs.firstOrNull()?.let { npc ->
                    // Get the actual physical location where the NPC currently is
                    npcContextGenerator.getOrCreateContextForNPC(npc.name)?.location?.name
                } ?: "Village" // Default location

            // Create conversation information source
            val conversationSource =
                ConversationInformationSource(
                    messages = conversation.history,
                    npcNames = conversation.npcs.map { it.name },
                    locationName = conversationLocation,
                    significance = calculateConversationSignificance(conversation),
                )

            // Process information using the new system
            worldInformationManager.processInformation(conversationSource)

            // Feed the current session with conversation history
            // String builder context
            val sessionContext = StringBuilder()
            sessionContext.append(
                "A new conversation has ended between ${
                    conversation.players.joinToString(", ") {
                        EssentialsUtils.getNickname(Bukkit.getPlayer(it)?.name ?: "")
                    }
                }, ${conversation.npcNames.joinToString(", ")}.\n",
            )
            sessionContext.append("Conversation history:\n")
            sessionContext.append(
                conversation.history
                    .filter(
                        { it.role != "system" }, // Exclude system messages
                    ).joinToString("\n") { "${it.content}" },
            )
            sessionContext.append("\nLocation: ${conversationLocation}\n")
            sessionContext.append("Summarize this conversation and add it. ")
            plugin.sessionManager.feed(sessionContext.toString())

            // Summarize conversation for NPC memory if needed
            npcResponseService
                .summarizeConversation(
                    conversation,
                ).thenAccept {
                    // Complete remaining steps after summarization is done
                    completeEndConversation(conversation)
                    future.complete(null)
                }
        } else {
            // For non-significant conversations, just complete without summarizing
            completeEndConversation(conversation)
            future.complete(null)
        }

        return future
    }

    private fun completeEndConversation(conversation: Conversation) {
        // Notify participants
        conversation.players.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            player?.sendInfo("The conversation has ended.")
        }

        cleanupHolograms(conversation)

        // Remove from repository
        repository.removeConversation(conversation)
    }

    /**
     * Adds a player to an existing conversation, with event cancellation support
     *
     * @param player The player to add to the conversation
     * @param conversation The conversation to add the player to
     * @param greetingMessage Optional greeting message from the player
     * @return CompletableFuture<Boolean> that completes with true if player successfully joined,
     *         false if event was cancelled or failed
     */
    fun joinConversation(
        player: Player,
        conversation: Conversation,
        greetingMessage: String? = null,
    ): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val joinEvent = ConversationJoinEvent(conversation, PlayerParticipant(player))
        // Run event on the main thread
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                // Fire the ConversationJoinEvent first
                Bukkit.getPluginManager().callEvent(joinEvent)
            },
        )

        // Check if event was cancelled
        if (joinEvent.isCancelled) {
            plugin.logger.info(
                "Player ${player.name} was prevented from joining conversation ${conversation.id} due to cancelled event",
            )
            result.complete(false)
            return result
        }

        // Add player to conversation
        if (!conversation.addPlayer(player)) {
            result.complete(false)
            return result
        }

        // Handle optional greeting message
        if (greetingMessage != null) {
            addPlayerMessage(player, conversation, greetingMessage)
        }

        // Notify players
        conversation.players.forEach { uuid ->
            Bukkit.getPlayer(uuid)?.sendInfo("<yellow>${player.name}</yellow> joined the conversation.")
        }

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(player.name)} has joined the conversation.",
        )

        result.complete(true)

        return result
    }

    /**
     * Adds an NPC to an existing conversation, with event cancellation support
     *
     * @param npc The NPC to add to the conversation
     * @param conversation The conversation to add the NPC to
     * @param greetingMessage Optional greeting message from the NPC
     * @return CompletableFuture<Boolean> that completes with true if NPC successfully joined,
     *         false if event was cancelled or failed
     */
    fun joinConversation(
        npc: NPC,
        conversation: Conversation,
        greetingMessage: String? = null,
    ): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()

        // Run event on the main thread
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                // Fire the ConversationJoinEvent first
                val joinEvent = ConversationJoinEvent(conversation, NPCParticipant(npc))
                Bukkit.getPluginManager().callEvent(joinEvent)

                // Check if event was cancelled
                if (joinEvent.isCancelled) {
                    plugin.logger.info(
                        "NPC ${npc.name} was prevented from joining conversation ${conversation.id} due to cancelled event",
                    )
                    result.complete(false)
                    return@Runnable
                }

                // Add NPC to conversation
                if (!conversation.addNPC(npc)) {
                    result.complete(false)
                    return@Runnable
                }

                // Handle optional greeting message
                if (greetingMessage != null) {
                    conversation.addNPCMessage(npc, greetingMessage)
                }

                // Notify players
                conversation.players.forEach { uuid ->
                    Bukkit.getPlayer(uuid)?.sendInfo("<yellow>${npc.name}</yellow> joined the conversation.")
                }

                conversation.addSystemMessage(
                    "${npc.name} has joined the conversation.",
                )

                // Update NPC state
                hologramManager.showListeningHolo(npc, false)

                result.complete(true)
            },
        )

        return result
    }

    // Method to schedule proximity check for a conversation
    fun scheduleProximityCheck(conversation: Conversation) {
        val checkDelay = 10 // Check every 10 seconds
        val maxDistance = plugin.config.chatRadius

        val taskId =
            Bukkit
                .getScheduler()
                .runTaskTimer(
                    plugin,
                    Runnable {
                        // Skip if conversation is no longer active
                        if (!conversation.active) {
                            return@Runnable
                        }

                        // Check each player's proximity to NPCs and other players in the conversation
                        val playersToRemove = mutableListOf<Player>()

                        for (playerId in conversation.players) {
                            val player = Bukkit.getPlayer(playerId) ?: continue

                            // Check if player is still near any NPC or other player in the conversation
                            var isNearAnyParticipant = false

                            // Check proximity to NPCs
                            for (npc in conversation.npcs) {
                                val npcEntity = getRealEntityForNPC(npc) ?: continue

                                val npcLoc = npcEntity.location
                                val playerLoc = player.location

                                // Check if player and NPC are in the same world and within range
                                if (playerLoc.world == npcLoc.world &&
                                    playerLoc.distance(npcLoc) <= maxDistance
                                ) {
                                    isNearAnyParticipant = true
                                    break
                                }
                            }

                            // If not near any NPC, check proximity to other players in the conversation
                            if (!isNearAnyParticipant) {
                                for (otherPlayerId in conversation.players) {
                                    if (otherPlayerId == playerId) continue // Skip self

                                    val otherPlayer = Bukkit.getPlayer(otherPlayerId) ?: continue
                                    val otherPlayerLoc = otherPlayer.location
                                    val playerLoc = player.location

                                    // Check if players are in the same world and within range
                                    if (playerLoc.world == otherPlayerLoc.world &&
                                        playerLoc.distance(otherPlayerLoc) <= maxDistance
                                    ) {
                                        isNearAnyParticipant = true
                                        break
                                    }
                                }
                            }

                            // If player is not near any participant (NPC or other player), mark for removal
                            if (!isNearAnyParticipant) {
                                playersToRemove.add(player)
                            }
                        }

                        val lastRemovedPlayer: Player? =
                            if (playersToRemove.isNotEmpty()) {
                                playersToRemove[0]
                            } else {
                                null
                            }

                        // Handle players who moved away
                        for (player in playersToRemove) {
                            player.sendInfo("<gray>You've moved away from the conversation.")
                            removePlayer(player, conversation)
                        }

                        // End conversation if no players left
                        if (conversation.players.isEmpty()) {
                            // add the last removed player to the conversation
                            if (lastRemovedPlayer != null) {
                                conversation.addPlayer(lastRemovedPlayer)
                            }

                            endConversation(conversation)

                            // If there was a MythicMob involved, remove it
                            for (npc in conversation.npcs) {
                                if (npc is MythicMobConversationIntegration.MythicMobNPCAdapter) {
                                    plugin.mythicMobConversation.endConversation(npc)
                                }
                            }

                            // Cancel this task
                            val currentTaskId = scheduledTasks[conversation]
                            if (currentTaskId != null) {
                                Bukkit.getScheduler().cancelTask(currentTaskId)
                                scheduledTasks.remove(conversation)
                            }
                        }
                    },
                    checkDelay * 20L,
                    checkDelay * 20L,
                ).taskId

        // Store the task ID
        scheduledTasks[conversation] = taskId
    }

    // Helper method to calculate conversation significance
    private fun calculateConversationSignificance(conversation: Conversation): Int {
        // Basic heuristic: longer conversations are more significant
        val length = conversation.history.size

        // Count NPCs involved
        val npcCount = conversation.npcs.size

        // Count player messages (more player involvement = more significant)
        val playerMessages =
            conversation.history.count {
                it.role == "user"
            }

        return when {
            length > 10 && npcCount >= 2 -> 5 // Very significant
            length > 7 || (length > 5 && npcCount >= 2) -> 4 // Fairly significant
            length > 4 -> 3 // Moderately significant
            length > 2 -> 2 // Slightly significant
            else -> 1 // Minimal significance
        }
    }

    /**
     * Fallback method for calculating impact when AI analysis fails
     */
    private fun fallbackImpactCalculation(conversation: Conversation): Double {
        // Count messages with positive/negative sentiment
        var positiveCount = 0
        var negativeCount = 0

        // Simple sentiment analysis using keywords
        val positiveWords = listOf("happy", "thanks", "good", "great", "love", "appreciate", "friend", "help")
        val negativeWords = listOf("angry", "hate", "bad", "terrible", "awful", "dislike", "enemy")

        for (message in conversation.history) {
            val content = message.content.lowercase()

            when {
                positiveWords.any { content.contains(it) } -> positiveCount++
                negativeWords.any { content.contains(it) } -> negativeCount++
            }
        }

        // Calculate impact based on positive vs negative balance
        val totalSentiment = positiveCount - negativeCount
        val conversationLength = conversation.history.size.toDouble()

        // Scale impact based on conversation length and sentiment
        val impact = (totalSentiment / conversationLength).coerceIn(-0.8, 0.8)

        // Conversations with more messages have stronger impact
        val lengthMultiplier =
            when {
                conversationLength > 20 -> 1.2
                conversationLength > 10 -> 1.0
                conversationLength > 5 -> 0.8
                else -> 0.6
            }

        return (impact * lengthMultiplier).coerceIn(-1.0, 1.0)
    }

    /**
     * Adds a player message to the conversation and triggers NPC response with debouncing
     */
    fun addPlayerMessage(
        player: Player,
        conversation: Conversation,
        message: String,
    ) {
        // Add the player's message to the conversation
        conversation.addPlayerMessage(player, message)
        handleHolograms(conversation, player.name)

        // Skip response generation if chat is disabled
        if (!conversation.chatEnabled) {
            return
        }

        // Cancel any existing response timer for this conversation
        responseTimers[conversation.id]?.let { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
            responseTimers.remove(conversation.id)
        }

        // Schedule a new response after the configured delay
        val responseDelay = plugin.config.responseDelay.toLong()
        val taskId =
            Bukkit
                .getScheduler()
                .runTaskLater(
                    plugin,
                    Runnable {
                        // Generate NPC responses
                        generateResponses(conversation).thenAccept {
                            // Response timer completed, remove it from tracking
                            responseTimers.remove(conversation.id)
                        }
                    },
                    responseDelay * 20, // Convert seconds to ticks (20 ticks = 1 second)
                ).taskId

        // Store the new timer
        responseTimers[conversation.id] = taskId
    }

    // * Remove NPC from a conversation
    fun removeNPC(
        npc: NPC,
        conversation: Conversation,
    ) {
        // Remove the NPC from the conversation
        if (conversation.removeNPC(npc)) {
            // Notify players in the conversation
            for (playerId in conversation.players) {
                val player = Bukkit.getPlayer(playerId)
                player?.sendInfo("<gold>${npc.name}</gold> has left the conversation.")
            }

            conversation.addSystemMessage("${npc.name} has left the conversation. They are no longer participating.")

            // Summarise the conversation for left NPC. (Only if there is still npcs)
            if (conversation.npcs.isNotEmpty()) {
                npcResponseService.summarizeConversationForSingleNPC(
                    conversation.history,
                    npc.name,
                )
            } else {
                // If no NPCs left, end the conversation
                endConversation(conversation)
            }

            // Cleanup holograms
            cleanupHolograms(conversation)
        }
    }

    fun removePlayer(
        player: Player,
        conversation: Conversation,
    ) {
        // Remove the player from the conversation
        if (conversation.removePlayer(player)) {
            val playerName = EssentialsUtils.getNickname(player.name)

            // Notify other players in the conversation
            for (otherPlayerId in conversation.players) {
                val otherPlayer = Bukkit.getPlayer(otherPlayerId)
                otherPlayer?.sendInfo("<gold>$playerName</gold> has left the conversation.")
            }

            conversation.addSystemMessage("$playerName has left the conversation. They are no longer participating.")

            // Cleanup holograms
            cleanupHolograms(conversation)

            // If no players left, end the conversation
            if (conversation.players.isEmpty()) {
                endConversation(conversation)
            }
        }
    }

    fun handleHolograms(
        conversation: Conversation,
        speakerName: String? = null,
    ) {
        for (npc in conversation.npcs) {
            val entity = getRealEntityForNPC(npc)

            if (entity != null) {
                if (speakerName == null || speakerName == npc.name) {
                    hologramManager.showThinkingHolo(entity)
                } else {
                    hologramManager.showListeningHolo(entity, false)
                }
            }
        }
    }

    /**
     * Clean up holograms from NPCs or disguised players
     */
    fun cleanupHolograms(conversation: Conversation) {
        for (npc in conversation.npcs) {
            val entity = getRealEntityForNPC(npc)
            if (entity != null) {
                hologramManager.cleanupNPCHologram(entity)
            }
        }
    }

    fun checkAndGetLoreContexts(conversation: Conversation): List<LoreContext> {
        val allMessages: List<ConversationMessage> = conversation.history
        if (allMessages.isEmpty()) {
            return emptyList()
        }

        // Track lore names that we've already checked to avoid redundant processing
        val processedMessages: MutableSet<String> = HashSet()
        val addedLoreNames: MutableSet<String> = HashSet()
        val addedLoreContexts = HashSet<LoreContext>()
        // Iterate through all messages in the conversation history
        for (message in allMessages) {
            // Skip system messages and already processed content
            if ("system" == message.role || processedMessages.contains(message.content)) {
                continue
            }

            // Mark this message as processed
            processedMessages.add(message.content)

            var messageContent: String = message.content

            // Extract the actual message content if it includes speaker name (format: "Name: message")
            val colonIndex = messageContent.indexOf(":")
            if (colonIndex > 0) {
                messageContent = messageContent.substring(colonIndex + 1).trim { it <= ' ' }
            }

            // Check for relevant lore contexts
            val relevantContexts: List<LoreContext> =
                plugin.lorebookManager.findRelevantLoreContexts(messageContent, conversation)

            // Add any found contexts to the conversation if not already added
            for ((loreName, context1) in relevantContexts) {
                if (addedLoreNames.add(loreName)) {
                    plugin.logger.info(
                        "Added lore context from '" + loreName +
                            "' to conversation based on message: " + messageContent,
                    )
                }
            }
        }
        return addedLoreContexts.toList()
    }

    // Repository delegation methods
    fun getConversationById(id: Int): Conversation? = repository.getConversationById(id)

    fun getConversation(player: Player): Conversation? = repository.getConversationByPlayer(player)

    fun getAllActiveConversations(): List<Conversation> = repository.getAllActiveConversations()

    fun getConversation(npc: NPC): Conversation? = repository.getConversationByNPC(npc)

    fun getConversation(npcName: String): Conversation? = repository.getConversationByNPC(npcName)

    fun isInConversation(player: Player): Boolean = repository.getConversationByPlayer(player) != null

    fun isInConversation(npc: NPC): Boolean = repository.getConversationByNPC(npc) != null

    /**
     * Gets the entity representing an NPC, accounting for disguised players
     */
    fun getRealEntityForNPC(npc: NPC): Entity? {
        // Check if someone is disguised as this NPC
        val disguisedPlayer = plugin.disguiseManager.getDisguisedPlayer(npc)
        if (disguisedPlayer != null) {
            return disguisedPlayer
        }

        // Otherwise return the actual NPC entity
        return npc.entity
    }

    // NPC conversation coordination
    fun generateResponses(
        conversation: Conversation,
        forceSpeaker: String? = null,
    ): CompletableFuture<Unit> {
        // Determine the next speaker
        val speakerFuture =
            if (forceSpeaker != null) {
                CompletableFuture.completedFuture(forceSpeaker)
            } else {
                npcResponseService.determineNextSpeaker(conversation)
            }

        // Generate the response for the next speaker, handle holograms, and cleanup
        val result = CompletableFuture<Unit>()

        speakerFuture
            .thenAccept { nextSpeaker ->
                if (nextSpeaker != null) {
                    val npcEntity = conversation.getNPCByName(nextSpeaker) ?: return@thenAccept

                    // Set the NPC as the last speaker
                    conversation.lastSpeakingNPC = npcEntity

                    // Show holograms for the NPCs
                    handleHolograms(conversation, nextSpeaker)

                    // First generate behavioral directive to guide the response (if enabled)
                    val directiveFuture =
                        if (plugin.config.behavioralDirectivesEnabled) {
                            npcResponseService.generateBehavioralDirective(conversation, npcEntity)
                        } else {
                            CompletableFuture.completedFuture("") // Return empty directive
                        }

                    directiveFuture
                        .thenAccept { directive ->
                            // Add the directive as a system message to guide the response (only if not empty)
                            if (directive.isNotEmpty()) {
                                conversation.addSystemMessage(directive)
                            }

                            // Get only the messages from the conversation for context
                            val recentMessages =
                                conversation.history
                                    .map { it.content }

                            // Prepare response context with limited messages
                            var responseContext =
                                mutableListOf(
                                    "\n===APPEARANCES===\n" +
                                        conversation.npcs.joinToString("\n") { npc ->
                                            val npcContext = npcContextGenerator.getOrCreateContextForNPC(npc)
                                            "${npc.name}: ${npcContext?.appearance ?: "No appearance information available."}"
                                        } +
                                        // We treat players as NPCs for this purpose
                                        conversation.players?.joinToString("\n") { playerId ->
                                            val player = Bukkit.getPlayer(playerId)
                                            if (player == null) {
                                                // skip this player
                                                return@joinToString ""
                                            }
                                            val playerName = player.name
                                            val nickname = EssentialsUtils.getNickname(playerName)
                                            val playerContext =
                                                npcContextGenerator.getOrCreateContextForNPC(nickname)
                                            "$nickname: ${playerContext?.appearance ?: "No appearance information available."}"
                                        } +
                                        "\n=========================",
                                    "====CURRENT CONVERSATION====\n" +
                                        recentMessages.joinToString("\n") +
                                        "\n=========================\n" +
                                        "This is an active conversation and you are talking to multiple characters: ${
                                            conversation.players?.joinToString(
                                                ", ",
                                            ) {
                                                Bukkit.getPlayer(it)?.name?.let { name ->
                                                    EssentialsUtils.getNickname(
                                                        name,
                                                    )
                                                } ?: ""
                                            }
                                        }. " +
                                        // remove nextSpeaker from the list
                                        conversation.npcNames
                                            .filter { it != nextSpeaker }
                                            .joinToString("\n") +
                                        ". Respond in character as $nextSpeaker. Message starts now:",
                                )

                            // Add relationship context with clear section header
                            val relationships = plugin.relationshipManager.getAllRelationships(npcEntity.name)
                            if (relationships.isNotEmpty()) {
                                val relationshipContext =
                                    plugin.relationshipManager.buildRelationshipContext(
                                        npcEntity.name,
                                        relationships,
                                        conversation,
                                    )
                                if (relationshipContext.isNotEmpty()) {
                                    responseContext.addFirst("===RELATIONSHIPS===\n$relationshipContext")
                                }
                            }

                            val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(nextSpeaker)
                            val shouldStream = plugin.config.streamMessages
                            val response =
                                if (shouldStream) {
                                    npcResponseService.generateNPCResponseWithTypingEffect(
                                        npcEntity,
                                        npcContext,
                                        responseContext,
                                    )
                                } else {
                                    npcResponseService.generateNPCResponse(
                                        npcEntity,
                                        responseContext,
                                    )
                                }

                            response
                                .thenAccept { npcResponse ->
                                    // Add the NPC's response to the conversation history
                                    conversation.addNPCMessage(npcEntity, npcResponse)

                                    // Hologram cleanup
                                    cleanupHolograms(conversation)

                                    // Get the current player in conversation with this NPC, if any
                                    val targetPlayer =
                                        conversation.players?.firstOrNull()?.let {
                                            Bukkit.getPlayer(it)
                                        }

                                    // take last two user + assistant message
                                    val lastTwoMessages =
                                        conversation.history
                                            .filter { it.role != "system" && it.content != "..." }
                                            .takeLast(2)
                                            .map { it.content }

                                    // Analyze response for action intents asynchronously
                                    if (targetPlayer != null) {
                                        plugin.npcActionIntentRecognizer.recognizeQuestGivingIntent(
                                            npcEntity,
                                            lastTwoMessages,
                                            targetPlayer,
                                        )
                                        plugin.npcActionIntentRecognizer.recognizeActionIntents(
                                            npcEntity,
                                            lastTwoMessages,
                                            targetPlayer,
                                        )
                                    }

                                    result.complete(Unit)
                                }.exceptionally { e ->
                                    plugin.logger.warning(
                                        "Error generating NPC response for ${npcEntity.name}: ${e.message}",
                                    )

                                    // Still cleanup holograms
                                    cleanupHolograms(conversation)

                                    // Complete successfully without adding any message
                                    result.complete(Unit)
                                    null
                                }
                        }.exceptionally { e ->
                            plugin.logger.warning("Error generating behavioral directive: ${e.message}")

                            // Continue without directive if it fails - use the same code but without the directive
                            // Get only the last 4 messages from the conversation for context
                            val recentMessages =
                                conversation.history
                                    .filterNot { it.role == "system" }
                                    .takeLast(4)
                                    .map { it.content }
                            val npcContext = npcContextGenerator.getOrCreateContextForNPC(nextSpeaker)
                            val responseContext =
                                listOf(
                                    "====CURRENT CONVERSATION====\n" +
                                        recentMessages.joinToString("\n") +
                                        "\n=========================\n" +
                                        "Respond in character as $nextSpeaker. This is an active conversation and" +
                                        " you are talking to ${
                                            conversation.players?.joinToString(
                                                ", ",
                                            ) {
                                                Bukkit.getPlayer(it)?.name?.let { name ->
                                                    EssentialsUtils.getNickname(
                                                        name,
                                                    )
                                                } ?: ""
                                            }
                                        }. " +
                                        conversation.npcNames.joinToString("\n") +
                                        "\n=========================",
                                )

                            // Continue with normal response generation
                            val shouldStream = plugin.config.streamMessages
                            val response =
                                if (shouldStream) {
                                    npcResponseService.generateNPCResponseWithTypingEffect(
                                        npcEntity,
                                        npcContext = npcContext,
                                        responseContext,
                                    )
                                } else {
                                    npcResponseService.generateNPCResponse(npcEntity, responseContext)
                                }

                            response
                                .thenAccept { npcResponse ->
                                    // Rest of processing code
                                    conversation.addNPCMessage(npcEntity, npcResponse)
                                    cleanupHolograms(conversation)
                                    // Process action intents...
                                    result.complete(Unit)
                                }.exceptionally { responseError ->
                                    result.completeExceptionally(responseError)
                                    null
                                }

                            null
                        }
                } else {
                    result.complete(Unit)
                }
            }.exceptionally { e ->
                plugin.logger.warning("Error determining next speaker: ${e.message}")
                result.completeExceptionally(e)
                null
            }

        return result
    }

    fun lockConversation(conversation: Conversation) {
        repository.lockedConversations.add(conversation.id)
    }

    fun unlockConversation(conversation: Conversation) {
        repository.lockedConversations.remove(conversation.id)
    }

    fun isConversationLocked(conversation: Conversation): Boolean =
        repository.lockedConversations.contains(conversation.id)

    /**
     * Cancels all response timers
     */
    fun cancelResponseTimers() {
        for (taskId in responseTimers.values) {
            Bukkit.getScheduler().cancelTask(taskId)
        }
        responseTimers.clear()
    }

    // Update the existing cancelScheduledTasks method to also cancel response timers
    fun cancelScheduledTasks() {
        for (taskId in scheduledTasks.values) {
            Bukkit.getScheduler().cancelTask(taskId)
        }
        scheduledTasks.clear()

        // Also cancel any pending response timers
        cancelResponseTimers()
    }

    fun startRadiantConversation(npcs: ArrayList<NPC>): CompletableFuture<Conversation> {
        // Generate normal conversation but with timeout
        val conversation =
            Conversation(
                _players = mutableListOf(),
                initialNPCs = npcs,
            )
        // Add to repository
        repository.addConversation(conversation)

        // Task to end conversation after a timeout
        val task =
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    endConversation(conversation)
                },
                20L * 10,
            ) // 10 seconds timeout

        return CompletableFuture.completedFuture(conversation)
    }

    fun cleanupNPCHologram(npc: NPC) {
        TODO("Not yet implemented")
    }

    fun addNPCToConversationWalk(
        npc: NPC?,
        conversation: Conversation,
        message: String,
    ): Boolean {
        if (npc == null || !npc.isSpawned) {
            return false
        }

        // Check if the NPC is already in this conversation
        if (conversation.npcs.contains(npc)) {
            return false
        }

        // Pick a random participant from the conversation to walk to
        val target =
            when {
                // If there are players in the conversation, walk to one of them
                conversation.players.isNotEmpty() -> {
                    val randomPlayerUUID = conversation.players.random()
                    Bukkit.getPlayer(randomPlayerUUID) ?: return false
                }
                // If no players, walk to an NPC already in the conversation
                conversation.npcs.isNotEmpty() -> {
                    val randomNPC = conversation.npcs.random()
                    randomNPC.entity ?: return false
                }
                // No valid targets to walk to
                else -> return false
            }

        // Use NPCManager to make the NPC walk to the target
        val taskId =
            plugin.npcManager.walkToLocation(
                npc,
                target,
                2.0, // Distance margin
                1.0f, // Speed modifier
                30, // Timeout in seconds
                // On arrival
                {
                    // Add NPC to conversation
                    conversation.addNPC(npc)

                    // Send greeting message if provided
                    if (message.isNotEmpty()) {
                        conversation.addNPCMessage(npc, message)
                    }

                    // Notify players in the conversation
                    conversation.players.forEach { playerId ->
                        val player = Bukkit.getPlayer(playerId)
                        player?.sendInfo("<gold>${npc.name}</gold> has joined the conversation.")
                    }
                },
                // On failure
                {
                    plugin.logger.warning("NPC ${npc.name} failed to walk to the conversation.")
                },
            )

        return taskId != -1
    }

    fun stopAllConversations() {
        TODO("Not yet implemented")
    }

    fun isPlayerInConversationWith(
        player: Player,
        npc: NPC,
    ): Boolean {
        val conversation = repository.getConversationByPlayer(player)
        return conversation?.npcs?.contains(npc) ?: false
    }

    fun isNPCInConversationWith(
        npc: NPC,
        player: Player,
    ): Boolean {
        val conversation = repository.getConversationByNPC(npc)
        return conversation?.players?.contains(player.uniqueId) ?: false
    }

    fun isNPCInConversationWith(
        npc: NPC,
        npc2: NPC,
    ): Boolean {
        val conversation = repository.getConversationByNPC(npc)
        return conversation?.npcs?.contains(npc2) ?: false
    }

    companion object {
        private var instance: ConversationManager? = null

        @JvmStatic
        fun getInstance(
            plugin: Story,
            npcContextGenerator: NPCContextGenerator,
            npcResponseService: NPCResponseService,
            worldInformationManager: WorldInformationManager,
        ): ConversationManager {
            if (instance == null) {
                instance = ConversationManager(plugin, npcContextGenerator, npcResponseService, worldInformationManager)
            }
            return instance!!
        }

        @JvmStatic
        fun getInstance(plugin: Story): ConversationManager =
            instance ?: throw IllegalStateException("ConversationManager has not been initialized")

        // Only for tests
        @JvmStatic
        fun reset() {
            instance = null
        }
    }
}
