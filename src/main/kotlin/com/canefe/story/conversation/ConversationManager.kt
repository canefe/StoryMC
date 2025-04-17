import com.canefe.story.Story
import com.canefe.story.conversation.*
import com.canefe.story.information.ConversationInformationSource
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.lore.LoreBookManager.LoreContext
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.util.Msg.sendInfo
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.checkerframework.checker.units.qual.C
import java.util.concurrent.CompletableFuture


class ConversationManager private constructor(
    private val plugin: Story,
    private val npcContextGenerator: NPCContextGenerator,
    private val npcResponseService: NPCResponseService,
    private val worldInformationManager: WorldInformationManager // Add this dependency
) {
    private val repository = ConversationRepository()
    private val hologramManager = ConversationHologramManager(plugin)

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

        // Add to repository
        repository.addConversation(conversation)

        // Notify player
        val npcNames = npcs.joinToString(", ") { it.name }
        player.sendInfo("You are now in a conversation with: <yellow>$npcNames</yellow>")

        return conversation
    }

    fun endConversation(conversation: Conversation) {

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

    fun addPlayerToConversation(player: Player, conversation: Conversation) {
        // Implementation
    }

    //* Triggers NPC Response
    fun addPlayerMessage(player: Player, conversation: Conversation, message: String) {
        // first add the message
        conversation.addPlayerMessage(player, message)
        // then trigger the NPC response if the conversation is active
        if (!conversation.isActive) {
            return
        }
        val npc = npcResponseService.determineNextSpeaker(conversation, player)
        npc.thenAccept { nextSpeaker ->
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

    // Player message handling
    fun handlePlayerMessage(player: Player, message: String) {
        // Implementation
    }

    // NPC conversation coordination
    fun generateGroupNPCResponses(conversation: Conversation, player: Player?, speakerName: String?): CompletableFuture<List<String>> {
        // Implementation
        return CompletableFuture.completedFuture(emptyList())
    }

    fun cancelScheduledTasks() {
        // Implementation
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