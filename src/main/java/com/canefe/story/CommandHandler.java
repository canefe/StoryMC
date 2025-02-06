package com.canefe.story;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
// import ConversationMessage from Story.java
import com.canefe.story.Story.ConversationMessage;

import java.util.*;
import java.util.Arrays;

public class CommandHandler implements CommandExecutor {
    private final Story plugin;

    public CommandHandler(Story plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can execute this command.");
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "togglegpt":
                toggleGPT(player);
                break;

            case "aireload":
                reloadConfig(player);
                break;

            case "feednpc":
                feedNPCCommand(player, args);
                break;

            case "g":
                makeNPCSay(player, args);
                break;

            case "setcurq":
                if (args.length < 1) {
                    player.sendMessage(ChatColor.RED + "Usage: /setcurq <npc name>");
                    return true;
                }
                String npcName = args[0];

                player.sendMessage(ChatColor.GRAY + "Current NPC set to: " + npcName);
                break;

            case "endconv":
                plugin.conversationManager.endConversation(player);
                break;

            default:
                return false;
        }
        return true;
    }

    private void toggleGPT(Player player) {
        plugin.setChatEnabled(!plugin.isChatEnabled());
        if (plugin.isChatEnabled()) {
            player.sendMessage(ChatColor.GRAY + "Chat with NPCs enabled.");
        } else {
            player.sendMessage(ChatColor.GRAY + "Chat with NPCs disabled.");
        }
    }

    private void npcTalk(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /maketalk <npc name>");
            return;
        }

        String npcName = args[0];


        player.sendMessage(ChatColor.GRAY + "You made NPC '" + npcName + "' talk using AI.");

        // Fetch NPC conversation
        GroupConversation convo = plugin.conversationManager.getActiveNPCConversation(npcName);
        if (convo == null) {
            player.sendMessage(ChatColor.RED + "No active conversation found for NPC '" + npcName + "'.");
            return;
        }
        // get a random player name from the convo
        Player randomPlayer = Bukkit.getPlayer(convo.getPlayers().iterator().next());
        plugin.conversationManager.generateGroupNPCResponses(convo, randomPlayer);
    }

    private void reloadConfig(Player player) {
        plugin.reloadPluginConfig(player);
        player.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully.");
    }

    private void feedNPCCommand(Player player, String[] args) {
        // Ensure a system message is provided
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /feednpc <instruction>");
            return;
        }

        // Fetch the current NPC for the player
        UUID npcUUID = plugin.playerCurrentNPC.get(player.getUniqueId());
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUUID);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "You are not currently interacting with any NPC. Use /interactnpc first.");
            return;
        }

        // Combine the instruction message
        String systemMessage = String.join(" ", args);

        // Fetch NPC conversation history
        plugin.addSystemMessage(npc.getName(), systemMessage);

        // Notify the player
        player.sendMessage(ChatColor.GRAY + "Added system message to NPC '" + npc.getName() + "': " + systemMessage);
    }

    private void makeNPCSay(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /g <message>");
            return;
        }

        UUID npcUUID = plugin.playerCurrentNPC.get(player.getUniqueId());
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUUID);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "You are not currently interacting with any NPC. Use /interactnpc first.");
            return;
        }

        String npcName = npc.getName();

        String message = String.join(" ", args);

        // Fetch NPC conversation history
        plugin.conversationManager.addNPCMessage(npcName, message);

        // Fetch NPC position asynchronously

                plugin.conversationManager.showThinkingHolo(npc);




            // Schedule the message broadcast after a 3-second delay
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Remove hologram after the delay
                int taskIdToRemove = plugin.conversationManager.getHologramTasks().get(npcName);
                Bukkit.getScheduler().cancelTask(taskIdToRemove);
                plugin.conversationManager.removeHologramTask(npcName);
                DHAPI.removeHologram(plugin.getNPCUUID(npcName).toString());

                // Broadcast the NPC's message
                plugin.broadcastNPCMessage(message, npcName, false, null, null, null, "#599B45");
            }, 60L); // 3 seconds (60 ticks)
    }


}
