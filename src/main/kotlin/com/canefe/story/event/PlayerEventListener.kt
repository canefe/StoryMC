package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.api.event.PlayerLocationChangeEvent
import com.canefe.story.util.EssentialsUtils
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.time.Duration
import java.util.UUID
import kotlin.collections.set

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

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player

        // Only process meaningful movement (ignore head rotation, tiny steps)
        if (event.from.world != event.to.world) return
        if (event.from.distanceSquared(event.to) < 0.25) return // <0.5 block movement

        val toLoc = plugin.locationManager.getLocationByPosition2D(event.to)
        val prevLoc = plugin.playerManager.lastLocation[player.uniqueId]

        // No change? Do nothing
        if (toLoc == prevLoc) return

        // Update cache
        plugin.playerManager.lastLocation[player.uniqueId] = toLoc

        // Fire the event
        val changeEvent = PlayerLocationChangeEvent(player, prevLoc, toLoc)
        Bukkit.getPluginManager().callEvent(changeEvent)

        // Optional debug
        plugin.logger.info("[LocationChange: ${player.name}]  ${prevLoc?.name} -> ${toLoc?.name}")
    }

    @EventHandler
    fun onPlayerLocationChange(event: PlayerLocationChangeEvent) {
        val to = event.to ?: return
        val from = event.from

        if (to.hideTitle) return

        val canShowTitle: (UUID) -> Boolean = { id ->
            plugin.playerManager.canShowTitle(id)
        }

        // 1. Ignore if same exact location
        if (from != null && from.name == to.name) {
            return
        }

        // 2. Ignore sub → parent transitions
        // example: from.name = "Yohg/Dex", to.name = "Yohg"
        if (from != null && from.parentLocationName == to.name) {
            return
        }

        if (!canShowTitle(event.player.uniqueId)) return

        val mm = plugin.miniMessage
        val audience = Audience.audience(event.player)

        val name = to.getFormattedName()
        val depth = to.name.count { it == '/' }
        val parent = to.parentLocationName

        if (depth == 1 && parent != null) {
            val title =
                Title.title(
                    mm.deserialize(""),
                    mm.deserialize("<gray>$parent - <yellow>$name"),
                    Title.Times.times(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1),
                    ),
                )
            audience.showTitle(title)
        } else {
            val title =
                Title.title(
                    mm.deserialize(""),
                    mm.deserialize("<yellow>$name"),
                    Title.Times.times(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1),
                    ),
                )
            audience.showTitle(title)
        }
    }
}
