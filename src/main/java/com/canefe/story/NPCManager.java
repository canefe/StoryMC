package com.canefe.story;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NPCManager {

    private static NPCManager instance; // Singleton instance
    private final Story plugin;
    private final Map<String, NPCData> npcDataMap = new HashMap<>(); // Centralized NPC storage

    private NPCManager(Story plugin) {
        // Private constructor to enforce singleton pattern
        this.plugin = plugin;
    }

    // Get the singleton instance
    public static synchronized NPCManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new NPCManager(plugin);
        }
        return instance;
    }

    public void eventGoToPlayerAndSay(String npcName, String playerName, String message) {
        // Asynchronously get the NPC
        plugin.npcUtils.getNPCByNameAsync(npcName).thenAccept(npc -> {
            if (npc == null) {
                Bukkit.getLogger().warning("NPC with name '" + npcName + "' not found!");
                return;
            }

            // Get the player by name
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                Bukkit.getLogger().warning("Player with name '" + playerName + "' is not online!");
                return;
            }

            // Switch back to the main thread for Citizens API calls
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Get the player's location
                Location playerLocation = player.getLocation();

                // Make the NPC navigate to the player
                Navigator navigator = npc.getNavigator();
                navigator.setTarget(playerLocation); // Set the target location
                navigator.getDefaultParameters().distanceMargin(2.0); // Stop 2 blocks away

                // Register event listener for navigation completion
                Bukkit.getPluginManager().registerEvents(new Listener() {
                    @EventHandler
                    public void onNavigationComplete(NavigationCompleteEvent event) {
                        if (event.getNPC().equals(npc)) {
                            // Unregister the listener after completion
                            NavigationCompleteEvent.getHandlerList().unregister(this);

                            // Start the conversation once NPC reaches the player
                            List<String> npcNames = new ArrayList<>();
                            npcNames.add(npcName);
                            GroupConversation conversation = plugin.conversationManager.startGroupConversation(player, npcNames);
                            // Send the message to the player
                            conversation.addMessage(new Story.ConversationMessage("assistant", npcName + ": " + message));
                            plugin.broadcastNPCMessage(message, npcName, false, npc, player.getUniqueId(), player, "cyan");
                        }
                    }
                }, plugin);
            });
        }).exceptionally(ex -> {
            Bukkit.getLogger().warning("An error occurred while fetching NPC '" + npcName + "': " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }

    public void walkToNPC(NPC npc, NPC targetNPC, String firstMessage) {

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Get the target NPC's location
            Location targetLocation = targetNPC.getEntity().getLocation();

            // Make the NPC navigate to the target NPC
            Navigator navigator = npc.getNavigator();
            navigator.setTarget(targetLocation); // Set the target location
            navigator.getDefaultParameters().distanceMargin(2.0); // Stop 2 blocks away

            // Register event listener for navigation completion
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onNavigationComplete(NavigationCompleteEvent event) {
                    if (event.getNPC().equals(npc)) {
                        // Unregister the listener after completion
                        NavigationCompleteEvent.getHandlerList().unregister(this);

                        // Start the conversation once NPC reaches the target NPC
                        List<String> npcNames = new ArrayList<>();
                        npcNames.add(npc.getName());
                        npcNames.add(targetNPC.getName());
                        GroupConversation conversation = plugin.conversationManager.startRadiantConversation(npcNames);
                        // Send the message to the player
                        conversation.addMessage(new Story.ConversationMessage("system", npc.getName() + ": " + firstMessage));
                        conversation.addMessage(new Story.ConversationMessage("user", targetNPC.getName() + " is listening..."));
                        plugin.broadcastNPCMessage(firstMessage, npc.getName(), false, npc, null, null, "#BD2C19");
                    }
                }
            }, plugin);
        });

    }

}
