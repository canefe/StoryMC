package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.api.event.ConversationJoinEvent
import com.canefe.story.api.event.ConversationStartEvent
import com.canefe.story.api.event.NPCParticipant
import com.canefe.story.api.event.PlayerParticipant
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.ArrayList
import java.util.concurrent.CompletableFuture

class NPCInteractionListener(
    private val plugin: Story,
) : Listener {
    // Map to store debounce timers and accumulated messages for each player
    private val delayedMessageTimers = mutableMapOf<Player, Int>()
    private val accumulatedMessages = mutableMapOf<Player, MutableList<String>>()

    /** Handles player chat events and processes NPC interactions */
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        val player = event.player
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        event.isCancelled = true

        // Schedule processing on the main thread to safely use getNearbyEntities
        Bukkit
            .getScheduler()
            .runTask(
                plugin,
                Runnable { processPlayerMessage(player, message) },
            )
    }

    /** Main processing logic for player messages */
    private fun processPlayerMessage(
        player: Player,
        message: String,
    ) {
        // Handle disguised players
        if (handleDisguisedPlayer(player, message)) return

        // Check if player is disabled
        if (plugin.playerManager.isPlayerDisabled(player)) return

        // Check if delayed message processing is enabled
        if (plugin.config.delayedPlayerMessageProcessing) {
            processDelayedPlayerMessage(player, message)
            return
        }

        // Broadcast the message
        plugin.npcMessageService.broadcastPlayerMessage(message, player)

        // Determine chat settings
        val isWhispering = message.matches(Regex(".*\\*whisper(s|ing)?\\*.*"))
        val chatRadius = if (isWhispering) 2.0 else plugin.config.chatRadius

        // Gather nearby entities
        val nearbyEntities = gatherNearbyEntities(player, chatRadius)

        // Handle conversation logic
        val currentConversation = plugin.conversationManager.getConversation(player)
        if (currentConversation != null) {
            handleExistingConversation(
                player,
                message,
                currentConversation,
                nearbyEntities,
                isWhispering,
            )
        } else {
            handleNoConversation(player, message, nearbyEntities)
        }
    }

    /**
     * Processes player messages with delayed LLM fleshing out (like /g command) Includes debounce
     * mechanism and OOC feedback
     */
    private fun processDelayedPlayerMessage(
        player: Player,
        message: String,
    ) {
        // Send OOC feedback message immediately (only to the player)
        sendOOCFeedback(player, message)

        // Add message to accumulated messages
        val playerMessages = accumulatedMessages.getOrPut(player) { mutableListOf() }
        playerMessages.add(message)

        // Cancel any existing delayed message timer for this player
        delayedMessageTimers[player]?.let { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
            delayedMessageTimers.remove(player)
        }

        // Schedule delayed processing after the configured delay (same as response delay)
        val responseDelay = plugin.config.responseDelay.toLong()
        val taskId =
            Bukkit
                .getScheduler()
                .runTaskLater(
                    plugin,
                    Runnable {
                        // Remove from tracking
                        delayedMessageTimers.remove(player)

                        // Get accumulated messages and clear them
                        val messages =
                            accumulatedMessages[player]?.toList() ?: listOf(message)
                        accumulatedMessages.remove(player)

                        // Combine all messages into one for LLM processing
                        val combinedMessage = messages.joinToString(" ")

                        // Process using player-specific LLM generation
                        generatePlayerCharacterResponse(player, combinedMessage)
                    },
                    responseDelay * 20, // Convert seconds to ticks (20 ticks = 1 second)
                ).taskId

        // Store the new timer
        delayedMessageTimers[player] = taskId
    }

    /** Sends OOC feedback message to show the player their message was received */
    private fun sendOOCFeedback(
        player: Player,
        message: String,
    ) {
        val playerName = EssentialsUtils.getNickname(player.name)
        player.sendInfo("$playerName: [$message]")
    }

    /**
     * Generates a fleshed-out response for the player character Based on generateNPCResponse but
     * adapted for players without creating NPC objects
     */
    private fun generatePlayerCharacterResponse(
        player: Player,
        message: String,
    ) {
        val playerCharacterName = EssentialsUtils.getNickname(player.name)

        // Get player context
        val playerContext =
            plugin.npcContextGenerator.getOrCreateContextForNPC(playerCharacterName)
                ?: run {
                    player.sendError(
                        "Could not find character data for $playerCharacterName.",
                    )
                    return
                }

        // Build conversation context similar to /g command
        val conversation = getOrCreateConversationForPlayer(player, playerCharacterName)
        val recentMessages = conversation.history.map { it.content }

        // Prepare response context (same as /g command)
        var responseContext =
            listOf(
                "====CURRENT CONVERSATION====\n" +
                    recentMessages.joinToString("\n") +
                    "\n=========================\n" +
                    "This is an active conversation and you are talking to multiple characters: ${conversation.players.joinToString(
                        ", ",
                    ) {
                        Bukkit.getPlayer(it)?.name?.let { name -> EssentialsUtils.getNickname(name) } ?: ""
                    }}. " +
                    conversation.npcNames.joinToString("\n") +
                    "\n===APPEARANCES===\n" +
                    conversation.npcs.joinToString("\n") { npc ->
                        val npcContext =
                            plugin.npcContextGenerator.getOrCreateContextForNPC(
                                npc.name,
                            )
                        "${npc.name}: ${npcContext?.appearance ?: "No appearance information available."}"
                    } +
                    // We treat players as NPCs for this purpose
                    conversation.players.joinToString("\n") { playerId ->
                        val p = Bukkit.getPlayer(playerId)
                        if (p == null) return@joinToString ""
                        val pName = p.name
                        val nickname = EssentialsUtils.getNickname(pName)
                        val pContext =
                            plugin.npcContextGenerator.getOrCreateContextForNPC(
                                nickname,
                            )
                        "$nickname: ${pContext?.appearance ?: "No appearance information available."}"
                    } +
                    "\n=========================",
            )

        // Add relationship context
        val relationships = plugin.relationshipManager.getAllRelationships(playerCharacterName)
        if (relationships.isNotEmpty()) {
            val relationshipContext =
                plugin.relationshipManager.buildRelationshipContext(
                    playerCharacterName,
                    relationships,
                    conversation,
                )
            if (relationshipContext.isNotEmpty()) {
                responseContext = responseContext + "===RELATIONSHIPS===\n$relationshipContext"
            }
        }

        // Use PromptService to get the talk as NPC prompt (treating player as NPC)
        val talkAsNpcPrompt = plugin.promptService.getTalkAsNpcPrompt(playerCharacterName, message)
        responseContext = responseContext + talkAsNpcPrompt

        // Generate AI response using player-specific method
        generatePlayerAIResponse(player, playerCharacterName, playerContext, responseContext)
            .thenApply { response ->
                // Add player message to conversation
                conversation.addPlayerMessage(player, response)

                // Broadcast the fleshed-out response as if it's from the player
                val shouldStream = plugin.config.streamMessages

                if (shouldStream) {
                    // Handle streaming with typing effect
                    handleStreamingPlayerResponse(player, response)
                } else {
                    // Broadcast immediately
                    plugin.npcMessageService.broadcastPlayerMessage(response, player)
                }
                // Determine chat settings
                val isWhispering = message.matches(Regex(".*\\*whisper(s|ing)?\\*.*"))
                val chatRadius = if (isWhispering) 2.0 else plugin.config.chatRadius

                // use Main thread
                Bukkit
                    .getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                            // Gather nearby entities
                            val nearbyEntities =
                                gatherNearbyEntities(player, chatRadius)

                            // Handle conversation logic
                            val currentConversation =
                                plugin.conversationManager.getConversation(player)
                            if (currentConversation != null) {
                                handleExistingConversation(
                                    player,
                                    message,
                                    currentConversation,
                                    nearbyEntities,
                                    isWhispering,
                                )
                            } else {
                                handleNoConversation(player, message, nearbyEntities)
                            }
                        },
                    )
            }
    }

    /** Get or create conversation for the player (similar to /g command logic) */
    private fun getOrCreateConversationForPlayer(
        player: Player,
        playerCharacterName: String,
    ): com.canefe.story.conversation.Conversation {
        val chatRadius = plugin.config.chatRadius

        return plugin.conversationManager.getConversation(playerCharacterName)
            ?: run {
                // Create new conversation with nearby NPCs and players
                var nearbyNPCs = plugin.getNearbyNPCs(player, chatRadius)
                var players = plugin.getNearbyPlayers(player, chatRadius)

                // Remove players that have their chat disabled
                players = players.filterNot { plugin.playerManager.isPlayerDisabled(it) }

                // Check if any nearby NPCs are already in a conversation
                val existingConversation =
                    nearbyNPCs
                        .mapNotNull {
                            plugin.conversationManager.getConversation(it.name)
                        }.firstOrNull()

                // Check if any nearby players are already in a conversation
                val playerConversation =
                    players
                        .flatMap { p ->
                            plugin.conversationManager
                                .getAllActiveConversations()
                                .filter { conv ->
                                    conv.players.contains(p.uniqueId)
                                }
                        }.firstOrNull()

                // Use existing conversation if available
                if (existingConversation != null) {
                    // Add any players not already in the conversation
                    players.forEach { p ->
                        if (!existingConversation.players.contains(p.uniqueId)) {
                            existingConversation.addPlayer(p)
                        }
                    }
                    plugin.conversationManager.handleHolograms(
                        existingConversation,
                        playerCharacterName,
                    )
                    return@run existingConversation
                } else if (playerConversation != null) {
                    plugin.conversationManager.handleHolograms(
                        playerConversation,
                        playerCharacterName,
                    )
                    return@run playerConversation
                }

                // Create new conversation
                if (!(players.isNotEmpty() || nearbyNPCs.isNotEmpty())) {
                    // No one nearby, create conversation with just the player
                    val newConversationFuture =
                        plugin.conversationManager.startConversation(emptyList())
                    newConversationFuture.thenAccept { newConv -> newConv.addPlayer(player) }
                    return@run newConversationFuture.join()
                }

                val newConversationFuture =
                    plugin.conversationManager.startConversation(nearbyNPCs)
                newConversationFuture.thenAccept { newConv ->
                    for (p in players) {
                        newConv.addPlayer(p)
                    }
                }
                newConversationFuture.join()
            }
    }

    /** Generate AI response for player character (adapted from generateNPCResponse) */
    private fun generatePlayerAIResponse(
        player: Player,
        characterName: String,
        playerContext: com.canefe.story.npc.data.NPCContext,
        responseContext: List<String>,
    ): java.util.concurrent.CompletableFuture<String> {
        val prompts: MutableList<ConversationMessage> = ArrayList()

        // Add basic roleplay instruction
        prompts.add(
            ConversationMessage(
                "system",
                "You are roleplaying as $characterName in a fantasy medieval world.",
            ),
        )

        // Add location context
        val location = playerContext.location
        if (location != null) {
            prompts.add(
                ConversationMessage(
                    "system",
                    "===HOME(CONTEXT) LOCATION===\n" +
                        location.getContextForPrompt(plugin.locationManager),
                ),
            )
        }

        // Check current location
        val entityPos = player.location
        val actualLocation = plugin.locationManager.getLocationByPosition(entityPos, 150.0)

        if (actualLocation != null && location != null && location.name != actualLocation.name) {
            val locationInfo =
                "===CURRENT LOCATION===\n" +
                    "You are currently physically at ${actualLocation.name}.\n" +
                    actualLocation.getContextForPrompt(plugin.locationManager)
            prompts.add(ConversationMessage("system", locationInfo))
        }

        // Add lorebook context
        val lorebookContexts = mutableListOf<String>()
        plugin.conversationManager.getConversation(player)?.let { conversation ->
            lorebookContexts.addAll(
                plugin.conversationManager.checkAndGetLoreContexts(conversation).map { lore ->
                    "${lore.loreName} - ${lore.context}"
                },
            )
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

        // Add character information
        prompts.add(
            ConversationMessage(
                "system",
                "===CHARACTER INFORMATION===\n" + playerContext.context,
            ),
        )

        // Add appearance information if available
        if (playerContext.appearance.isNotEmpty()) {
            prompts.add(
                ConversationMessage(
                    "system",
                    "===PHYSICAL APPEARANCE===\n" + playerContext.appearance,
                ),
            )
        }

        // Add memories
        playerContext.getMemoriesForPrompt(plugin.timeService).let { memories ->
            if (memories.isNotEmpty()) {
                prompts.add(ConversationMessage("system", "===MEMORY===\n" + memories))
            }
        }

        // Add general instructions
        prompts.add(ConversationMessage("system", "===INSTRUCTIONS===\n"))
        prompts.add(
            ConversationMessage(
                "system",
                plugin.npcContextGenerator
                    .getGeneralContexts()
                    .joinToString(separator = "\n"),
            ),
        )

        // Include current time
        prompts.add(
            ConversationMessage(
                "system",
                "===CURRENT TIME===\n" +
                    "The current time is ${plugin.timeService.getHours()}:${plugin.timeService.getMinutes()} at date ${plugin.timeService.getFormattedDate()} " +
                    "in the ${plugin.timeService.getSeason()} season.",
            ),
        )

        // Add response context
        if (responseContext.isNotEmpty()) {
            prompts.add(
                ConversationMessage("system", responseContext.joinToString(separator = "\n")),
            )
        }

        return plugin.getAIResponse(prompts, lowCost = false).thenApply { response ->
            response?.trim() ?: ""
        }
    }

    /** Handle streaming player response with typing effect */
    private fun handleStreamingPlayerResponse(
        player: Player,
        response: String,
    ) {
        // We can't use typing session manager for players like we do for NPCs
        // So we'll just broadcast the message directly
        plugin.npcMessageService.broadcastPlayerMessage(response, player)
    }

    /** Handles messages from disguised players */
    private fun handleDisguisedPlayer(
        player: Player,
        message: String,
    ): Boolean {
        if (plugin.disguiseManager.getImitatedNPC(player) != null) {
            player.performCommand("h $message")
            return true
        }
        return false
    }

    /** Data class to hold nearby entities */
    private data class NearbyEntities(
        val npcs: List<NPC>,
        val players: List<Player>,
        val allInteractableNPCs: List<NPC>,
    )

    /** Gathers all nearby entities for conversation processing */
    private fun gatherNearbyEntities(
        player: Player,
        chatRadius: Double,
    ): NearbyEntities {
        val nearbyNPCs = plugin.getNearbyNPCs(player, chatRadius)

        val disguisedPlayers =
            player
                .getNearbyEntities(chatRadius, chatRadius, chatRadius)
                .filter { plugin.disguiseManager.isDisguisedAsNPC(it) }
                .mapNotNull {
                    (it as? Player)?.let { p -> plugin.disguiseManager.getImitatedNPC(p) }
                }

        val nearbyPlayers =
            player
                .getNearbyEntities(chatRadius, chatRadius, chatRadius)
                .filterIsInstance<Player>()
                .filter {
                    it != player &&
                        !plugin.playerManager.isPlayerDisabled(it) &&
                        !CitizensAPI.getNPCRegistry().isNPC(it)
                }

        val allInteractableNPCs = (nearbyNPCs + disguisedPlayers).distinct()

        return NearbyEntities(nearbyNPCs, nearbyPlayers, allInteractableNPCs)
    }

    /** Handles message when player is already in a conversation */
    private fun handleExistingConversation(
        player: Player,
        message: String,
        conversation: com.canefe.story.conversation.Conversation,
        nearbyEntities: NearbyEntities,
        isWhispering: Boolean,
    ) {
        // Add the message to the conversation
        plugin.conversationManager.addPlayerMessage(player, conversation, message)

        // Manage NPCs in the conversation
        manageNPCsInConversation(conversation, nearbyEntities, isWhispering)

        // Manage players in the conversation
        managePlayersInConversation(conversation, nearbyEntities.players, isWhispering)
    }

    /** Manages NPCs joining/leaving an existing conversation */
    private fun manageNPCsInConversation(
        conversation: com.canefe.story.conversation.Conversation,
        nearbyEntities: NearbyEntities,
        isWhispering: Boolean,
    ) {
        // Find NPCs to remove
        val npcsToRemove =
            conversation.npcs.filter { npc ->
                when {
                    plugin.mythicMobConversation.isMythicMobNPC(npc.entity) -> false
                    !nearbyEntities.allInteractableNPCs.contains(npc) ||
                        plugin.npcManager.isNPCDisabled(npc) -> true
                    else -> false
                }
            }

        // Find NPCs to add
        val npcsToAdd =
            if (!isWhispering) {
                nearbyEntities.allInteractableNPCs.filter { npc ->
                    !plugin.mythicMobConversation.isMythicMobNPC(npc.entity) &&
                        !plugin.npcManager.isNPCDisabled(npc) &&
                        !conversation.npcs.contains(npc)
                }
            } else {
                emptyList()
            }

        // Remove NPCs
        npcsToRemove.forEach { npc -> plugin.conversationManager.removeNPC(npc, conversation) }

        // Add NPCs (exclude already removed ones)
        npcsToAdd.filter { !npcsToRemove.contains(it) }.forEach { npc ->
            plugin.conversationManager.joinConversation(npc, conversation)
        }
    }

    /** Manages players joining an existing conversation */
    private fun managePlayersInConversation(
        conversation: com.canefe.story.conversation.Conversation,
        nearbyPlayers: List<Player>,
        isWhispering: Boolean,
    ) {
        if (!isWhispering) {
            val playersToAdd =
                nearbyPlayers.filter { nearbyPlayer -> !conversation.hasPlayer(nearbyPlayer) }

            playersToAdd.forEach { playerToAdd ->
                plugin.conversationManager.joinConversation(playerToAdd, conversation)
            }
        }
    }

    /** Handles message when player is not in a conversation */
    private fun handleNoConversation(
        player: Player,
        message: String,
        nearbyEntities: NearbyEntities,
    ) {
        tryJoinExistingConversation(
            player,
            message,
            nearbyEntities.allInteractableNPCs,
            nearbyEntities.players,
        ).thenAccept { joined ->
            if (!joined) {
                startNewConversationIfPossible(player, message, nearbyEntities)
            }
        }
    }

    /** Attempts to start a new conversation based on available entities */
    private fun startNewConversationIfPossible(
        player: Player,
        message: String,
        nearbyEntities: NearbyEntities,
    ) {
        when {
            nearbyEntities.allInteractableNPCs.isNotEmpty() -> {
                tryStartNewConversation(player, message, nearbyEntities.allInteractableNPCs)
            }
            nearbyEntities.players.isNotEmpty() -> {
                tryStartPlayerConversation(player, message, nearbyEntities.players)
            }
        }
    }

    /** Attempts to join an existing conversation with any nearby NPC or player */
    private fun tryJoinExistingConversation(
        player: Player,
        message: String,
        nearbyNPCs: List<NPC>,
        nearbyPlayers: List<Player>,
    ): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()

        // Check NPCs first
        for (npc in nearbyNPCs) {
            if (!plugin.npcManager.isNPCDisabled(npc) &&
                plugin.conversationManager.isInConversation(npc)
            ) {
                val existingConvo = plugin.conversationManager.getConversation(npc) ?: continue
                plugin.conversationManager
                    .joinConversation(player, existingConvo, message)
                    .thenAccept { success -> result.complete(success) }
                return result
            }
        }

        // Check players if no NPC conversation found
        for (nearbyPlayer in nearbyPlayers) {
            if (plugin.conversationManager.isInConversation(nearbyPlayer)) {
                val existingConvo =
                    plugin.conversationManager.getConversation(nearbyPlayer) ?: continue
                plugin.conversationManager
                    .joinConversation(player, existingConvo, message)
                    .thenAccept { success -> result.complete(success) }
                return result
            }
        }

        result.complete(false)
        return result
    }

    /** Attempts to start a new conversation with nearby NPCs */
    private fun tryStartNewConversation(
        player: Player,
        message: String?,
        nearbyNPCs: List<NPC>,
    ) {
        val availableNPCs = nearbyNPCs.filter { !plugin.npcManager.isNPCDisabled(it) }

        if (availableNPCs.isNotEmpty()) {
            val npcsToAdd = ArrayList<NPC>(availableNPCs)
            val conversation = plugin.conversationManager.startConversation(player, npcsToAdd)
            val startEvent = ConversationStartEvent(player, npcsToAdd, conversation)
            Bukkit.getPluginManager().callEvent(startEvent)

            if (startEvent.isCancelled) {
                plugin.conversationManager.endConversation(conversation)
                player.sendInfo("The conversation couldn't be started.")
                return
            }

            message?.let { plugin.conversationManager.addPlayerMessage(player, conversation, it) }
        }
    }

    /** Attempts to start a new conversation with nearby players */
    private fun tryStartPlayerConversation(
        player: Player,
        message: String?,
        nearbyPlayers: List<Player>,
    ) {
        val availablePlayers = nearbyPlayers.filter { !plugin.playerManager.isPlayerDisabled(it) }

        if (availablePlayers.isNotEmpty()) {
            val conversation =
                plugin.conversationManager.startPlayerConversation(player, availablePlayers)
            message?.let { plugin.conversationManager.addPlayerMessage(player, conversation, it) }
        }
    }

    @EventHandler
    fun onNPCSpawn(event: NPCSpawnEvent) {
        val npc = event.npc
        val scaledNPCs = plugin.npcManager.scaledNPCs
        val scale = scaledNPCs[npc.uniqueId]

        if (scale != null) {
            plugin.npcManager.scaleNPC(npc, scale)
        }
    }

    /** Handles direct player interactions with NPCs (right click) */
    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return // Ignore off-hand interactions
        }

        val player = event.player
        val target = event.rightClicked

        // Check if it's a regular NPC
        val npc = CitizensAPI.getNPCRegistry().getNPC(target)

        // If not a regular NPC, check if it's a disguised player
        if (npc == null && plugin.disguiseManager.isDisguisedAsNPC(target)) {
            val disguisedPlayer = target as Player
            val imitatedNPC = plugin.disguiseManager.getImitatedNPC(disguisedPlayer)

            if (imitatedNPC != null) {
                // Handle the interaction as if it was with the real NPC
                handleDirectInteraction(player, imitatedNPC)
                event.isCancelled = true
                return
            }
        }

        // Continue with regular NPC handling if not a disguised player
        if (npc != null) {
            // Skip if player has disabled interactions
            if (plugin.playerManager.isPlayerDisabled(player)) {
                plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId
                return
            }

            // Check if NPC is disabled/busy
            if (plugin.npcManager.isNPCDisabled(npc)) {
                player.sendError("<yellow>${npc.name}</yellow> is busy.")
                return
            }

            handleDirectInteraction(player, npc)
        }
    }

    /** Handles a direct interaction with an NPC (either real or imitated by a disguised player) */
    fun handleDirectInteraction(
        player: Player,
        npc: NPC,
    ) {
        // Save the last interacted NPC
        plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId

        // Check if the NPC is already in a conversation
        val existingConversation = plugin.conversationManager.getConversation(npc)
        val playersExistingConversation = plugin.conversationManager.getConversation(player)

        if (existingConversation != null) {
            // NPC is already in a conversation, try to add player
            if (existingConversation.players.contains(player.uniqueId)) {
                player.sendInfo("You're already in this conversation.")
                return
            }

            // Check if conversation is locked
            if (plugin.conversationManager.isConversationLocked(existingConversation)) {
                player.sendInfo("<yellow>${npc.name}</yellow> is busy in another conversation.")
                return
            }

            // Add player to the conversation
            plugin.conversationManager.joinConversation(player, existingConversation).thenAccept { success ->
                if (success) {
                    player.sendInfo(
                        "You joined the conversation with <yellow>${npc.name}</yellow>.",
                    )
                } else {
                    player.sendError("Could not join the conversation.")
                }
            }
        } else if (playersExistingConversation != null) {
            // Add the new NPC to the existing conversation
            plugin.conversationManager.joinConversation(npc, playersExistingConversation)
        } else {
            // Start a new conversation with this NPC
            val npcs = ArrayList<NPC>()
            npcs.add(npc)

            // Create the conversation
            plugin.conversationManager.startConversation(player, npcs)
        }
    }

    /** Respond to conversation start events */
    @EventHandler
    fun onConversationStart(event: ConversationStartEvent) {
        // For each NPC in the conversation, stop the navigation
        for (npc in event.npcs) {
            if (npc.entity == null) {
                continue // Skip if the NPC entity is null
            }
            if (!plugin.mythicMobConversation.isMythicMobNPC(npc.entity)) {
                npc.navigator.cancelNavigation()
            }
        }

        // Other potential actions when a conversation starts:
        // - Turn NPCs to face the player
        // - Play conversation start animations
        // - Log conversation start for quest tracking
    }

    /** Respond to conversation join events */
    @EventHandler
    fun onConversationJoin(event: ConversationJoinEvent) {
        // Handle different types of participants
        when (val participant = event.participant) {
            is NPCParticipant -> {
                // check if the conversation is locked
                if (plugin.conversationManager.isConversationLocked(event.conversation)) {
                    // No new participants allowed
                    event.isCancelled = true
                    return
                }

                val npc = participant.npc
                // Stop NPC navigation
                npc.navigator.cancelNavigation()

                // Make NPCs face the closest player
                val closestPlayer =
                    event.conversation.players
                        .mapNotNull { Bukkit.getPlayer(it) }
                        .minByOrNull { player ->
                            player.location.distanceSquared(npc.entity.location)
                        }

                closestPlayer?.let { player ->
                    // Make NPC look at player
                    val direction =
                        player.location.toVector().subtract(npc.entity.location.toVector())
                    npc.entity.location.direction = direction
                }
            }
            is PlayerParticipant -> {
                // Any player-specific join handling could go here
            }
        }
    }
}
