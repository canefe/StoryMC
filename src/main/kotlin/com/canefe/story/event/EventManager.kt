package com.canefe.story.event

import com.canefe.story.Story
import org.bukkit.Bukkit
import org.bukkit.event.Listener

class EventManager private constructor(
    private val plugin: Story,
) {
    private val listeners = mutableListOf<Listener>()

    fun registerEvents() {
        registerListener(PlayerEventListener(plugin))

        // Plugin integration listeners
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            registerListener(NPCInteractionListener(plugin))
            plugin.logger.info("Citizens detected, NPCInteractionListener registered")
        } else {
            plugin.logger.info("Citizens not detected, skipping NPCInteractionListener registration")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("ReviveMe")) {
            registerListener(ReviveMeEventListener(plugin))
            plugin.logger.info("ReviveMe detected, ReviveMeEventListener registered")
        } else {
            plugin.logger.info("ReviveMe not detected, skipping ReviveMeEventListener registration")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("BetterHealthBar")) {
            registerListener(HealthBarListener())
            HealthBarListener().onEnable()
            plugin.logger.info("HealthBar detected, HealthBarListener registered")
        } else {
            plugin.logger.info("HealthBar not detected, skipping HealthBarListener registration")
        }

        plugin.logger.info("Registered ${listeners.size} event listeners")
    }

    private fun registerListener(listener: Listener) {
        plugin.server.pluginManager.registerEvents(listener, plugin)
        listeners.add(listener)
    }

    fun unregisterAll() {
        listeners.clear()
    }

    companion object {
        private var instance: EventManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): EventManager =
            instance ?: synchronized(this) {
                instance ?: EventManager(plugin).also { instance = it }
            }
    }
}
