package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.event.ConversationJoinEvent
import com.canefe.story.api.event.ConversationStartEvent
import com.canefe.story.api.event.NPCParticipant
import com.canefe.story.api.event.PlayerParticipant
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.util.*
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot

class NPCInteractionListener(
    private val plugin: Story,
) : Listener {
    // Map to store debounce timers and accumulated messages for each player
    private val delayedMessageTimers = mutableMapOf<Player, Int>()
    private val accumulatedMessages = mutableMapOf<Player, MutableList<String>>()

    /** Handles player chat events and processes NPC interactions */
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        if (!plugin.config.chatEnabled) return
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

        // Check if delayed message processing is enabled (global config OR per-player config)
        val playerConfig = plugin.playerManager.getPlayerConfig(player.uniqueId)
        if (plugin.config.delayedPlayerMessageProcessing || playerConfig.delayedPlayerMessageProcessing) {
            processDelayedPlayerMessage(player, message)
            return
        }

        // Broadcast the message
        plugin.npcMessageService.broadcastPlayerMessage(message, player)

        // Emit speech perception unconditionally — NPCs react via the perception system, not conversations.
        plugin.conversationManager.emitPlayerSpeech(player, message)
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
        val playerName = player.characterName
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
        val playerCharacterName = player.characterName
        val playerCharacterId = player.characterId

        if (playerCharacterId.isNullOrEmpty() || player.character == null) {
            player.sendError("Could not find character data for $playerCharacterName.")
            return
        }

        plugin.intelligence
            .playerGhostwrite(player, playerCharacterId, playerCharacterName, message)
            .thenApply { response ->
                if (response.isBlank()) {
                    plugin.logger.warning(
                        "[AICharacterVoice] Empty response for ${player.name} ($playerCharacterName); falling back to raw message.",
                    )
                }
                val finalResponse = if (response.isBlank()) message else response

                val shouldStream = plugin.config.streamMessages
                if (shouldStream) {
                    handleStreamingPlayerResponse(player, finalResponse)
                } else {
                    plugin.npcMessageService.broadcastPlayerMessage(finalResponse, player)
                }

                // Speech perception is the only audience-side signal — no conversation join/start.
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        plugin.conversationManager.emitPlayerSpeech(player, finalResponse)
                    },
                )
            }.exceptionally { e ->
                plugin.logger.warning(
                    "[AICharacterVoice] playerGhostwrite failed for ${player.name}: ${e.message}",
                )
                e.printStackTrace()
                null
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

    @EventHandler
    fun onNPCSpawn(event: NPCSpawnEvent) {
        val npc = event.npc
        val scaledNPCs = plugin.npcManager.scaledNPCs
        val scale = scaledNPCs[npc.uniqueId]

        if (scale != null) {
            plugin.npcManager.scaleNPC(CitizensStoryNPC(npc), scale)
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
        val citizensNpc = CitizensAPI.getNPCRegistry().getNPC(target)

        // If not a regular NPC, check if it's a disguised player
        if (citizensNpc == null && plugin.disguiseManager.isDisguisedAsNPC(target)) {
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
        if (citizensNpc != null) {
            val npc = CitizensStoryNPC(citizensNpc)

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
        npc: StoryNPC,
    ) {
        // Save the last interacted NPC for other systems that read playerCurrentNPC.
        plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId
    }

    /** Respond to conversation start events */
    @EventHandler
    fun onConversationStart(event: ConversationStartEvent) {
        // For each NPC in the conversation, stop the navigation
        for (npc in event.npcs) {
            val entity = npc.entity ?: continue // Skip if the NPC entity is null
            if (!plugin.mythicMobConversation.isMythicMobNPC(entity)) {
                npc.cancelNavigation()
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
                npc.cancelNavigation()

                // Make NPCs face the closest player
                val npcEntity = npc.entity
                if (npcEntity != null) {
                    val closestPlayer =
                        event.conversation.players
                            .mapNotNull { Bukkit.getPlayer(it) }
                            .minByOrNull { player ->
                                player.location.distanceSquared(npcEntity.location)
                            }

                    closestPlayer?.let { player ->
                        // Make NPC look at player
                        val direction =
                            player.location.toVector().subtract(npcEntity.location.toVector())
                        npcEntity.location.direction = direction
                    }
                }
            }

            is PlayerParticipant -> {
                // Any player-specific join handling could go here
            }
        }
    }
}
