package com.canefe.story.conversation.radiant

import com.canefe.story.Story
import com.canefe.story.player.NPCManager
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Service responsible for handling radiant conversations between NPCs and players
 */
class RadiantConversationService(
    private val plugin: Story,
) {
    private val npcManager = NPCManager.getInstance(plugin)

    // Track players who recently witnessed a radiant conversation
    private val playerWitnessedConversation = ConcurrentHashMap<UUID, Long>()

    /**
     * Starts periodic proximity checks for radiant conversations
     */
    fun startProximityTask() {
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                if (!plugin.config.radiantEnabled) {
                    return@Runnable
                }

                for (player in Bukkit.getOnlinePlayers()) {
                    handleProximityCheck(player)
                }
            },
            20L * 5,
            20L * 10,
        ) // Check every 10 seconds
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
    private fun triggerRadiantConversation(
        initiator: NPC,
        player: Player,
    ) {
        if (plugin.conversationManager.isInConversation(initiator)) {
            return
        }

        if (plugin.conversationManager.isInConversation(player)) {
            return
        }

        // First check if player is a valid potential target
        val isPlayerValid = !isPlayerInvalidTarget(player)

        if (!isPlayerValid) {
            return
        }

        // Then check if there are other valid NPC targets
        val nearbyNPCs = plugin.getNearbyNPCs(initiator, plugin.config.radiantRadius)
        val hasValidNPCTarget =
            nearbyNPCs.any { npc ->
                !plugin.conversationManager.isInConversation(npc) &&
                    !npcManager.isNPCOnCooldown(npc) &&
                    !plugin.npcManager.isNPCDisabled(npc) &&
                    npc != initiator
            }

        // Make random decision only if both options are possible
        val random = Random()
        val choosePlayer =
            if (isPlayerValid && hasValidNPCTarget) {
                random.nextBoolean() // 50% chance only when both options are possible
            } else {
                isPlayerValid // Choose player only if it's the only valid option
            }

        if (choosePlayer) {
            val playerName = EssentialsUtils.getNickname(player.name)

            // Check if the relationship is strong enough
            val relationship = plugin.relationshipManager.getRelationship(initiator.name, playerName)

            if (relationship.score < 0.1) {
                return
            }

            // Target is player
            initiatePlayerConversation(initiator, player)
        } else if (hasValidNPCTarget) {
            // Target is another NPC
            initiateNPCConversation(initiator)
        }
        // If neither option is valid, conversation attempt fails silently
    }

    /**
     * Check if a player is an invalid target for conversation
     */
    private fun isPlayerInvalidTarget(player: Player): Boolean =
        isVanished(player) ||
            plugin.playerManager.isPlayerDisabled(player) ||
            plugin.conversationManager.isInConversation(player) ||
            hasRecentlyWitnessedConversation(player)

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
     * Check if a player has recently witnessed a radiant conversation
     */
    private fun hasRecentlyWitnessedConversation(player: Player): Boolean {
        val lastWitness = playerWitnessedConversation[player.uniqueId] ?: return false
        val currentTime = System.currentTimeMillis()
        val witnessDelay = plugin.config.radiantCooldown * 1000L // Convert seconds to milliseconds
        return currentTime - lastWitness < witnessDelay
    }

    /**
     * Mark that a player has witnessed a radiant conversation
     */
    private fun markPlayerWitnessedConversation(player: Player) {
        playerWitnessedConversation[player.uniqueId] = System.currentTimeMillis()
    }

    /**
     * Get all players who might witness a conversation within a radius
     */
    private fun getPlayersInRadius(
        location: org.bukkit.Location,
        radius: Double,
    ): List<Player> =
        location.world.players.filter { player ->
            !isPlayerInvalidTarget(player) && player.location.distance(location) <= radius
        }

    /**
     * Initiates a conversation between an NPC and a player
     */
    private fun initiatePlayerConversation(
        initiator: NPC,
        player: Player,
    ) {
        val initiatorName = initiator.name

        // Mark the target player as having witnessed a conversation
        markPlayerWitnessedConversation(player)

        // Mark nearby players as having witnessed the conversation
        val nearbyPlayers = getPlayersInRadius(player.location, plugin.config.radiantRadius)
        nearbyPlayers.forEach { nearbyPlayer ->
            markPlayerWitnessedConversation(nearbyPlayer)
        }

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

                // Check one more time if the player is in conversation
                if (plugin.conversationManager.isInConversation(player)) {
                    return@runAsync
                }

                // Make NPC go to player and talk
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        npcManager.eventGoToPlayerAndTalk(initiator, player, greeting, null)
                    },
                )
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
        val availableNPCs =
            nearbyNPCs.filter { npc ->
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

        npcManager.setNPCCooldown(targetNPC)

        // Mark nearby players as having witnessed the conversation
        val nearbyPlayers = getPlayersInRadius(initiator.entity.location, plugin.config.radiantRadius)
        nearbyPlayers.forEach { nearbyPlayer ->
            markPlayerWitnessedConversation(nearbyPlayer)
        }

        CompletableFuture.runAsync {
            try {
                // Get AI response
                val greeting =
                    plugin.npcResponseService.generateNPCGreeting(initiator, targetNPC.name) ?: return@runAsync

                // Make NPCs talk to each other
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        npcManager.walkToNPC(initiator, targetNPC, greeting, radiant = true)
                    },
                )
            } catch (e: Exception) {
                plugin.logger.warning("Error generating greeting for NPC $initiatorName")
                e.printStackTrace()
            }
        }
    }
}
