package com.canefe.story;

import org.bukkit.ChatColor;
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

            case "setnpcrole":
                setNPCRole(player, args);
                break;

            case "removenpcrole":
                removeNPCRole(player, args);
                break;

            case "resetconversation":
                resetConversation(player);
                break;

            case "maketalk":
                npcTalk(player, args);
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
                plugin.playerCurrentNPC.put(player.getUniqueId(), npcName);
                player.sendMessage(ChatColor.GRAY + "Current NPC set to: " + npcName);
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

    private void setNPCRole(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /setnpcrole <npc name> <role description>");
            return;
        }

        String npcName = args[0];
        String roleDescription = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        plugin.updateNpcRole(npcName, roleDescription);
        player.sendMessage(ChatColor.GRAY + "Role for NPC '" + npcName + "' set to: " + roleDescription);
    }

    private void removeNPCRole(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /removenpcrole <npc name>");
            return;
        }

        String npcName = args[0];
        if (plugin.removeNpcRole(npcName)) {
            player.sendMessage(ChatColor.GRAY + "Role for NPC '" + npcName + "' removed.");
        } else {
            player.sendMessage(ChatColor.RED + "NPC '" + npcName + "' does not have a role set.");
        }
    }

    private void resetConversation(Player player) {
        plugin.resetGlobalConversationHistory();
        player.sendMessage(ChatColor.GRAY + "Conversation history reset.");
    }

    private void npcTalk(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /maketalk <npc name>");
            return;
        }

        String npcName = plugin.conversationManager.getActiveNPC(player);

        // Generate the NPC response with no user message (purely AI-driven)
        plugin.conversationManager.addNPCMessage(npcName, args[0]);

        player.sendMessage(ChatColor.GRAY + "You made NPC '" + npcName + "' talk using AI.");
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
        String npcName = plugin.playerCurrentNPC.get(player.getUniqueId());
        if (npcName == null || npcName.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You are not currently interacting with any NPC. Use /interactnpc first.");
            return;
        }

        // Combine the instruction message
        String systemMessage = String.join(" ", args);

        // Fetch NPC conversation history
        plugin.addSystemMessage(npcName, systemMessage);

        // Notify the player
        player.sendMessage(ChatColor.GRAY + "Added system message to NPC '" + npcName + "': " + systemMessage);
    }

    private void makeNPCSay(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /g <message>");
            return;
        }

        String npcName = plugin.playerCurrentNPC.get(player.getUniqueId());
        String message = String.join(" ", args);

        // Fetch NPC conversation history
        plugin.addNPCMessage(npcName, message);
    }

}
