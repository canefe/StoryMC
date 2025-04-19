package com.canefe.story.event

import com.canefe.story.Story
import org.bukkit.event.Listener

class EventManager private constructor(private val plugin: Story) {
	private val listeners = mutableListOf<Listener>()

	fun registerEvents() {
		// Create and register all listeners
		registerListener(NPCInteractionListener(plugin))
		registerListener(PlayerEventListener(plugin))
		registerListener(HealthBarListener())

		plugin.logger.info("Registered ${listeners.size} event listeners")
	}

	private fun registerListener(listener: Listener) {
		plugin.server.pluginManager.registerEvents(listener, plugin)
		listeners.add(listener)
	}

	fun unregisterAll() {
		// Most events don't need explicit unregistering in Bukkit,
		// but if you need to do cleanup, you can add that here
		listeners.clear()
	}

	companion object {
		private var instance: EventManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): EventManager {
			return instance ?: synchronized(this) {
				instance ?: EventManager(plugin).also { instance = it }
			}
		}
	}
}
