package com.canefe.story.event

import com.canefe.story.util.EssentialsUtils
import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Handles player-related events for the Story plugin
 */
class PlayerEventListener(private val plugin: Story) : Listener {

    /**
     * Handles player item drops during conversations
     */
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        // Check if in conversation
        val conversation = plugin.conversationManager.getConversation(player) ?:
            return

        conversation.addSystemMessage("${EssentialsUtils.getNickname(player.name)} dropped ${event.itemDrop.itemStack.type.name} amount ${event.itemDrop.itemStack.amount}"
            )
    }

    /**
     * Handles player item pickups during conversations
     */
    @EventHandler
    fun onPlayerPickupItem(event: PlayerAttemptPickupItemEvent) {
        val player = event.player
        // Check if in conversation
        val conversation = plugin.conversationManager.getConversation(player) ?:
            return

        conversation.addSystemMessage(
                "${EssentialsUtils.getNickname(player.name)} picked up ${event.item.itemStack.type.name} amount ${event.item.itemStack.amount}"
        )
    }

    /**
     * Handles player quit event to clean up ongoing conversations
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val playerUUID = player.uniqueId

        // Remove player from NPC tracking
        plugin.playerManager.playerCurrentNPC.remove(playerUUID)

        // Remove the player from any active conversations
        val conversation = plugin.conversationManager.getConversation(player) ?: return
        plugin.conversationManager.endConversation(conversation)
    }
}