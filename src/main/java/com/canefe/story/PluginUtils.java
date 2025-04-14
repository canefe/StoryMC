package com.canefe.story;

import org.bukkit.Bukkit;

/**
 * Utility methods for plugin-related operations
 */
public class PluginUtils {

    /**
     * Checks if a plugin is enabled
     *
     * @param pluginName The name of the plugin to check
     * @return true if the plugin is enabled, false otherwise
     */
    public static boolean isPluginEnabled(String pluginName) {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null &&
                Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }
}