package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import java.util.ArrayList
import java.util.UUID

class NPCInteractionListener(private val plugin: Story) : Listener {

    /**
     * Handles player chat events and processes NPC interactions
     */
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        val player = event.player
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())

        // Skip if player has disabled NPC interactions
        if (plugin.playerManager.isPlayerDisabled(player)) {
            return
        }

        // Collect all nearby NPCs that player could interact with
        val nearbyNPCs = plugin.getNearbyNPCs(player, plugin.config.chatRadius)

        // Check if any nearby NPC is already in a conversation that player can join
        var joinedExistingConversation = false
        for (npc in nearbyNPCs) {
            if (!plugin.npcManager.isNPCDisabled(npc) &&
                plugin.conversationManager.isInConversation(npc)) {

                // Get the existing conversation this NPC is in
                val existingConvo = plugin.conversationManager.getConversation(npc)

                // Check if this is a different conversation than what the player is currently in
                if (existingConvo != null &&
                    (!plugin.conversationManager.isInConversation(player) ||
                            plugin.conversationManager.getConversation(player) != existingConvo)) {

                    // End the player's current conversation if they're in one
                    if (plugin.conversationManager.isInConversation(player)) {
                        plugin.conversationManager.endConversation(
                            plugin.conversationManager.getConversation(player)!!
                        )
                    }

                    // Add player to the existing conversation
                    existingConvo.addPlayer(player)

                    // Add the player's message to the conversation
                    plugin.conversationManager.addPlayerMessage(player, existingConvo, message)

                    joinedExistingConversation = true
                    break
                }
            }
        }

        // Only proceed with normal handling if player didn't join an existing conversation
        if (!joinedExistingConversation) {
            // Handle interactions with nearby NPCs
            for (npc in nearbyNPCs) {
                if (!plugin.npcManager.isNPCDisabled(npc)) {

                    // Initialize conversation with this NPC
                    plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId
                    handleConversation(player, npc, false)

                }
            }

            // Check for NPCs that need to be removed from player's conversation
            if (plugin.conversationManager.isInConversation(player)) {
                val conversation = plugin.conversationManager.getConversation(player) ?: return
                val npcsToRemove = ArrayList<NPC>()

                for (npc in conversation.npcs) {
                    if (!nearbyNPCs.contains(npc) || plugin.npcManager.isNPCDisabled(npc)) {
                        npcsToRemove.add(npc)
                    }
                }

                // Remove NPCs that are no longer nearby
                for (npcToRemove in npcsToRemove) {
                    conversation.removeNPC(npcToRemove)
                }

                plugin.conversationManager.addPlayerMessage(player, conversation, message)
            }
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
        val npc = CitizensAPI.getNPCRegistry().getNPC(event.rightClicked) ?: return

        // Process the interaction through the main plugin
        handleConversation(player, npc, true)
    }


    /**
     * Handles the conversation logic between a player and an NPC.
     * This method determines whether to join an existing conversation,
     * create a new one, or end a conversation based on the current state.
     *
     * @param player The player interacting with the NPC
     * @param npc The NPC being interacted with
     * @param isDirectInteraction Whether this is a direct interaction (right-click) or chat
     * @return true if the interaction was handled, false otherwise
     */
    private fun handleConversation(player: org.bukkit.entity.Player, npc: NPC, isDirectInteraction: Boolean): Boolean {
        // Check if player has disabled interactions
        if (plugin.playerManager.isPlayerDisabled(player)) {
            plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId
            return true
        }

        val npcName = npc.name
        val playerUUID = player.uniqueId
        try {
            // Check if player is already in a conversation
            val conversation = plugin.conversationManager.getConversation(player)
            if (conversation != null) {
                // Only end the conversation on direct interaction (right-click), not during chat
                if (isDirectInteraction && conversation.hasNPC(npc)) {
                    plugin.conversationManager.endConversation(conversation)
                    return true
                }

                // Check if NPC is disabled/busy
                if (plugin.npcManager.isNPCDisabled(npc)) {
                    player.sendError("<yellow>$npcName</yellow> is busy.")
                    return true
                }

                // Add this NPC to the existing conversation
                conversation.addNPC(npc)
                return true
            }

            // Player is not in a conversation
            else {
                // Check if NPC is disabled/busy
                if (plugin.npcManager.isNPCDisabled(npc)) {
                    player.sendError("<yellow>$npcName</yellow> is busy.")
                    return true
                }

                val existingConversation = plugin.conversationManager.getConversation(npc) ?: run {
                    // No existing conversation, create a new one
                    val npcs = ArrayList<NPC>()
                    npcs.add(npc)
                    plugin.conversationManager.startConversation(player, npcs)
                    return true
                }

                existingConversation.addPlayer(player)
                return true
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error handling conversation: ${e.message}")
            player.sendError("An error occurred while processing the conversation.")
            return false
        }
    }

}