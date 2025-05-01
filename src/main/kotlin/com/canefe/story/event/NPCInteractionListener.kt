package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.conversation.event.ConversationJoinEvent
import com.canefe.story.conversation.event.ConversationStartEvent
import com.canefe.story.conversation.event.NPCParticipant
import com.canefe.story.conversation.event.PlayerParticipant
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.ArrayList
import java.util.concurrent.CompletableFuture

class NPCInteractionListener(
	private val plugin: Story,
) : Listener {
	/**
	 * Handles player chat events and processes NPC interactions
	 */
	@EventHandler
	fun onPlayerChat(event: AsyncChatEvent) {
		val player = event.player
		val message = PlainTextComponentSerializer.plainText().serialize(event.message())
		event.isCancelled = true
		plugin.npcMessageService.broadcastPlayerMessage(message, player)
		// Use regex to find *whisper*, *whispers*, *whispering*
		val isWhispering = message.matches(Regex(".*\\*whisper(s|ing)?\\*.*"))

		// Skip if player has disabled NPC interactions
		if (plugin.playerManager.isPlayerDisabled(player)) {
			return
		}

		// Collect all nearby NPCs that player could interact with
		val nearbyNPCs = plugin.getNearbyNPCs(player, plugin.config.chatRadius)

		// Check if any nearby NPC is already in a conversation that player can join
		val currentConversation = plugin.conversationManager.getConversation(player)

		if (currentConversation != null) {
			// Player is already in a conversation, add the message
			plugin.conversationManager.addPlayerMessage(player, currentConversation, message)

			// Check for NPCs that need to be removed from player's conversation
			val npcsToRemove = ArrayList<NPC>()
			for (npc in currentConversation.npcs) {
				if (!nearbyNPCs.contains(npc) || plugin.npcManager.isNPCDisabled(npc)) {
					npcsToRemove.add(npc)
				}
			}

			// Check for NPCs that need to be added to player's conversation
			val npcsToAdd = ArrayList<NPC>()
			for (npc in nearbyNPCs) {
				if (isWhispering) {
					if (!plugin.npcManager.isNPCDisabled(npc) && !currentConversation.npcs.contains(npc)) {
						npcsToAdd.add(npc)
					}
				}
			}

			// Remove NPCs that are no longer nearby
			for (npcToRemove in npcsToRemove) {
				plugin.conversationManager.removeNPC(npcToRemove, currentConversation)
			}

			// Don't add NPCs that already were removed
			npcsToAdd.removeAll(npcsToRemove.toSet())

			// Add new NPCs to the conversation
			for (npcToAdd in npcsToAdd) {
				plugin.conversationManager.joinConversation(npcToAdd, currentConversation)
			}
		} else {
			// Player is not in a conversation
			// Try to join an existing conversation first
			tryJoinExistingConversation(player, message, nearbyNPCs)
				.thenAccept { joined ->
					if (!joined) {
						// If didn't join existing, start a new conversation with nearby NPCs
						tryStartNewConversation(player, message, nearbyNPCs)
					}
				}
		}
	}

	/**
	 * Attempts to join an existing conversation with any nearby NPC
	 */
	private fun tryJoinExistingConversation(
		player: org.bukkit.entity.Player,
		message: String,
		nearbyNPCs: List<NPC>,
	): CompletableFuture<Boolean> {
		val result = CompletableFuture<Boolean>()

		// Async processing since we need to call events on main thread
		Bukkit.getScheduler().runTask(
			plugin,
			Runnable {
				for (npc in nearbyNPCs) {
					if (!plugin.npcManager.isNPCDisabled(npc) &&
						plugin.conversationManager.isInConversation(npc)
					) {
						// Get the existing conversation this NPC is in
						val existingConvo = plugin.conversationManager.getConversation(npc) ?: continue

						// Try to join the conversation via event
						plugin.conversationManager
							.joinConversation(player, existingConvo, message)
							.thenAccept { success ->
								result.complete(success)
							}

						return@Runnable
					}
				}
				result.complete(false)
			},
		)

		return result
	}

	/**
	 * Attempts to start a new conversation with nearby NPCs
	 */
	private fun tryStartNewConversation(
		player: org.bukkit.entity.Player,
		message: String?,
		nearbyNPCs: List<NPC>,
	) {
		// Run on main thread to fire events
		Bukkit.getScheduler().runTask(
			plugin,
			Runnable {
				// Filter out disabled NPCs
				val availableNPCs = nearbyNPCs.filter { !plugin.npcManager.isNPCDisabled(it) }

				if (availableNPCs.isNotEmpty()) {
					// Create the conversation object
					val npcsToAdd = ArrayList<NPC>(availableNPCs)

					// Fire ConversationStartEvent
					val conversation = plugin.conversationManager.startConversation(player, npcsToAdd)
					val startEvent = ConversationStartEvent(player, npcsToAdd, conversation)
					Bukkit.getPluginManager().callEvent(startEvent)

					// Check if the event was cancelled
					if (startEvent.isCancelled) {
						plugin.conversationManager.endConversation(conversation)
						player.sendInfo("The conversation couldn't be started.")
						return@Runnable
					}

					// Add initial message if provided
					if (message != null) {
						plugin.conversationManager.addPlayerMessage(player, conversation, message)
					}
				}
			},
		)
	}

	/**
	 * Handles direct player interactions with NPCs (right click)
	 */
	@EventHandler
	fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
		if (event.hand != EquipmentSlot.HAND) {
			return // Ignore off-hand interactions
		}

		val player = event.player
		val npc = CitizensAPI.getNPCRegistry().getNPC(event.rightClicked) ?: return

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

	/**
	 * Handle a direct interaction with an NPC through right-click
	 */
	private fun handleDirectInteraction(
		player: org.bukkit.entity.Player,
		npc: NPC,
	) {
		// Check if player is already in a conversation
		val existingPlayerConversation = plugin.conversationManager.getConversation(player)

		// Check if NPC is already in a conversation
		val existingNPCConversation = plugin.conversationManager.getConversation(npc)

		when {
			// Case 1: Both in same conversation - end it
			existingPlayerConversation != null && existingNPCConversation == existingPlayerConversation -> {
				// End conversation with CompletableFuture handling
				plugin.conversationManager
					.endConversation(existingPlayerConversation)
					.exceptionally { ex ->
						plugin.logger.warning("Error ending conversation: ${ex.message}")
						null
					}
				return
			}

			// Case 2: NPC in another conversation - try to join it
			existingNPCConversation != null -> {
				// If player is in a different conversation, end it first
				if (existingPlayerConversation != null && existingPlayerConversation != existingNPCConversation) {
					plugin.conversationManager
						.endConversation(existingPlayerConversation)
						.thenRun {
							// Join NPC's conversation after ending player's conversation
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									plugin.conversationManager.joinConversation(player, existingNPCConversation)
								},
							)
						}
				} else {
					// Join NPC's conversation
					plugin.conversationManager.joinConversation(player, existingNPCConversation)
				}
				return
			}

			// Case 3: Player in conversation, NPC not - add NPC to player conversation
			existingPlayerConversation != null -> {
				plugin.conversationManager.joinConversation(npc, existingPlayerConversation)
				return
			}

			// Case 4: Neither in conversation - start new one
			else -> {
				val npcs = ArrayList<NPC>()
				npcs.add(npc)

				// Create the conversation
				plugin.conversationManager.startConversation(player, npcs)
			}
		}
	}

	/**
	 * Respond to conversation start events
	 */
	@EventHandler
	fun onConversationStart(event: ConversationStartEvent) {
		// For each NPC in the conversation, stop the navigation
		for (npc in event.npcs) {
			if (!plugin.mythicMobConversation.isMythicMobNPC(npc.entity)) {
				npc.navigator.cancelNavigation()
			}
		}

		// Other potential actions when a conversation starts:
		// - Turn NPCs to face the player
		// - Play conversation start animations
		// - Log conversation start for quest tracking
	}

	/**
	 * Respond to conversation join events
	 */
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
					val direction = player.location.toVector().subtract(npc.entity.location.toVector())
					npc.entity.location.direction = direction
				}
			}

			is PlayerParticipant -> {
				// Any player-specific join handling could go here
			}
		}
	}
}
