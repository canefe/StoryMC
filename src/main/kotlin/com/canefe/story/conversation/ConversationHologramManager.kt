package com.canefe.story.conversation

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils.isPluginEnabled
import eu.decentsoftware.holograms.api.DHAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.*
import kotlin.random.Random

class ConversationHologramManager(private val plugin: Story) {
    private val hologramTasks: MutableMap<String, Int> = HashMap()
    // Add missing fields
    private val npcLastLookTimes: MutableMap<String, Long> = HashMap()
    private val npcLookIntervals: MutableMap<String, Int> = HashMap()


    // activeConversations reference from plugin.conversationManager.activeConversations
    private val activeConversations: List<Conversation>
        get() = plugin.conversationManager.activeConversations

    // Define the method with a parameter to distinguish it from the other overload
    fun showListeningHolo(npc: NPC, isThinking: Boolean = false) {
        val npcName = npc.name

        if (!npc.isSpawned || npc.entity == null) return

        if (isPluginEnabled("DecentHolograms")) {
            try {
                val npcPos: Location = npc.entity.location.clone().add(0.0, 2.10, 0.0)
                val npcUUID = npc.uniqueId.toString()

                // Create and configure hologram
                createOrUpdateHologram(npcUUID, npcPos, isThinking)

                // Handle listening-specific logic if not in thinking mode
                if (!isThinking) {
                    handleListeningBehavior(npc, npcName, npcUUID)
                } else {
                    // For thinking mode, just set up position updates
                    setupHologramPositionUpdates(npc, npcName)
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error while showing ${if (isThinking) "thinking" else "listening"} hologram: ${e.message}")
            }
        }
    }

    // Use the new parameter to direct to the same method for thinking mode
    fun showThinkingHolo(npc: NPC) {
        showListeningHolo(npc, true)
    }

    // Helper methods to modularize logic
    private fun createOrUpdateHologram(npcUUID: String, position: Location, isThinking: Boolean) {
        // Remove any existing hologram
        var holo = DHAPI.getHologram(npcUUID)
        if (holo != null) DHAPI.removeHologram(npcUUID)

        // Create a new hologram
        holo = DHAPI.createHologram(npcUUID, position)

        if (isThinking) {
            DHAPI.addHologramLine(holo, 0, "&9&othinking...")
        } else {
            val listeningStates = arrayOf("&7&olistening...", "&7&owatching...", "&7&onodding...")
            val chosenState = listeningStates[Random.nextInt(listeningStates.size)]
            DHAPI.addHologramLine(holo, 0, chosenState)
        }
        DHAPI.updateHologram(npcUUID)
    }

    private fun handleListeningBehavior(npc: NPC, npcName: String, npcUUID: String) {
        val random = Random

        // Find the conversation this NPC is in
        var conversation: Conversation? = null
        for (conv in activeConversations) {
            if (conv.npcNames.contains(npcName)) {
                conversation = conv
                break
            }
        }

        // If NPC is not in any conversation, don't create the hologram task
        if (conversation == null) {
            DHAPI.removeHologram(npcUUID)
            return
        }

        // Set initial look time and interval if not already set
        var lastLookTime = npcLastLookTimes[npcName]
        if (lastLookTime == null) {
            lastLookTime = System.currentTimeMillis()
            npcLastLookTimes[npcName] = lastLookTime
        }

        // Store next look interval
        var lookInterval = npcLookIntervals[npcName]
        if (lookInterval == null) {
            lookInterval = random.nextInt(3000) + 2000 // 2-5 seconds
            npcLookIntervals[npcName] = lookInterval
        }

        // Cancel any existing task for this NPC
        cancelExistingTask(npcName)

        val finalConversation = conversation
        val taskId = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Check if NPC is still valid
            if (!npc.isSpawned || npc.entity == null || !isNPCInConversation(npcName)) {
                cleanupNPC(npc, npcName, npcUUID)
                return@Runnable
            }

            // Update hologram position
            val updatedPos = npc.entity.location.clone().add(0.0, 2.10, 0.0)
            DHAPI.moveHologram(DHAPI.getHologram(npcUUID), updatedPos)

            // Handle NPC looking behavior
            handleNPCLooking(npc, npcName, finalConversation, random)
        }, 0L, 5L).taskId

        hologramTasks[npcName] = taskId
    }

    private fun setupHologramPositionUpdates(npc: NPC, npcName: String) {
        cancelExistingTask(npcName)

        val npcUUID = npc.uniqueId.toString()
        val taskId = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!npc.isSpawned || npc.entity == null) {
                // Remove the hologram if the NPC is gone
                DHAPI.removeHologram(npcUUID)

                // Cancel the task
                val taskToCancel = hologramTasks[npcName]
                if (taskToCancel != null) {
                    Bukkit.getScheduler().cancelTask(taskToCancel)
                    hologramTasks.remove(npcName)
                }
                return@Runnable
            }

            // Update hologram position
            val updatedPos = npc.entity.location.clone().add(0.0, 2.10, 0.0)
            DHAPI.moveHologram(DHAPI.getHologram(npcUUID), updatedPos)
        }, 0L, 5L).taskId

        hologramTasks[npcName] = taskId
    }

    private fun handleNPCLooking(npc: NPC, npcName: String, conversation: Conversation?, random: Random) {
        val currentTime = System.currentTimeMillis()
        val storedLastLook = npcLastLookTimes[npcName] ?: return
        val storedInterval = npcLookIntervals[npcName] ?: return

        if (currentTime - storedLastLook > storedInterval && conversation != null) {
            // Reset the timer and set a new random interval
            npcLastLookTimes[npcName] = currentTime
            npcLookIntervals[npcName] = random.nextInt(3000) + 2000 // 2-5 seconds

            // Choose someone to look at
            val targets = mutableListOf<Any>()

            // Add only NPCs that are still in the conversation
            for (otherNpc in conversation.npcs) {
                if (otherNpc.isSpawned && otherNpc != npc &&
                    conversation.npcNames.contains(otherNpc.name)
                ) {
                    targets.add(otherNpc.entity)
                }
            }

            // Add only Players that are still in the conversation
            for (playerUUID in conversation.players) {
                val player = Bukkit.getPlayer(playerUUID)
                if (player != null && player.isOnline) {
                    targets.add(player)
                }
            }

            // Look at a random target if any are available
            if (targets.isNotEmpty() && random.nextInt(10) < 8) { // 80% chance to look at someone
                val target = targets[random.nextInt(targets.size)]
                if (target is Entity) {
                    // Set a slower head rotation speed for more natural movement
                    npc.navigator.defaultParameters.speedModifier(1f)
                    val rot = npc.getOrAddTrait(RotationTrait::class.java)
                    rot.physicalSession.rotateToFace(target)
                }
            }
        }
    }

    private fun cancelExistingTask(npcName: String) {
        val existingTaskId = hologramTasks[npcName]
        if (existingTaskId != null) {
            Bukkit.getScheduler().cancelTask(existingTaskId)
            hologramTasks.remove(npcName)
        }
    }

    private fun cleanupNPC(npc: NPC, npcName: String, npcUUID: String) {
        // Remove the hologram
        DHAPI.removeHologram(npcUUID)

        // Cancel the task
        cancelExistingTask(npcName)

        // Reset look traits if applicable
        if (npc.isSpawned && npc.hasTrait(LookClose::class.java)) {
            val lookTrait = npc.getTraitNullable(LookClose::class.java)
            lookTrait?.lookClose(false)
        }
    }

    // Additional helper method for checking if NPC is in conversation
    private fun isNPCInConversation(npcName: String): Boolean {
        for (conversation in activeConversations) {
            if (conversation.npcNames.contains(npcName)) {
                return true
            }
        }
        return false
    }

    fun cleanupNPCHologram(npc: NPC?) {
        if (npc == null) return
        val npcName = npc.name
        val taskIdToRemove = hologramTasks[npcName]
        Bukkit.getScheduler().cancelTask(taskIdToRemove ?: -1)
        DHAPI.removeHologram(plugin.npcUtils.getNPCUUID(npcName).toString())
    }

    fun addHologramTask(npcName: String, taskId: Int) {
        hologramTasks[npcName] = taskId
    }

    fun removeHologramTask(npcName: String) {
        hologramTasks.remove(npcName)
    }
}