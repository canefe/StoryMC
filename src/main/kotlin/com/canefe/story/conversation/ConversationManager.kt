import com.canefe.story.Story
import com.canefe.story.conversation.*
import com.canefe.story.information.ConversationInformationSource
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.lore.LoreBookManager.LoreContext
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendInfo
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.random.Random


class ConversationManager private constructor(
    private val plugin: Story,
    private val npcContextGenerator: NPCContextGenerator,
    private val npcResponseService: NPCResponseService,
    private val worldInformationManager: WorldInformationManager // Add this dependency
) {
    private val repository = ConversationRepository()
    private val hologramManager = ConversationHologramManager(plugin)

    // Map to store scheduled tasks by conversation
    private val scheduledTasks = mutableMapOf<Conversation, Int>()

    // Getter for scheduled tasks
    fun getScheduledTasks(): MutableMap<Conversation, Int> = scheduledTasks

    val activeConversations: List<Conversation>
        get() = repository.getAllActiveConversations()

    // Core conversation management methods
    fun startConversation(player: Player, npcs: List<NPC>): Conversation {
        // Check if player is already in a conversation and end it
        val existingConversation = repository.getConversationByPlayer(player)
        existingConversation?.let {
            endConversation(it)
        }

        // Create a new conversation
        val participants = mutableListOf(player.uniqueId)
        val conversation = Conversation(
            _players = participants,
            initialNPCs = npcs
        )

        // If chat is not enabled, allow manual conversation
        if (!plugin.config.chatEnabled)
            conversation.chatEnabled = false

        // Add to repository
        repository.addConversation(conversation)

        // Schedule proximity check for this conversation
        scheduleProximityCheck(conversation)

        // Notify player
        val npcNames = npcs.joinToString(", ") { it.name }
        player.sendInfo("You are now in a conversation with: <yellow>$npcNames</yellow>")

        return conversation
    }

    fun endConversation(conversation: Conversation) {
        // Clean up scheduled proximity check task if it exists
        val taskId = scheduledTasks[conversation]
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId)
            scheduledTasks.remove(conversation)
        }

        // Only process conversation data if significant
        if (conversation.history.size > 2) {
            val playerName = conversation.players.firstOrNull()?.let {
                Bukkit.getPlayer(it)?.name
            }

            // Get the conversation location (where the conversation is happening)
            val conversationLocation = conversation.npcs.firstOrNull()?.let { npc ->
                // Get the actual physical location where the NPC currently is
                npcContextGenerator.getOrCreateContextForNPC(npc.name)?.location?.name
            } ?: "Village" // Default location

            // Create conversation information source
            val conversationSource = ConversationInformationSource(
                messages = conversation.history,
                npcNames = conversation.npcs.map { it.name },
                locationName = conversationLocation,
                significance = calculateConversationSignificance(conversation)
            )

            // Process information using the new system
            worldInformationManager.processInformation(conversationSource)

            // Summarize conversation for NPC memory if needed
            npcResponseService.summarizeConversation(
                conversation.history,
                conversation.npcs.map { it.name },
                playerName
            )
        }

        // Notify participants
        conversation.players.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            player?.sendMessage("§7The conversation has ended.")
        }

        // Remove from repository
        repository.removeConversation(conversation)
    }

    // Method to schedule proximity check for a conversation
    private fun scheduleProximityCheck(conversation: Conversation) {
        val checkDelay = 10 // Check every 10 seconds
        val maxDistance = plugin.config.chatRadius

        val taskId = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Skip if conversation is no longer active
            if (!conversation.isActive) {
                return@Runnable
            }

            // Check each player's proximity to NPCs in the conversation
            val playersToRemove = mutableListOf<Player>()

            for (playerId in conversation.players) {
                val player = Bukkit.getPlayer(playerId) ?: continue

                // Check if player is still near any NPC in the conversation
                var isNearAnyNPC = false

                for (npc in conversation.npcs) {
                    if (!npc.isSpawned) continue

                    val npcLoc = npc.entity.location
                    val playerLoc = player.location

                    // Check if player and NPC are in the same world and within range
                    if (playerLoc.world == npcLoc.world &&
                        playerLoc.distance(npcLoc) <= maxDistance) {
                        isNearAnyNPC = true
                        break
                    }
                }

                // If player is not near any NPC, mark for removal
                if (!isNearAnyNPC) {
                    playersToRemove.add(player)
                }
            }

            // Handle players who moved away
            for (player in playersToRemove) {
                player.sendInfo("<gray>You've moved away from the conversation.")
                conversation.removePlayer(player)
            }

            // End conversation if no players left
            if (conversation.players.isEmpty()) {
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
        }, checkDelay * 20L, checkDelay * 20L).taskId

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
        val playerMessages = conversation.history.count {
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
        val lengthMultiplier = when {
            conversationLength > 20 -> 1.2
            conversationLength > 10 -> 1.0
            conversationLength > 5 -> 0.8
            else -> 0.6
        }

        return (impact * lengthMultiplier).coerceIn(-1.0, 1.0)
    }

    //* Triggers NPC Response
    fun addPlayerMessage(player: Player, conversation: Conversation, message: String) {
        // first add the message
        conversation.addPlayerMessage(player, message)
        handleHolograms(conversation, player.name)
        // then trigger the NPC response if the conversation is active
        if (!conversation.isActive) {
            return
        }

        // Generate NPC responses
        generateResponses(conversation).thenAccept {
            // Handle any post-response actions if needed
        }

    }
    //* Remove NPC from a conversation
    fun removeNPC(npc: NPC, conversation: Conversation) {
        // Remove the NPC from the conversation
        if (conversation.removeNPC(npc)) {
            // Notify players in the conversation
            for (playerId in conversation.players) {
                val player = Bukkit.getPlayer(playerId)
                player?.sendInfo("<gold>${npc.name}</gold> has left the conversation.")
            }

            // Summarise the conversation for left NPC. (Only if there is still npcs)
            if (conversation.npcs.isNotEmpty()) {
                npcResponseService.summarizeConversationForSingleNPC(
                    conversation.history,
                    npc.name
                )
            } else {
                // If no NPCs left, end the conversation
                endConversation(conversation)
            }

            // Cleanup holograms
            cleanupHolograms(conversation)
        }
    }

    fun handleHolograms(conversation: Conversation, speakerName: String? = null) {
        for (npc in conversation.npcs) {
            if (npc.isSpawned && npc.entity != null) {
                if (speakerName == null || speakerName == npc.name) {
                    hologramManager.showThinkingHolo(npc)
                } else {
                    hologramManager.showListeningHolo(npc, false)
                }
            }
        }
    }

    fun cleanupHolograms(conversation: Conversation) {
        for (npc in conversation.npcs) {
            if (npc.isSpawned && npc.entity != null) {
                hologramManager.cleanupNPCHologram(npc)
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
                                "' to conversation based on message: " + messageContent
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
    fun isInConversation(player: Player): Boolean {
        return repository.getConversationByPlayer(player) != null
    }
    fun isInConversation(npc: NPC): Boolean {
        return repository.getConversationByNPC(npc) != null
    }


    // NPC conversation joining methods
    fun handleNPCJoiningConversation(
        npc: NPC,
        conversation: Conversation,
        greetingMessage: String?,
        targetPlayer: Player?,
        npcContext: NPCContext?
    ) {
        // Implementation
    }

    fun handleNPCJoiningConversationDirectly(
        npc: NPC,
        conversation: Conversation,
        greetingMessage: String?,
        npcContext: NPCContext?
    ) {
        // Implementation
    }

    // NPC conversation coordination
    fun generateResponses(conversation: Conversation, forceSpeaker: String? = null): CompletableFuture<Unit> {

        // Determine the next speaker
        val speakerFuture = if (forceSpeaker != null) {
            CompletableFuture.completedFuture(forceSpeaker)
        } else {
            npcResponseService.determineNextSpeaker(conversation)
        }

        // Generate the response for the next speaker, handle holograms, and cleanup
        speakerFuture.thenAccept { nextSpeaker ->
            if (nextSpeaker != null) {
                val npcEntity = conversation.getNPCByName(nextSpeaker) ?: return@thenAccept

                // Show holograms for the NPCs
                handleHolograms(conversation, nextSpeaker)

                var responseContext = conversation.history
                    .map { it.content }
                    .toList()

                // add one more string to the context
                responseContext = responseContext + "You are ${nextSpeaker}. You are in a conversation. Generate a natural response."

                val response = npcResponseService.generateNPCResponse(npcEntity, responseContext)
                response.thenAccept { npcResponse ->
                    conversation.addNPCMessage(npcEntity, npcResponse)

                    // Hologram cleanup
                    cleanupHolograms(conversation)
                }
            }
        }

        return CompletableFuture.completedFuture(Unit)
    }

    fun cancelScheduledTasks() {
        for (taskId in scheduledTasks.values) {
            Bukkit.getScheduler().cancelTask(taskId)
        }
        scheduledTasks.clear()
    }

    fun startRadiantConversation(npcs: ArrayList<NPC>): CompletableFuture<Conversation> {
        // Generate normal conversation but with timeout
        val conversation = Conversation(
            _players = mutableListOf(),
            initialNPCs = npcs
        )
        // Add to repository
        repository.addConversation(conversation)

        // Task to end conversation after a timeout
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            endConversation(conversation)
        }, 20L * 10) // 10 seconds timeout

        return CompletableFuture.completedFuture(conversation)
    }

    fun cleanupNPCHologram(npc: NPC) {
        TODO("Not yet implemented")
    }

    fun addNPCToConversationWalk(npc: NPC?, conversation: Conversation, message: String): Boolean {
        TODO("Not yet implemented")
    }

    fun stopAllConversations() {
        TODO("Not yet implemented")
    }

    companion object {
        private var instance: ConversationManager? = null

        @JvmStatic
        fun getInstance(
            plugin: Story,
            npcContextGenerator: NPCContextGenerator,
            npcResponseService: NPCResponseService,
            worldInformationManager: WorldInformationManager
        ): ConversationManager {
            if (instance == null) {
                instance = ConversationManager(
                    plugin,
                    npcContextGenerator,
                    npcResponseService,
                    worldInformationManager
                )
            }
            return instance!!
        }

        // Overloaded method for getting existing instance
        @JvmStatic
        fun getInstance(plugin: Story): ConversationManager {
            return instance ?: throw IllegalStateException("ConversationManager has not been initialized")
        }
    }
}