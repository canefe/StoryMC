package com.canefe.story.util

import org.bukkit.Bukkit

/**
 * Utility methods for plugin-related operations
 */
object PluginUtils {
    /**
     * Checks if a plugin is enabled
     *
     * @param pluginName The name of the plugin to check
     * @return true if the plugin is enabled, false otherwise
     */
    @JvmStatic
    fun isPluginEnabled(pluginName: String?): Boolean =
        Bukkit.getPluginManager().getPlugin(pluginName!!) != null &&
            Bukkit.getPluginManager().isPluginEnabled(pluginName)
}
