package com.canefe.story;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.text.Text;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class NPCSpawner {
    private final Story plugin;

    public NPCSpawner(Story plugin) {
        this.plugin = plugin;
    }

    public void spawnNPC(String npcName, Location location, Player player, String message) {
        // Check if Citizens plugin is enabled
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            Bukkit.getLogger().warning("Citizens plugin is not enabled!");
            return;
        }

        // Create the NPC
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(org.bukkit.entity.EntityType.PLAYER, npcName);

        // Set the NPC location
        npc.spawn(location);

        // Add traits to the NPC (e.g., text, skin)
        npc.setName(npcName);
        npc.setProtected(true); // Prevent NPC from taking damage

        // Add a delay before calling eventGoToPlayerAndSay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!npc.isSpawned()) {
                Bukkit.getLogger().warning("NPC '" + npcName + "' failed to spawn!");
                return;
            }
            plugin.npcManager.eventGoToPlayerAndSay(npc, player.getName(), message);
        }, 5L); // 5 ticks delay (adjust as needed, 1 tick = 50ms)
    }

}
