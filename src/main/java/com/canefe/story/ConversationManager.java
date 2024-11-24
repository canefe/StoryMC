package com.canefe.story;

import com.google.gson.Gson;
import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.canefe.story.Story.ConversationMessage;

public class ConversationManager {

    private final Story plugin; // Reference to the main plugin class
    private final Gson gson = new Gson(); // JSON utility

    // Store active conversation states (UUID -> ConversationState)
    private final Map<UUID, ConversationState> playerConversations = new HashMap<>();

    // NPC conversations stored by name
    private final Map<String, List<ConversationMessage>> npcConversations = new HashMap<>();

    public ConversationManager(Story plugin) {
        this.plugin = plugin;
    }

    // Start a new conversation
    public void startConversation(Player player, String npcName) {
        UUID playerUUID = player.getUniqueId();

        // End any existing conversation
        ConversationState existingState = playerConversations.get(playerUUID);
        if (existingState != null && existingState.isActive()) {
            endConversation(player);
        }

        // Initialize a new conversation
        playerConversations.put(playerUUID, new ConversationState(npcName, true));
    }

    // End a conversation
    public void endConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        ConversationState state = playerConversations.get(playerUUID);

        if (state != null && state.isActive()) {
            String npcName = state.getNpcName();
            List<ConversationMessage> history = npcConversations.getOrDefault(npcName, new ArrayList<>());

            // Summarize the conversation
            //Check if 'user' has any messages
            boolean hasUserMessages = history.stream().anyMatch(msg -> msg.getRole().equals("user"));
            if (hasUserMessages) {
                summarizeConversation(history, npcName, player.getName());
            }
            plugin.broadcastNPCMessage("Farewell, " + player.getName() + "!", npcName, false, null, null, null);
            // Update state
            state.setActive(false);
        } else {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
        }
    }

    // Add a player's message to the conversation
    public void addPlayerMessage(Player player, String message) {
        UUID playerUUID = player.getUniqueId();
        ConversationState state = playerConversations.get(playerUUID);

        if (state == null || !state.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        String npcName = state.getNpcName();
        List<ConversationMessage> history = npcConversations.computeIfAbsent(npcName, k -> new ArrayList<>());

        // Add the player's message
        history.add(new ConversationMessage("user", player.getName() + ": " + message));

        npcConversations.put(npcName, history);
        // Generate NPC response
        generateNPCResponse(npcName, history, player);
    }

    public void addNPCMessage(String npcName, String message) {
        List<ConversationMessage> history = npcConversations.computeIfAbsent(npcName, k -> new ArrayList<>());
        history.add(new ConversationMessage("assistant", message));
        npcConversations.put(npcName, history);
    }

    // Generate NPC response
    private void generateNPCResponse(String npcName, List<ConversationMessage> history, Player player) {

        // Fetch NPC data dynamically from YAML
        FileConfiguration npcData = plugin.getNPCData(npcName);

        String npcRole = npcData.getString("role", "Default role");
        String existingContext = npcData.getString("context", null);

        // Build dynamic world/time info
        SeasonsAPI seasonsAPI = SeasonsAPI.getInstance();
        Season season = seasonsAPI.getSeason(Bukkit.getWorld("world")); // Replace "world" with actual world name
        int hours = seasonsAPI.getHours(Bukkit.getWorld("world"));
        int minutes = seasonsAPI.getMinutes(Bukkit.getWorld("world"));

        Date date = seasonsAPI.getDate(Bukkit.getWorld("world"));



        String playerName = player != null ? player.getName() : "Player";
        // Generate the full context dynamically
        // Generate the full context dynamically
        if (existingContext != null) {
            existingContext = NPCContextGenerator.updateContext(existingContext, npcName, hours, minutes, season.toString(), date.toString());
        } else {
            existingContext = NPCContextGenerator.generateDefaultContext(npcName, npcRole, hours, minutes, season.toString(), date.toString());
        }


        // Retrieve NPC conversation history or initialize if missing
        List<ConversationMessage> npcConversationHistory = new ArrayList<>();
        List<Map<?, ?>> historyList = npcData.getMapList("conversationHistory");
        for (Map<?, ?> map : historyList) {
            String role = (String) map.get("role");
            String content = (String) map.get("content");
            npcConversationHistory.add(new ConversationMessage(role, content));
        }

        if (npcConversationHistory.isEmpty() || !Objects.equals(npcConversationHistory.getFirst().getContent(), existingContext)) {
            npcConversationHistory.addFirst(new ConversationMessage("system", existingContext));
        }

        plugin.saveNPCData(npcName, npcRole, existingContext, npcConversationHistory);

        // Add general contexts (optional)
        List<ConversationMessage> tempHistory = new ArrayList<>(history);
        for (String context : plugin.getGeneralContexts()) {
            tempHistory.add(new ConversationMessage("system", context));
        }

        tempHistory.addAll(0, npcConversationHistory);

        // Request AI response
        CompletableFuture.runAsync(

                ()-> {
                try {
                    String aiResponse = plugin.getAIResponse(tempHistory);
                    if (aiResponse == null || aiResponse.isEmpty()) {
                        plugin.getLogger().warning("Failed to generate NPC response for " + npcName);
                        return;
                    }

                    // Process response
                    plugin.broadcastNPCMessage(aiResponse, npcName, false, null, null, null);

                    // Save assistant response
                    synchronized (history) {
                        history.add(new ConversationMessage("assistant", aiResponse));
                        npcConversations.put(npcName, history);
                        // Print npc conversation history
                        for (ConversationMessage msg : history) {
                            Bukkit.getLogger().info(msg.getRole() + ": " + msg.getContent());
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                    Bukkit.getLogger().severe("Error occurred while generating NPC response for " + npcName);
                }
                });
    }

    // Summarize conversation history
    private void summarizeConversation(List<ConversationMessage> history, String npcName, String playerName) {
        if (history.isEmpty()) return;

        // Build summary prompt
        StringBuilder prompt = new StringBuilder("You are tasked with summarizing the conversation history between " + npcName + "(the assistant) and " + playerName +"(the user). This conversations take place in Minecraft Medieval universe.\n");
        for (ConversationMessage msg : history) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        Bukkit.getLogger().info(prompt.toString());

        // Generate summary using AI
        CompletableFuture.runAsync(
                ()-> {
                    try {
                        String summary = plugin.getAIResponse(Collections.singletonList(
                                new ConversationMessage("system", prompt.toString())
                        ));
                        if (summary != null && !summary.isEmpty()) {
                            plugin.addSystemMessage(npcName, summary);
                        } else {
                            plugin.getLogger().warning("Failed to summarize the conversation.");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Bukkit.getLogger().severe("Error occurred while summarizing conversation for " + npcName);
                    }
                });


    }

    // Check if a player has an active conversation
    public boolean hasActiveConversation(Player player) {
        ConversationState state = playerConversations.get(player.getUniqueId());
        return state != null && state.isActive();
    }

    // Get active NPC for a player
    public String getActiveNPC(Player player) {
        ConversationState state = playerConversations.get(player.getUniqueId());
        return (state != null && state.isActive()) ? state.getNpcName() : null;
    }
}

