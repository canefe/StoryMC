package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.api.event.ConversationJoinEvent
import com.canefe.story.api.event.ConversationStartEvent
import com.canefe.story.api.event.NPCParticipant
import com.canefe.story.api.event.PlayerParticipant
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

class NPCInteractionListener(private val plugin: Story) : Listener {
	/**
	 * Handles player chat events and processes NPC interactions
	 */
	@EventHandler
	fun onPlayerChat(event: AsyncChatEvent) {
		val player = event.player
		val message = PlainTextComponentSerializer.plainText().serialize(event.message())
		event.isCancelled = true

		// Schedule processing on the main thread to safely use getNearbyEntities
		Bukkit.getScheduler().runTask(
			plugin,
			Runnable {
				if (plugin.disguiseManager.getImitatedNPC(player) != null) {
					// make player execute command "g"
					player.performCommand("h $message")
					return@Runnable
				}

				plugin.npcMessageService.broadcastPlayerMessage(message, player)

				// Use regex to find *whisper*, *whispers*, *whispering*
				val isWhispering = message.matches(Regex(".*\\*whisper(s|ing)?\\*.*"))

				// Skip if player has disabled NPC interactions
				if (plugin.playerManager.isPlayerDisabled(player)) {
					return@Runnable
				}

				val chatRadius = if (isWhispering) 2.0 else plugin.config.chatRadius

				// These methods are now safe because they're on the main thread
				val nearbyNPCs = plugin.getNearbyNPCs(player, chatRadius)

				val disguisedPlayers =
					player
						.getNearbyEntities(chatRadius, chatRadius, chatRadius)
						.filter { plugin.disguiseManager.isDisguisedAsNPC(it) }
						.mapNotNull { (it as? org.bukkit.entity.Player)?.let { p -> plugin.disguiseManager.getImitatedNPC(p) } }

				// Filter out mythic mob npcs
				val mythicMobNPCs =
					player
						.getNearbyEntities(chatRadius, chatRadius, chatRadius)
						.filter { plugin.mythicMobConversation.isMythicMobNPC(it) }
						// plugin.mythicMobConversation.getMythicMobNPC(it) }
						.mapNotNull {
							(it as? org.bukkit.entity.LivingEntity)?.let { e ->
								plugin.mythicMobConversation.getOrCreateNPCAdapter(e)
							}
						}

				// Combine regular NPCs with impersonated NPCs for interaction
				val allInteractableNPCs = (nearbyNPCs + disguisedPlayers).distinct()

				// Check if any nearby NPC is already in a conversation that player can join
				val currentConversation = plugin.conversationManager.getConversation(player)

				if (currentConversation != null) {
					// Player is already in a conversation, add the message
					plugin.conversationManager.addPlayerMessage(player, currentConversation, message)

					// Check for NPCs that need to be removed from player's conversation
					val npcsToRemove = ArrayList<NPC>()
					for (npc in currentConversation.npcs) {
						if (plugin.mythicMobConversation.isMythicMobNPC(npc.entity)) {
							// Skip MythicMob NPCs
							continue
						}
						if (!allInteractableNPCs.contains(npc) || plugin.npcManager.isNPCDisabled(npc)) {
							npcsToRemove.add(npc)
						}
					}

					// Check for NPCs that need to be added to player's conversations
					val npcsToAdd = ArrayList<NPC>()
					for (npc in allInteractableNPCs) {
						// skip if its MythicMob NPC
						if (plugin.mythicMobConversation.isMythicMobNPC(npc.entity)) {
							continue
						}
						if (!isWhispering) {
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
					tryJoinExistingConversation(player, message, allInteractableNPCs)
						.thenAccept { joined ->
							if (!joined) {
								// If didn't join existing, start a new conversation with nearby NPCs
								tryStartNewConversation(player, message, allInteractableNPCs)
							}
						}
				}
			},
		)
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
	private fun tryStartNewConversation(player: org.bukkit.entity.Player, message: String?, nearbyNPCs: List<NPC>) {
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

	@EventHandler
	fun onNPCSpawn(event: NPCSpawnEvent) {
		val npc = event.npc
		val scaledNPCs = plugin.npcManager.scaledNPCs
		val scale = scaledNPCs[npc.uniqueId]

		if (scale != null) {
			plugin.npcManager.scaleNPC(npc, scale)
		}
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
		val target = event.rightClicked

		// Check if it's a regular NPC
		val npc = CitizensAPI.getNPCRegistry().getNPC(target)

		// If not a regular NPC, check if it's a disguised player
		if (npc == null && plugin.disguiseManager.isDisguisedAsNPC(target)) {
			val disguisedPlayer = target as org.bukkit.entity.Player
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

	/**
	 * Handles a direct interaction with an NPC (either real or imitated by a disguised player)
	 */
	fun handleDirectInteraction(player: Player, npc: NPC) {
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
			plugin.conversationManager
				.joinConversation(player, existingConversation)
				.thenAccept { success ->
					if (success) {
						player.sendInfo("You joined the conversation with <yellow>${npc.name}</yellow>.")
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

	/**
	 * Respond to conversation start events
	 */
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
