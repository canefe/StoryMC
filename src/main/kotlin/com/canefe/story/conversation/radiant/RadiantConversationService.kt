package com.canefe.story.conversation.radiant

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.player.NPCManager
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Service responsible for handling radiant conversations between NPCs and players
 */
class RadiantConversationService(private val plugin: Story) {

    private val npcManager = NPCManager.getInstance(plugin)

    /**
     * Starts periodic proximity checks for radiant conversations
     */
    fun startProximityTask() {
        Bukkit.getScheduler().runTaskTimer (plugin, Runnable {
            if (!plugin.config.radiantEnabled)
                return@Runnable

            for (player in Bukkit.getOnlinePlayers()) {
                handleProximityCheck(player)
            }
        }, 20L * 5, 20L * 10) // Check every 10 seconds
    }

    /**
     * Handles proximity check for a specific player
     */
    private fun handleProximityCheck(player: Player) {
        if (plugin.playerManager.isPlayerDisabled(player)) {
            return
        }

        val nearbyNPCs = plugin.getNearbyNPCs(player, plugin.config.radiantRadius)

        // Try to find a non-cooldown NPC to initiate conversation
        for (npc in nearbyNPCs) {
            if (plugin.npcManager.isNPCDisabled(npc)) {
                continue
            }

            if (npcManager.isNPCOnCooldown(npc)) {
                continue
            }

            triggerRadiantConversation(npc, player)
            npcManager.setNPCCooldown(npc)
            break
        }
    }

    /**
     * Triggers a radiant conversation with an NPC and a player or another NPC
     */
    fun triggerRadiantConversation(initiator: NPC, player: Player) {
        if (plugin.conversationManager.isInConversation(initiator)) {
            return
        }

        if (plugin.conversationManager.isInConversation(player)) {
            return
        }

        val random = Random()
        val choosePlayer = random.nextBoolean() // 50% chance to choose player

        if (choosePlayer && !isPlayerInvalidTarget(player)) {
            // Target is player
            initiatePlayerConversation(initiator, player)
        } else {
            // Target is another NPC
            initiateNPCConversation(initiator)
        }
    }

    /**
     * Check if a player is an invalid target for conversation
     */
    private fun isPlayerInvalidTarget(player: Player): Boolean {
        return isVanished(player) || plugin.playerManager.isPlayerDisabled(player)
    }

    /**
     * Check if a player is vanished
     */
    private fun isVanished(player: Player): Boolean {
        if (player.hasMetadata("vanished")) {
            return player.getMetadata("vanished").any { it.asBoolean() }
        }
        return false
    }

    /**
     * Initiates a conversation between an NPC and a player
     */
    private fun initiatePlayerConversation(initiator: NPC, player: Player) {
        val initiatorName = initiator.name
        val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(initiatorName) ?:
            return

        CompletableFuture.runAsync {
            try {

                val playerName = EssentialsUtils.getNickname(player.name)

                // Get AI response
                val greeting = plugin.npcResponseService.generateNPCGreeting(initiator, playerName) ?: return@runAsync

                // Check if greeting is empty
                if (greeting == "") {
                    plugin.logger.warning("Empty greeting generated for NPC $initiatorName")
                    return@runAsync
                }

                // Make NPC go to player and talk
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    npcManager.eventGoToPlayerAndTalk(initiator, player, greeting, null)
                })
            } catch (e: Exception) {
                plugin.logger.warning("Error generating greeting for NPC $initiatorName")
                e.printStackTrace()
            }
        }
    }

    /**
     * Initiates a conversation between two NPCs
     */
    private fun initiateNPCConversation(initiator: NPC) {
        val nearbyNPCs = plugin.getNearbyNPCs(initiator, plugin.config.radiantRadius)

        // Filter available NPCs
        val availableNPCs = nearbyNPCs.filter { npc ->
            !plugin.conversationManager.isInConversation(npc) &&
                    !npcManager.isNPCOnCooldown(npc) &&
                    !plugin.npcManager.isNPCDisabled(npc) &&
                    npc != initiator
        }

        if (availableNPCs.isEmpty()) return

        // Choose random target NPC
        val random = Random()
        val targetNPC = availableNPCs[random.nextInt(availableNPCs.size)]

        val initiatorName = initiator.name
        val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(initiatorName) ?:
            return

        CompletableFuture.runAsync {
            try {
                // Get AI response
                val greeting = plugin.npcResponseService.generateNPCGreeting(initiator, targetNPC.name) ?: return@runAsync

                // Make NPCs talk to each other
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    npcManager.walkToNPC(initiator, targetNPC, greeting)
                    npcManager.setNPCCooldown(targetNPC)
                })

            } catch (e: Exception) {
                plugin.logger.warning("Error generating greeting for NPC $initiatorName")
                e.printStackTrace()
            }
        }
    }
}