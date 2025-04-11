package com.canefe.story;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class LoreBookManager {
    private final Story plugin;
    private final Map<String, LoreBook> loreBooks = new HashMap<>();
    private static LoreBookManager instance;
    private final File loreFolder;

    // Track recently added lore contexts by conversation to avoid duplicates
    private final Map<GroupConversation, Set<String>> recentlyAddedLoreContexts = new HashMap<>();
    private final Map<String, Set<String>> npcKnowledgeCategories = new HashMap<>();
    private final long CONTEXT_COOLDOWN_MS = 60000; // 1 minute cooldown

    public static LoreBookManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new LoreBookManager(plugin);
        }
        return instance;
    }

    private LoreBookManager(Story plugin) {
        this.plugin = plugin;
        this.loreFolder = new File(plugin.getDataFolder(), "lore");
        if (!loreFolder.exists()) {
            loreFolder.mkdirs();
        }
        loadConfig();

        // Schedule cleanup of old context entries
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupOldContextEntries, 1200L, 1200L); // Run every minute
    }

    public void loadConfig() {
        loadAllLoreBooks();
        loadNPCKnowledgeCategories();
    }

    private void cleanupOldContextEntries() {
        long now = System.currentTimeMillis();
        recentlyAddedLoreContexts.entrySet().removeIf(entry ->
                !entry.getKey().isActive() || entry.getValue().isEmpty());
    }

    // Method to load NPC knowledge categories
    public void loadNPCKnowledgeCategories() {
        npcKnowledgeCategories.clear();

        if (plugin.getNPCDataManager() == null) {
            plugin.getLogger().warning("Cannot load NPC knowledge categories: NPCDataManager is null");
            return;
        }

        // Default everyone knows "common" knowledge
        for (String npcName : plugin.getNPCDataManager().getAllNPCNames()) {
            Set<String> categories = new HashSet<>();
            categories.add("common");

            FileConfiguration npcData = plugin.getNPCData(npcName);
            List<String> configCategories = npcData.getStringList("knowledgeCategories");
            if (configCategories != null) {
                categories.addAll(configCategories);
            }

            npcKnowledgeCategories.put(npcName.toLowerCase(), categories);
        }
    }

    public void loadAllLoreBooks() {
        loreBooks.clear();
        File[] files = loreFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                String name = config.getString("name");
                String context = config.getString("context");
                List<String> keywords = config.getStringList("keywords");
                List<String> categoryList = config.getStringList("categories");
                Set<String> categories = new HashSet<>(categoryList.isEmpty() ?
                        Collections.singletonList("common") : categoryList);

                if (name != null && context != null && keywords != null && !keywords.isEmpty()) {
                    LoreBook loreBook = new LoreBook(name, context, keywords, categories);
                    loreBooks.put(name.toLowerCase(), loreBook);
                    plugin.getLogger().info("Loaded lorebook: " + name);
                } else {
                    plugin.getLogger().warning("Invalid lorebook format in file: " + file.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading lorebook from file: " + file.getName());
                e.printStackTrace();
            }
        }
        plugin.getLogger().info("Loaded " + loreBooks.size() + " lorebooks");
    }



    public List<LoreContext> findRelevantLoreContexts(String message, GroupConversation conversation) {
        List<LoreContext> relevantContexts = new ArrayList<>();
        String messageLower = message.toLowerCase();
        Set<String> addedLoreNames = new HashSet<>(); // Track lorebooks added in this call

        // Initialize the set for this conversation if needed
        recentlyAddedLoreContexts.putIfAbsent(conversation, new HashSet<>());
        Set<String> recentContexts = recentlyAddedLoreContexts.get(conversation);


        // Get all knowledge categories accessible in this conversation
        Set<String> conversationKnowledgeCategories = new HashSet<>();
        conversationKnowledgeCategories.add("common"); // Everyone knows common knowledge

        for (String npcName : conversation.getNpcNames()) {
            Set<String> npcCategories = npcKnowledgeCategories.get(npcName.toLowerCase());
            if (npcCategories != null) {
                conversationKnowledgeCategories.addAll(npcCategories);
            }
        }

        for (LoreBook loreBook : loreBooks.values()) {
            // Skip if this lorebook was recently added to this conversation
            if (recentContexts.contains(loreBook.getName())) {
                continue;
            }


            boolean knowledgeAccessible = false;
            for (String category : loreBook.getCategories()) {
                if (conversationKnowledgeCategories.contains(category)) {
                    knowledgeAccessible = true;
                    break;
                }
            }

            if (!knowledgeAccessible) {
                continue; // Skip if no NPC knows this category
            }

            for (String keyword : loreBook.getKeywords()) {
                if (messageLower.contains(keyword.toLowerCase())) {
                    if (addedLoreNames.add(loreBook.getName())) { // Only add if not already added in this call
                        relevantContexts.add(new LoreContext(loreBook.getName(), loreBook.getContext()));
                        recentContexts.add(loreBook.getName()); // Mark as recently added
                    }
                    break; // Only add each lorebook once even if multiple keywords match
                }
            }
        }

        // Schedule cleanup of this lorebook from recent contexts
        for (String loreName : addedLoreNames) {
            final String finalLoreName = loreName;
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (recentlyAddedLoreContexts.containsKey(conversation)) {
                    recentlyAddedLoreContexts.get(conversation).remove(finalLoreName);
                }
            }, CONTEXT_COOLDOWN_MS / 50); // Convert ms to ticks
        }

        return relevantContexts;
    }

    // Class to hold context information
    public static class LoreContext {
        private final String loreName;
        private final String context;

        public LoreContext(String loreName, String context) {
            this.loreName = loreName;
            this.context = context;
        }

        public String getLoreName() {
            return loreName;
        }

        public String getContext() {
            return context;
        }
    }

    // Class definition for LoreBook
    public class LoreBook {
        private final String name;
        private final String context;
        private final List<String> keywords;
        private final Set<String> categories;

        public LoreBook(String name, String context, List<String> keywords, Set<String> categories) {
            this.name = name;
            this.context = context;
            this.keywords = keywords;
            this.categories = categories != null ? categories : new HashSet<>(Collections.singletonList("common"));
        }

        public String getName() {
            return name;
        }

        public Set<String> getCategories() {
            return categories;
        }

        public String getContext() {
            return context;
        }

        public List<String> getKeywords() {
            return keywords;
        }
    }
}
