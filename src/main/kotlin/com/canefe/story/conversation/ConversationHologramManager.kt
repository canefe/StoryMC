package com.canefe.story.conversation

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils.isPluginEnabled
import eu.decentsoftware.holograms.api.DHAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.*
import kotlin.random.Random

class ConversationHologramManager(private val plugin: Story) {
	private val hologramTasks: MutableMap<String, Int> = HashMap()

	private val activeConversations: List<Conversation>
		get() = plugin.conversationManager.activeConversations

	fun showListeningHolo(
		npc: NPC,
		isThinking: Boolean = false,
	) {
		val npcName = npc.name

		if (!npc.isSpawned || npc.entity == null) return

		// Register NPC as being in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npcName, true)

		if (isPluginEnabled("DecentHolograms")) {
			try {
				val npcPos: Location = npc.entity.location.clone().add(0.0, 2.10, 0.0)
				val npcUUID = npc.uniqueId.toString()

				// Create and configure hologram
				createOrUpdateHologram(npcUUID, npcPos, isThinking)

				// Just set up position updates
				setupHologramPositionUpdates(npc, npcName, npcUUID)
			} catch (e: Exception) {
				plugin.logger.warning(
					"Error while showing ${if (isThinking) "thinking" else "listening"} hologram: ${e.message}",
				)
			}
		}
	}

	fun showThinkingHolo(npc: NPC) {
		showListeningHolo(npc, true)
	}

	// Helper methods to modularize logic
	private fun createOrUpdateHologram(
		npcUUID: String,
		position: Location,
		isThinking: Boolean,
	) {
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

	private fun setupHologramPositionUpdates(
		npc: NPC,
		npcName: String,
		npcUUID: String,
	) {
		cancelExistingTask(npcName)

		val taskId =
			Bukkit.getScheduler().runTaskTimer(
				plugin,
				Runnable {
					if (!npc.isSpawned || npc.entity == null || !plugin.conversationManager.isInConversation(npc)) {
						cleanupNPC(npc, npcName, npcUUID)
						return@Runnable
					}

					// Update hologram position
					val updatedPos = npc.entity.location.clone().add(0.0, 2.10, 0.0)
					DHAPI.moveHologram(DHAPI.getHologram(npcUUID), updatedPos)
				},
				0L,
				5L,
			).taskId

		hologramTasks[npcName] = taskId
	}

	private fun cancelExistingTask(npcName: String) {
		val existingTaskId = hologramTasks[npcName]
		if (existingTaskId != null) {
			Bukkit.getScheduler().cancelTask(existingTaskId)
			hologramTasks.remove(npcName)
		}
	}

	private fun cleanupNPC(
		npc: NPC,
		npcName: String,
		npcUUID: String,
	) {
		// Remove the hologram
		DHAPI.removeHologram(npcUUID)

		// Cancel the task
		cancelExistingTask(npcName)

		// Mark NPC as no longer in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npcName, false)
	}

	fun cleanupNPCHologram(npc: NPC?) {
		if (npc == null) return
		val npcName = npc.name
		val taskIdToRemove = hologramTasks[npcName]
		Bukkit.getScheduler().cancelTask(taskIdToRemove ?: -1)
		DHAPI.removeHologram(npc.uniqueId.toString())

		// Mark NPC as no longer in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npcName, false)
	}

	fun addHologramTask(
		npcName: String,
		taskId: Int,
	) {
		hologramTasks[npcName] = taskId
	}

	fun removeHologramTask(npcName: String) {
		hologramTasks.remove(npcName)
	}
}
