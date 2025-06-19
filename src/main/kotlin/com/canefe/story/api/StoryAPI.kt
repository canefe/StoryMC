package com.canefe.story.api

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import org.bukkit.entity.Player

/**
 * Public API for the Story plugin
 */
interface StoryAPI {
	companion object {
		private lateinit var instance: Story

		/**
		 * Initialize the API with the plugin instance
		 * @param plugin The Story plugin instance
		 */
		@JvmStatic
		fun initialize(plugin: Story) {
			instance = plugin
		}

		/**
		 * Checks if a player is currently in a conversation
		 *
		 * @param player The player to check
		 * @return true if the player is in an active conversation, false otherwise
		 */
		@JvmStatic
		fun isInConversation(player: Player): Boolean = instance.conversationManager.isInConversation(player)

		/**
		 * Gets the current conversation for a player
		 *
		 * @param player The player to check
		 * @return The conversation API wrapper if player is in one, null otherwise
		 */
		@JvmStatic
		fun getCurrentConversation(player: Player): APIConversation? =
			instance.conversationManager.getConversation(player)?.let { conversation ->
				APIConversation(
					id = conversation.id,
					// convert UUID to Player object
					players =
						conversation.players.mapNotNull { uuid ->
							instance.server.getPlayer(uuid)
						},
					npcNames = conversation.npcNames,
					active = conversation.active,
				)
			}

		/**
		 * Gets all active conversations
		 *
		 * @return List of all active conversations as API wrappers
		 */
		@JvmStatic
		fun getActiveConversations(): List<APIConversation> =
			instance.conversationManager.getAllActiveConversations().map { conversation ->
				APIConversation(
					id = conversation.id,
					players =
						conversation.players.mapNotNull { uuid ->
							instance.server.getPlayer(uuid)
						},
					npcNames = conversation.npcNames,
					active = conversation.active,
					history = conversation.history,
				)
			}

		/**
		 * Get NPC data by name
		 *
		 * @param npcName The name of the NPC
		 * @return The NPC data API wrapper if found, null otherwise
		 */
		@JvmStatic
		fun getNPCByName(npcName: String): APINPCData? =
			instance.npcDataManager.getNPCData(npcName)?.let { npcData ->
				APINPCData(
					name = npcData.name,
					context = npcData.context,
					appearance = npcData.appearance,
				)
			}
	}
}

/**
 * API representation of a conversation that's exposed to other plugins
 */
class APIConversation(
	val id: Int,
	val players: List<Player>,
	val npcNames: List<String>,
	val active: Boolean,
	val history: List<ConversationMessage> = emptyList(),
)

/**
 * API representation of NPC data that's exposed to other plugins
 */
class APINPCData(
	val name: String,
	val context: String,
	val appearance: String,
)
