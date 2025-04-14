package com.canefe.story;

import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NPCUtils {

    // Cache for NPCs
    private final Map<String, NPC> npcCache = new ConcurrentHashMap<>();

    private Story plugin;

    // Private constructor to prevent instantiation
    private NPCUtils(Story plugin) {
        this.plugin = plugin;
    }

    private static final class InstanceHolder {
        // Single instance of the class
        private static NPCUtils instance;

        private static void initialize(Story plugin) {
            if (instance == null) {
                instance = new NPCUtils(plugin);
            }
        }
    }

    // Static method to get the single instance
    public static NPCUtils getInstance(Story plugin) {
        InstanceHolder.initialize(plugin);
        return InstanceHolder.instance;
    }

    // Asynchronous method to get an NPC by name, with caching
    public CompletableFuture<NPC> getNPCByNameAsync(String npcName) {
        return CompletableFuture.supplyAsync(() -> {
            // Check cache first
            if (npcCache.containsKey(npcName.toLowerCase())) {
                return npcCache.get(npcName.toLowerCase());
            }

            // Search NPC registry if not in cache
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (npc.getName().equalsIgnoreCase(npcName)) {
                    npcCache.put(npcName.toLowerCase(), npc);
                    return npc;
                }
            }

            return null; // NPC not found
        });
    }

    public static class NPCContext {
        public String npcName;
        public String npcRole;
        public String context;
        public Map<String, Integer> relations;
        public StoryLocation location;
        public String avatar;
        public List<Story.ConversationMessage> conversationHistory;

        public NPCContext(String npcName, String npcRole, String existingContext, Map<String, Integer> relations, StoryLocation storyLocation, String avatar, List<Story.ConversationMessage> npcConversationHistory) {
            this.npcName = npcName;
            this.npcRole = npcRole;
            this.context = existingContext;
            this.relations = relations;
            this.location = storyLocation;
            this.avatar = avatar;
            this.conversationHistory = npcConversationHistory;
        }

        public String getNpcName() {
            return npcName;
        }

        public String getNpcRole() {
            return npcRole;
        }

        public String getContext() {
            return context;
        }

        public Map<String, Integer> getRelations() {
            return relations;
        }

        public StoryLocation getLocation() {
            return location;
        }

        public String getAvatar() {
            return avatar;
        }

        public List<Story.ConversationMessage> getConversationHistory() {
            return conversationHistory;
        }
    }

    // GetOrCreateContextForNPC
    public NPCContext getOrCreateContextForNPC(String npcName) {
        try {
            // Fetch NPC data dynamically
            FileConfiguration npcData = plugin.getNPCData(npcName);
            String npcRole = npcData.getString("role", "Default role");
            String existingContext = npcData.getString("context", null);
            String location = npcData.getString("location", "Village");
            String avatar = npcData.getString("avatar", "");
            // Get list of relations for the NPC
            ConfigurationSection relationsSection = npcData.getConfigurationSection("relations");
            Map<String, Integer> relations = new HashMap<>();
            if (relationsSection != null) {
                for (String key : relationsSection.getKeys(false)) {
                    relations.put(key, relationsSection.getInt(key));
                }
            }

            StoryLocation storyLocation = plugin.locationManager.getLocation(location);

            // Add dynamic world context
            SeasonsAPI seasonsAPI = SeasonsAPI.getInstance();
            Season season = seasonsAPI.getSeason(Bukkit.getWorld("world")); // Replace "world" with actual world name
            int hours = seasonsAPI.getHours(Bukkit.getWorld("world"));
            int minutes = seasonsAPI.getMinutes(Bukkit.getWorld("world"));
            Date date = seasonsAPI.getDate(Bukkit.getWorld("world"));

            // Update or generate context
            if (existingContext != null) {
                existingContext = plugin.conversationManager.npcContextGenerator.updateContext(existingContext, npcName, hours, minutes, season.toString(), date.toString(true));
            } else {
                existingContext = plugin.conversationManager.npcContextGenerator.generateDefaultContext(npcName, npcRole, hours, minutes, season.toString(), date.toString(true));
            }

            // Add context to the conversation history
            List<Story.ConversationMessage> npcConversationHistory = plugin.getMessages(npcData);
            if (npcConversationHistory.isEmpty()) {
                npcConversationHistory.add(new Story.ConversationMessage("system", existingContext));
            } else if (!Objects.equals(npcConversationHistory.get(0).getContent(), existingContext)) {
                npcConversationHistory.set(0, new Story.ConversationMessage("system", existingContext));
            }

            plugin.saveNPCData(npcName, npcRole, existingContext, npcConversationHistory, location);
            return new NPCContext(npcName, npcRole, existingContext, relations, storyLocation, avatar, npcConversationHistory);
        }
        catch (Exception e) {
            Bukkit.getLogger().warning("Error while updating NPC context: " + e.getMessage());
            return null;
        }
    }

    // Optional: Clear the cache (e.g., on reload)
    public void clearCache() {
        npcCache.clear();
    }
}

