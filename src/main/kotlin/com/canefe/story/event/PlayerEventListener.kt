package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.util.EssentialsUtils
import net.kokoricraft.reviveme.events.PlayerDownedEvent
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Handles player-related events for the Story plugin
 */
class PlayerEventListener(
    private val plugin: Story,
) : Listener {
    /**
     * Handles player item drops during conversations
     */
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        // Check if in conversation
        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(
                player.name,
            )} dropped ${event.itemDrop.itemStack.type.name} amount ${event.itemDrop.itemStack.amount}",
        )
    }

    /**
     * Handles player damage taken during conversations
     */
    @EventHandler
    fun onPlayerDamagedByMob(event: EntityDamageByEntityEvent) {
        val player = event.entity
        val damager = event.damager

        if (player !is Player) return

        // Check if in conversation
        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        val name =
            if (damager is LivingEntity) {
                damager.customName() ?: damager.type.name
            } else {
                damager.type.name
            }

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(
                player.name,
            )} was damaged by $name amount ${event.finalDamage}",
        )
    }

    @EventHandler
    fun onPlayerDowned(event: PlayerDownedEvent) {
        val player = event.player
        val enemy =
            event.enemy?.let {
                EssentialsUtils.getNickname(it.name)
            } ?: event.cause.name

        // Check if in conversation
        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(
                player.name,
            )} fell on the ground, downed by $enemy. They need to be revived.",
        )
    }

    /**
     * Handles player item pickups during conversations
     */
    @EventHandler
    fun onPlayerPickupItem(event: PlayerAttemptPickupItemEvent) {
        val player = event.player
        // Check if in conversation
        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(
                player.name,
            )} picked up ${event.item.itemStack.type.name} amount ${event.item.itemStack.amount}",
        )
    }

    /**
     * Handles player join event
     *
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val playerUUID = player.uniqueId
        // Show current quest to the player
        val currentQuest = plugin.questManager.getCurrentQuest(player) ?: return
        // wait a few seconds before showing the quest, waiting for the player to load
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                // Check if the player is still online
                if (plugin.server.getPlayer(playerUUID) != null) {
                    plugin.questManager.printQuest(currentQuest, player)
                }
            },
            20L * 5, // 5 seconds delay
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
