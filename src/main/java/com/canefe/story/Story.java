package com.canefe.story;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import me.casperge.realisticseasons.season.Season;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.mcmonkey.sentinel.SentinelTrait;
import net.citizensnpcs.api.CitizensAPI;
import me.casperge.realisticseasons.api.SeasonsAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Story extends JavaPlugin implements Listener, CommandExecutor {

    // Toggle for NPC chat
    private boolean chatEnabled = true;

    // Map to store current NPC per player (UUID -> NPC Name)
    public Map<UUID, String> playerCurrentNPC = new HashMap<>();

    // Map to store NPC roles dynamically (NPC Name -> Role Description)
    private Map<String, String> npcRoles = new HashMap<>();

    // Map to store dynamic contexts for NPCs (NPC Name -> Context String)
    private Map<String, String> npcContexts = new HashMap<>();

    // Map to store conversation histories for NPCs (NPC Name -> List of Conversation Messages)
    private Map<String, List<ConversationMessage>> npcConversations = new HashMap<>();

    // Shared conversation history
    private List<ConversationMessage> conversationHistory = new ArrayList<>();

    private List<String> generalContexts = new ArrayList<>();

    // OpenAI API Key (Set this in your config.yml)
    private String openAIKey;

    private String defaultContext;

    private String aiModel;

    private String chatFormat;

    // Gson instance for JSON parsing
    private Gson gson = new Gson();

    private NPCDataManager npcDataManager;

    public ConversationManager conversationManager;

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info("AIStorymaker has been enableds!");

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Check if Sentinel and Citizens plugins are present
        if (Bukkit.getPluginManager().getPlugin("Sentinel") == null) {
            getLogger().warning("Sentinel plugin not found! NPC commands will not work.");
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().warning("Citizens plugin not found! NPC interactions will not work.");
        }

        // Register commands with the new CommandHandler
        CommandHandler commandHandler = new CommandHandler(this);
        getCommand("togglegpt").setExecutor(commandHandler);
        getCommand("setnpcrole").setExecutor(commandHandler);
        getCommand("removenpcrole").setExecutor(commandHandler);
        getCommand("resetconversation").setExecutor(commandHandler);
        getCommand("maketalk").setExecutor(commandHandler);
        getCommand("aireload").setExecutor(commandHandler);
        getCommand("feednpc").setExecutor(commandHandler);
        getCommand("g").setExecutor(commandHandler);



        npcDataManager = new NPCDataManager(this);
        loadAllNPCData();
        loadGeneralContexts();

        // Register the ConversationManager
        conversationManager = new ConversationManager(this);

        // Save default config and load OpenAI API key
        this.saveDefaultConfig();
        openAIKey = this.getConfig().getString("openai.apikey", "");
        defaultContext = this.getConfig().getString("ai.defaultContext", "Default context");
        aiModel = this.getConfig().getString("openai.aiModel", "gryphe/mythomax-l2-13b:extended");
        chatFormat = this.getConfig().getString("ai.chatFormat", "<gray>%npc_name% <italic>:</italic></gray> <white>%message%</white>");

        if (openAIKey.isEmpty()) {
            getLogger().warning("OpenAI API Key is not set in config.yml!");
        }
    }

    private void loadAllNPCData() {
        File[] files = npcDataManager.getNPCDirectory().listFiles(file -> file.isFile() && file.getName().toLowerCase().endsWith(".yml"));

        if (files != null) {
            for (File file : files) {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                String npcName = file.getName().replace(".yml", "");

                // Load role
                String role = config.getString("role", "No role defined");
                npcRoles.put(npcName, role);

                // Load context
                String context = config.getString("context", "Default context");
                npcContexts.put(npcName, context);

                // Load conversation history
                List<Map<?, ?>> historyList = config.getMapList("conversationHistory");
                List<ConversationMessage> conversationHistory = new ArrayList<>();
                for (Map<?, ?> map : historyList) {
                    String roleKey = (String) map.get("role");
                    String content = (String) map.get("content");
                    conversationHistory.add(new ConversationMessage(roleKey, content));
                }

                // Store the history in memory
                npcConversations.put(npcName, conversationHistory);
            }
        }
    }

    public void setChatEnabled(boolean enabled) {
        this.chatEnabled = enabled;
    }

    public boolean isChatEnabled() {
        return chatEnabled;
    }

    public void saveNPCData(String npcName, String roleDescription, String context, List<ConversationMessage> conversationHistory) {
        FileConfiguration config = npcDataManager.loadNPCData(npcName);

        // Save role and context
        config.set("role", roleDescription);
        config.set("context", context);

        // Save conversation history
        List<Map<String, String>> historyList = new ArrayList<>();
        for (ConversationMessage message : conversationHistory) {
            Map<String, String> map = new HashMap<>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            historyList.add(map);
        }
        config.set("conversationHistory", historyList);

        npcDataManager.saveNPCFile(npcName, config);
    }





    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("AIStorymaker has been disableds.");
    }

    public void reloadPluginConfig(Player player) {
        try {
            // Reload the config file
            this.reloadConfig();
            openAIKey = this.getConfig().getString("openai.apikey", "");

            // Any additional config values to reload can be added here
            player.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully.");

            if (openAIKey.isEmpty()) {
                getLogger().warning("OpenAI API Key is not set in the config.yml!");
                player.sendMessage(ChatColor.YELLOW + "Warning: OpenAI API Key is missing.");
            }

            defaultContext = this.getConfig().getString("api.defaultContext", "Default context");
            aiModel = this.getConfig().getString("openai.aiModel", "gryphe/mythomax-l2-13b:extended");
            chatFormat = this.getConfig().getString("api.chatFormat", "<gray>%npc_name% <italic>:</italic></gray> <white>%message%</white>");

            loadGeneralContexts();


        } catch (Exception e) {
            getLogger().severe("Failed to reload configuration: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to reload configuration. Check console for details.");
            e.printStackTrace();
        }
    }


    public void updateNpcRole(String npcName, String roleDescription) {
        npcRoles.put(npcName, roleDescription);
        saveNPCData(npcName, roleDescription, npcContexts.getOrDefault(npcName, defaultContext), npcConversations.getOrDefault(npcName, new ArrayList<>()));
    }

    public boolean removeNpcRole(String npcName) {
        return npcRoles.remove(npcName) != null;
    }

    public void resetGlobalConversationHistory() {
        conversationHistory.clear();
    }

    public void sendNpcMessage(String npcName, String message) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "c " + npcName + " " + message);
    }

    // Event: PlayerInteractEntityEvent
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // Ignore off-hand interactions
        }
        Player player = event.getPlayer();
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());

        if (npc == null) {
            return;
        }

        String npcName = npc.getName();
        UUID playerUUID = player.getUniqueId();

        // Set current NPC for the player
        playerCurrentNPC.put(playerUUID, npcName);
        if (conversationManager.hasActiveConversation(player)) {
            conversationManager.endConversation(player);
            player.sendMessage(ChatColor.RED + "You ended the conversation with " + npcName + ".");
        } else {
            conversationManager.startConversation(player, npcName);
            player.sendMessage(ChatColor.GRAY + "You are now talking to " + npcName + ".");
        }

        // If NPC has SentinelTrait, open a custom GUI for commands
        SentinelTrait sentinel = npc.getTrait(SentinelTrait.class);
        if (sentinel != null) {
            // Open GUI for NPC commands (e.g., Follow Me)
            // For simplicity, we'll skip GUI implementation in this example
            // You can integrate Inventory GUI handling here if needed
        }
    }

    // Event: AsyncPlayerChatEvent
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!chatEnabled) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String message = event.getMessage();

        String npcName = playerCurrentNPC.get(playerUUID);

        if (npcName == null || npcName.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You are not currently talking to any NPC. Please interact with an NPC first.");
            return;
        }

        // Find the NPC by name
        NPC npc = null;
        for (NPC currentNPC : CitizensAPI.getNPCRegistry()) {
            if (currentNPC.getName().equals(npcName)) {
                npc = currentNPC;
                break;
            }
        }

        if (npc == null) {
            player.sendMessage(ChatColor.RED + "NPC '" + npcName + "' does not exist.");
            playerCurrentNPC.remove(playerUUID);
            return;
        }

        //generateNPCResponse(npcName, message, player.getName());

        if (conversationManager.hasActiveConversation(player)) {
            conversationManager.addPlayerMessage(player, message);
        } else {
            conversationManager.startConversation(player, npcName);
            conversationManager.addPlayerMessage(player, message);
        }
    }

    public void broadcastNPCMessage(String aiResponse, String npcName, boolean shouldFollow, NPC finalNpc, UUID playerUUID, Player player) {
        MiniMessage mm = MiniMessage.miniMessage();

// Define a regex pattern to find words between * *
        Pattern pattern = Pattern.compile("\\*(.*?)\\*");

// Replace instances of *content* with <gray><italic>content</italic></gray>
        Matcher matcher = pattern.matcher(aiResponse);
        String formattedResponse = matcher.replaceAll("<gray><italic>$1</italic></gray>");

// Parse the formatted response with MiniMessage
        Component parsedResponse = mm.deserialize(chatFormat
                .replace("%npc_name%", npcName)
                .replace("%message%", formattedResponse));

        // Dispatch the formatted response to players and apply follow logic
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.getServer().sendMessage(parsedResponse);

            if (shouldFollow && finalNpc.hasTrait(SentinelTrait.class) && playerUUID != null && player != null) {
                SentinelTrait sentinel = finalNpc.getTrait(SentinelTrait.class);
                sentinel.setGuarding(playerUUID);
                player.sendMessage(ChatColor.GRAY + npcName + " is now following you.");
            }
        });
    }

    public void addUserMessage(String npcName, String playerName, String message) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("user", playerName + ": " + message));
        saveNPCConversationHistory(npcName, npcConversationHistory);
    }

    public void addUserMessageToHistory(List<ConversationMessage> messageHistory, String playerName, String message) {
        messageHistory.add(new ConversationMessage("user", playerName + ": " + message));
    }

    public List<String> getGeneralContexts() {
        return generalContexts;
    }

    private void loadGeneralContexts() {
        File file = new File(getDataFolder(), "general.yml");
        if (!file.exists()) {
            saveResource("general.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        generalContexts = config.getStringList("contexts");
        Bukkit.getLogger().info("Loaded " + generalContexts.size() + " general contexts.");
        if (generalContexts == null) {
            generalContexts = new ArrayList<>();
        }
    }

    public List<ConversationMessage> getMessages(FileConfiguration npcData) {
        List<ConversationMessage> npcConversationHistory = new ArrayList<>();
        List<Map<?, ?>> historyList = npcData.getMapList("conversationHistory");
        for (Map<?, ?> map : historyList) {
            String role = (String) map.get("role");
            String content = (String) map.get("content");
            npcConversationHistory.add(new ConversationMessage(role, content));
        }
        return npcConversationHistory;
    }

    public void addSystemMessage(String npcName, String systemMessage) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("system", systemMessage));
        saveNPCConversationHistory(npcName, npcConversationHistory);
    }

    public void addNPCMessage(String npcName, String npcMessage) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("assistant", npcMessage));
        saveNPCConversationHistory(npcName, npcConversationHistory);
        broadcastNPCMessage(npcMessage, npcName, false, null, null, null);

    }


    // Fetch NPC data dynamically from YAML
    public FileConfiguration getNPCData(String npcName) {
        return npcDataManager.loadNPCData(npcName);
    }

    // Save NPC conversation history back to YAML
    public void saveNPCConversationHistory(String npcName, List<ConversationMessage> conversationHistory) {
        FileConfiguration config = npcDataManager.loadNPCData(npcName);

        List<Map<String, String>> historyList = new ArrayList<>();
        for (ConversationMessage message : conversationHistory) {
            Map<String, String> map = new HashMap<>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            historyList.add(map);
        }

        config.set("conversationHistory", historyList);
        npcDataManager.saveNPCFile(npcName, config);
    }




    // Event: PlayerQuitEvent
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        playerCurrentNPC.remove(playerUUID);
    }

    // Method to interact with OpenAI API and get AI response
    public String getAIResponse(List<ConversationMessage> conversation) {
        if (openAIKey.isEmpty()) {
            getLogger().warning("OpenAI API Key is not set!");
            return null;
        }

        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + openAIKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Build JSON payload
            JsonObject json = new JsonObject();
            json.addProperty("model", aiModel);
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("role", "user");
            messageObj.addProperty("content", buildConversationContext(conversation));
            json.add("messages", gson.toJsonTree(conversation));

            String payload = gson.toJson(json);

            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                getLogger().warning("OpenAI API responded with code: " + responseCode);
                return null;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
            StringBuilder responseBuilder = new StringBuilder();
            String responseLine;

            while ((responseLine = br.readLine()) != null) {
                responseBuilder.append(responseLine.trim());
            }

            String response = responseBuilder.toString();

            // Parse JSON response
            JsonObject responseJson = gson.fromJson(response, JsonObject.class);
            getLogger().info("OpenAI response: " + responseJson);
            String aiMessage = responseJson
                    .getAsJsonArray("choices")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content")
                    .getAsString()
                    .trim();

            return aiMessage;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Helper method to build conversation context
    private String buildConversationContext(List<ConversationMessage> conversation) {
        StringBuilder contextBuilder = new StringBuilder();
        for (ConversationMessage msg : conversation) {
            contextBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return contextBuilder.toString();
    }

    // Inner class to represent conversation messages
    public static class ConversationMessage {
        private String role;
        private String content;

        public ConversationMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
