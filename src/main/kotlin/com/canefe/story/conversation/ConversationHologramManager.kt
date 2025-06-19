package com.canefe.story.conversation

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils.isPluginEnabled
import eu.decentsoftware.holograms.api.DHAPI
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.*
import kotlin.random.Random

class ConversationHologramManager(private val plugin: Story) {
	private val hologramTasks: MutableMap<String, Int> = HashMap()

	private val activeConversations: List<Conversation>
		get() = plugin.conversationManager.activeConversations

	fun showListeningHolo(npc: NPC, isThinking: Boolean = false) {
		val npcName = npc.name

		if (!npc.isSpawned || npc.entity == null) return

		// Register NPC as being in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npc, true)

		if (isPluginEnabled("DecentHolograms")) {
			try {
				val npcPos: Location =
					npc.entity.location
						.clone()
						.add(0.0, 2.10, 0.0)
				val npcUUID = npc.uniqueId.toString()

				// Create and configure hologram
				createOrUpdateHologram(npcUUID, npcPos, isThinking)

				// Just set up position updates
				setupHologramPositionUpdates(npc)
			} catch (e: Exception) {
				plugin.logger.warning(
					"Error while showing ${if (isThinking) "thinking" else "listening"} hologram: ${e.message}",
				)
			}
		}
	}

	fun showListeningHolo(entity: Entity, isThinking: Boolean = false) {
		if (!isPluginEnabled("DecentHolograms")) {
			return
		}
		val isNPC = CitizensAPI.getNPCRegistry().isNPC(entity)
		try {
			val entityPos: Location =
				entity.location
					.clone()
					.add(0.0, 2.10, 0.0)
			val entityUUID =
				if (isNPC) {
					CitizensAPI
						.getNPCRegistry()
						.getNPC(entity)
						.uniqueId
						.toString()
				} else {
					entity.uniqueId.toString()
				}
			createOrUpdateHologram(entityUUID, entityPos, isThinking)

			setupHologramPositionUpdates(entity)
		} catch (e: Exception) {
			plugin.logger.warning(
				"Error while showing ${if (isThinking) "thinking" else "listening"} hologram: ${e.message}",
			)
		}
	}

	fun showThinkingHolo(npc: NPC) {
		showListeningHolo(npc, true)
	}

	fun showThinkingHolo(entity: Entity) {
		showListeningHolo(entity, true)
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
			val listeningStates =
				arrayOf(
					"&7&olistening...",
					"&7&owatching...",
					"&7&onodding...",
					"&7&othinking...",
					"&7&ofocusing...",
					"&7&owaiting...",
					"&7&oblinking...",
					"&7&otilting head...",
					"&7&osilent...",
					"&7&omurmuring...",
					"&7&obreathing calmly...",
					"&7&oleaning in...",
					"&7&oglancing around...",
					"&7&oshowing interest...",
					"&7&oraising eyebrows...",
					"&7&onarrowing eyes slightly...",
				)

			val chosenState = listeningStates[Random.nextInt(listeningStates.size)]
			DHAPI.addHologramLine(holo, 0, chosenState)
		}
		DHAPI.updateHologram(npcUUID)
	}

	private fun setupHologramPositionUpdates(entity: Entity) {
		cancelExistingTask(entity.name)
		val uniqueId =
			if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
				CitizensAPI
					.getNPCRegistry()
					.getNPC(entity)
					.uniqueId
					.toString()
			} else {
				entity.uniqueId.toString()
			}
		val taskId =
			Bukkit
				.getScheduler()
				.runTaskTimer(
					plugin,
					Runnable {
						if (!entity.isInWorld) {
							cleanupNPC(entity)
							return@Runnable
						}

						// Update hologram position
						val updatedPos =
							entity.location
								.clone()
								.add(0.0, 2.10, 0.0)
						if (DHAPI.getHologram(uniqueId) == null) {
							return@Runnable
						}
						DHAPI.moveHologram(DHAPI.getHologram(uniqueId), updatedPos)
					},
					0L,
					5L,
				).taskId

		hologramTasks[entity.name] = taskId
	}

	private fun setupHologramPositionUpdates(npc: NPC) {
		val npcName = npc.name
		val npcUUID = npc.uniqueId.toString()

		cancelExistingTask(npcName)

		val taskId =
			Bukkit
				.getScheduler()
				.runTaskTimer(
					plugin,
					Runnable {
						if (!npc.isSpawned || npc.entity == null || !plugin.conversationManager.isInConversation(npc)) {
							cleanupNPC(npc)
							return@Runnable
						}

						// Update hologram position
						val updatedPos =
							npc.entity.location
								.clone()
								.add(0.0, 2.10, 0.0)
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

	fun cleanupNPC(entity: Entity) {
		val uniqueId =
			if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
				CitizensAPI
					.getNPCRegistry()
					.getNPC(entity)
					.uniqueId
					.toString()
			} else {
				entity.uniqueId.toString()
			}

		DHAPI.removeHologram(uniqueId)

		cancelExistingTask(entity.name)
	}

	private fun cleanupNPC(npc: NPC) {
		val npcName = npc.name
		val npcUUID = npc.uniqueId.toString()
		// Remove the hologram
		DHAPI.removeHologram(npcUUID)

		// Cancel the task
		cancelExistingTask(npcName)

		// Mark NPC as no longer in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npc, false)
	}

	fun cleanupNPCHologram(npc: NPC?) {
		if (npc == null) return
		val npcName = npc.name
		val taskIdToRemove = hologramTasks[npcName]
		Bukkit.getScheduler().cancelTask(taskIdToRemove ?: -1)
		DHAPI.removeHologram(npc.uniqueId.toString())

		// Mark NPC as no longer in conversation
		plugin.npcBehaviorManager.setNPCInConversation(npc, false)
	}

	fun cleanupNPCHologram(entity: Entity?) {
		if (entity == null) return
		val uniqueId =
			if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
				CitizensAPI
					.getNPCRegistry()
					.getNPC(entity)
					.uniqueId
					.toString()
			} else {
				entity.uniqueId.toString()
			}
		val entityName = entity.name
		val taskIdToRemove = hologramTasks[entityName]
		Bukkit.getScheduler().cancelTask(taskIdToRemove ?: -1)
		DHAPI.removeHologram(uniqueId)
	}

	fun addHologramTask(npcName: String, taskId: Int) {
		hologramTasks[npcName] = taskId
	}

	fun removeHologramTask(npcName: String) {
		hologramTasks.remove(npcName)
	}
}
