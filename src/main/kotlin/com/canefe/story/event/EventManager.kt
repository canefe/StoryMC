package com.canefe.story.event

import com.canefe.story.Story
import org.bukkit.Bukkit
import org.bukkit.event.Listener

class EventManager private constructor(
    private val plugin: Story,
) {
    private val listeners = mutableListOf<Listener>()

    fun registerEvents() {
        // Create and register all listeners
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            registerListener(NPCInteractionListener(plugin))
            plugin.logger.info("Citizens detected, NPCInteractionListener registered")
        } else {
            plugin.logger.info("Citizens not detected, skipping NPCInteractionListener registration")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("ReviveMe")) {
            registerListener(PlayerEventListener(plugin))
            plugin.logger.info("ReviveMe detected, PlayerDownedListener registered")
        } else {
            plugin.logger.info("ReviveMe not detected, skipping PlayerDownedListener registration")
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
        // Most events don't need explicit unregistering in Bukkit,
        // but if you need to do cleanup, you can add that here
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
