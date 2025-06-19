package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils
import eu.decentsoftware.holograms.api.DHAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.compareTo
import kotlin.text.compareTo
import kotlin.times
import kotlin.toString
import kotlin.unaryMinus

/**
 * Manages typing sessions for NPCs with typewriter-style dialogue
 * that dynamically updates for all viewers without chat spam
 */
class TypingSessionManager(
	private val plugin: Story,
) {
	private val activeSessions = ConcurrentHashMap<UUID, TypingSession>()
	private var taskId: Int = -1

	/**
	 * Represents a single typing animation session for an NPC
	 */
	inner class TypingSession(
		val npc: NPC,
		val fullText: String,
		val typingSpeed: Int = 4, // Characters per tick
		val location: Location = npc.entity.location,
	) {
		var currentPosition: Int = 0
		val isComplete: Boolean get() = currentPosition >= fullText.length

		/**
		 * Gets the current partial text that should be displayed
		 */
		fun getCurrentText(): String = fullText.substring(0, currentPosition)

		/**
		 * Advances the typing by one step
		 * @return true if typing is complete after this tick
		 */
		fun tick(): Boolean {
			if (isComplete) return true

			// Advance by typingSpeed characters
			currentPosition = (currentPosition + typingSpeed).coerceAtMost(fullText.length)

			// Update all nearby players with the new text
			updateViewers()

			return isComplete
		}

		/**
		 * Updates all players in radius with the current typing state using multi-line holograms
		 */
		fun updateViewers() {
			val currentText = getCurrentText()

			plugin.npcMessageService.broadcastNPCStreamMessage(currentText, npc)
		}
	}

	init {
		// Start the global ticker when the manager is created
		startTicker()
	}

	/**
	 * Starts a new typing session for an NPC
	 * If a session already exists for this NPC, it will be replaced
	 */
	fun startTyping(
		npc: NPC,
		fullText: String,
		messageFormat: String = "<npc_text>",
		typingSpeed: Int = 2,
		radius: Double = 30.0,
	): TypingSession {
		// clear hologram if it exists
		try {
			if (PluginUtils.isPluginEnabled("DecentHolograms")) {
				val npcUUID = npc.uniqueId.toString()
				DHAPI.removeHologram(npcUUID)
			}
		} catch (e: Exception) {
			plugin.logger.warning("Error clearing existing hologram: ${e.message}")
		}

		val location =
			plugin.disguiseManager
				.isNPCBeingImpersonated(npc)
				?.let { plugin.disguiseManager.getDisguisedPlayer(npc)?.location }
				?: npc.entity.location

		if (location == null) {
			plugin.logger.warning("NPC location is null, cannot start typing session.")
			return TypingSession(npc, fullText)
		}

		val session =
			TypingSession(
				npc = npc,
				fullText = fullText,
				typingSpeed = typingSpeed,
				location = location,
			)

		activeSessions[npc.uniqueId] = session

		// Ensure ticker is running
		if (taskId == -1) {
			startTicker()
		}

		return session
	}

	/**
	 * Gets an active typing session for an NPC, if one exists
	 */
	fun getSession(npcId: UUID): TypingSession? = activeSessions[npcId]

	/**
	 * Stops the typing session for an NPC if one exists
	 */
	fun stopTyping(npcId: UUID) {
		val session = activeSessions.remove(npcId) ?: return

		// Clean up the hologram (wait 3 seconds before removing)
		Bukkit.getScheduler().runTaskLater(
			plugin,
			Runnable {
				try {
					if (PluginUtils.isPluginEnabled("DecentHolograms")) {
						val npcUUID = session.npc.uniqueId.toString()
						DHAPI.removeHologram(npcUUID)
					}
				} catch (e: Exception) {
					plugin.logger.warning("Error removing NPC typing hologram: ${e.message}")
				}
			},
			60L,
		)

		// If no more sessions, stop the ticker
		if (activeSessions.isEmpty()) {
			stopTicker()
		}
	}

	/**
	 * Cleans up all resources - call this when plugin disables
	 */
	fun shutdown() {
		// Clean up all holograms
		activeSessions.values.forEach { session ->
			try {
				if (PluginUtils.isPluginEnabled("DecentHolograms")) {
					val npcUUID = session.npc.uniqueId.toString()
					DHAPI.removeHologram(npcUUID)
				}
			} catch (e: Exception) {
				plugin.logger.warning("Error removing NPC typing hologram during shutdown: ${e.message}")
			}
		}

		stopTicker()
		activeSessions.clear()
	}

	/**
	 * Completes typing immediately, showing the full text
	 */
	fun completeTypingImmediately(npcId: UUID) {
		val session = activeSessions[npcId] ?: return
		session.currentPosition = session.fullText.length
		session.updateViewers()
	}

	/**
	 * Starts the global ticker that advances all typing sessions
	 */
	private fun startTicker() {
		if (taskId != -1) return

		taskId =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				// Make a copy to avoid concurrent modification issues
				val sessionsToTick = HashMap(activeSessions)

				// Tick each session and remove completed ones
				sessionsToTick.entries.forEach { (npcId, session) ->
					val complete = session.tick()
					if (complete) {
						activeSessions.remove(npcId)
					}
				}

				// If no more sessions, stop ticker
				if (activeSessions.isEmpty()) {
					stopTicker()
				}
			}, 0L, 2L) // Run every 2 ticks (1/10th of a second)
	}

	/**
	 * Stops the global ticker
	 */
	private fun stopTicker() {
		if (taskId != -1) {
			Bukkit.getScheduler().cancelTask(taskId)
			taskId = -1
		}
	}
}
