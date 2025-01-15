package com.canefe.story;

import com.google.gson.Gson;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.canefe.story.Story.ConversationMessage;

public class ConversationManager {

    private static ConversationManager instance;
    private final Story plugin; // Reference to the main plugin class
    private final Gson gson = new Gson(); // JSON utility
    private final NPCContextGenerator npcContextGenerator;
    private boolean isRadiantEnabled = true;

    // Active group conversations (UUID -> GroupConversation)
    private final List<GroupConversation> activeConversations = new ArrayList<>();

    private final Map<String, Integer> hologramTasks = new HashMap<>();

    private ConversationManager(Story plugin) {
        this.plugin = plugin;
        this.npcContextGenerator = NPCContextGenerator.getInstance(plugin);
    }

    public static ConversationManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new ConversationManager(plugin);
        }
        return instance;
    }


    public void setRadiantEnabled(boolean enabled) {
        if (enabled) {
            isRadiantEnabled = true;
        } else {
            isRadiantEnabled = false;
        }
    }

    public boolean isRadiantEnabled() {
        return isRadiantEnabled;
    }


    // Start a new group conversation
    public GroupConversation startGroupConversation(Player player, List<String> npcNames) {
        UUID playerUUID = player.getUniqueId();

        // End any existing conversation GroupConversation.players (list of UUIDs)
        GroupConversation existingConversation = activeConversations.stream()
                .filter(conversation -> conversation.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        if (existingConversation != null && existingConversation.isActive()) {
            endConversation(player);
        }

        // Initialize a new conversation with the provided NPCs
        List <UUID> players = new ArrayList<>();
        players.add(playerUUID);
        GroupConversation newConversation = new GroupConversation(players, npcNames);
        activeConversations.add(newConversation);

        player.sendMessage(ChatColor.GRAY + "You started a conversation with: " + String.join(", ", npcNames));
        return newConversation;
    }

    // Start radiant conversation between two NPCs, no players involved
    public GroupConversation startRadiantConversation(List<String> npcNames) {
        // Initialize a new conversation with the provided NPCs

        // Don't start if npc is already in a conversation
        for (String npcName : npcNames) {
            if (isNPCInConversation(npcName)) {
                return null;
            }
        }
        List <UUID> players = new ArrayList<>();
        GroupConversation newConversation = new GroupConversation(players, npcNames);
        activeConversations.add(newConversation);

        generateRadiantResponses(newConversation);

        return newConversation;
    }

    public void endRadiantConversation(GroupConversation conversation) {


        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);
            activeConversations.remove(conversation);
        }

    }
    public void generateRadiantResponses(GroupConversation conversation) {
        List<String> npcNames = conversation.getNpcNames();
        List<ConversationMessage> conversationHistory = conversation.getConversationHistory();

        // Color codes list for npcs ( each npc has a different color )
        Map<String, String> colorCodes = new HashMap<>();
        colorCodes.put("untaken1", "#599B45");
        colorCodes.put("untaken2", "#51be6f");
        colorCodes.put("untaken3", "#5E93D1");
        colorCodes.put("untaken4", "#8A6DAD");
        colorCodes.put("untaken5", "#FE92DE");
        colorCodes.put("untaken6", "#BD2C19");

        if (npcNames.size() == 1) {
             endRadiantConversation(conversation);
            return;
        }

        if (!conversation.isActive())
        {
            return;
        }

        // Process NPC responses one by one with a delay
        for (int i = 0; i < npcNames.size(); i++) {
            String npcName = npcNames.get(i);
            int delay = i * 3; // 6 seconds delay for each NPC (3 seconds for "is thinking" + 3 seconds for response)

            // Schedule the "is thinking" message after the delay
            int finalI = i;
            int finalI1 = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getNPCPos(npcName).thenAccept(npcPos -> {
                    if (npcPos == null) {
                        plugin.getLogger().warning("Failed to get position for NPC: " + npcName);
                        return;
                    }

                    // Show "is thinking" hologram
                    showThinkingHolo(npcName);

                    // Schedule the NPC response after another 3-second delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                // Fetch NPC data dynamically
                                FileConfiguration npcData = plugin.getNPCData(npcName);
                                String npcRole = npcData.getString("role", "Default role");
                                String existingContext = npcData.getString("context", null);
                                String location = npcData.getString("location", "Village");

                                StoryLocation storyLocation = plugin.locationManager.getLocation(location);

                                // Add dynamic world context
                                SeasonsAPI seasonsAPI = SeasonsAPI.getInstance();
                                Season season = seasonsAPI.getSeason(Bukkit.getWorld("world")); // Replace "world" with actual world name
                                int hours = seasonsAPI.getHours(Bukkit.getWorld("world"));
                                int minutes = seasonsAPI.getMinutes(Bukkit.getWorld("world"));
                                Date date = seasonsAPI.getDate(Bukkit.getWorld("world"));

                                // Update or generate context
                                if (existingContext != null) {
                                    existingContext = npcContextGenerator.updateContext(existingContext, npcName, hours, minutes, season.toString(), date.toString(true));
                                } else {
                                    existingContext = npcContextGenerator.generateDefaultContext(npcName, npcRole, hours, minutes, season.toString(), date.toString(true));
                                }

                                // Add context to the conversation history
                                List<ConversationMessage> npcConversationHistory = plugin.getMessages(npcData);
                                if (npcConversationHistory.isEmpty()) {
                                    npcConversationHistory.add(new ConversationMessage("system", existingContext));
                                } else if (!Objects.equals(npcConversationHistory.get(0).getContent(), existingContext)) {
                                    npcConversationHistory.set(0, new ConversationMessage("system", existingContext));
                                }

                                plugin.saveNPCData(npcName, npcRole, existingContext, npcConversationHistory, location);

                                // Prepare temp history
                                List<ConversationMessage> tempHistory = new ArrayList<>(conversation.getConversationHistory());

                                tempHistory.addFirst(new ConversationMessage("system",
                                        "You are " + npcName + " in a with " + String.join(", ", npcNames) + ". Conversation can be about anything like about day or recent events. Don't make it go waste by asking questions."));
                                plugin.getGeneralContexts().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));
                                if (storyLocation != null)
                                    storyLocation.getContext().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));

                                List<ConversationMessage> lastTwentyMessages = npcConversationHistory.subList(
                                        Math.max(npcConversationHistory.size() - 20, 0), npcConversationHistory.size());
                                lastTwentyMessages.add(0, npcConversationHistory.get(0));

                                tempHistory.addAll(0, lastTwentyMessages);

                                // Request AI response
                                String aiResponse = plugin.getAIResponse(tempHistory);

                                DHAPI.removeHologram(plugin.getNPCUUID(npcName).toString());

                                Integer taskId = hologramTasks.get(npcName);
                                if (taskId != null) {
                                    Bukkit.getScheduler().cancelTask(taskId);
                                    hologramTasks.remove(npcName);
                                }

                                if (aiResponse == null || aiResponse.isEmpty()) {
                                    plugin.getLogger().warning("Failed to generate NPC response for " + npcName);
                                    return;
                                }

                                // Add NPC response to conversation
                                conversation.addMessage(new ConversationMessage("assistant", npcName + ": " + aiResponse));
// get the next npc name
                                if (finalI1 + 1 < npcNames.size()) {
                                    conversation.addMessage(new ConversationMessage("user", npcNames.get(finalI1 + 1) + " listens"));
                                } else {
                                    conversation.addMessage(new ConversationMessage("user", npcNames.getFirst() + " listens"));
                                }

                                if (!colorCodes.containsKey(npcName)) {
                                    // get the next color code that is not 'untaken' as key
                                    Map.Entry<String, String> colorEntry = colorCodes.entrySet().stream()
                                            .filter(entry -> entry.getKey().contains("untaken"))
                                            .findFirst()
                                            .orElse(null);
                                    if (colorEntry != null) {
                                        // set the key to the npc name (change existing key)
                                        colorCodes.put(npcName, colorEntry.getValue());
                                        colorCodes.remove(colorEntry.getKey());
                                    }
                                }

                                // Broadcast NPC response
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.broadcastNPCMessage(aiResponse, npcName, false, null, null, null, colorCodes.get(npcName));
                                });

                                summarizeConversation(conversation.getConversationHistory(), conversation.getNpcNames(), null);

                                endRadiantConversation(conversation);

                            } catch (Exception e) {
                                plugin.getLogger().warning("Error while generating response for NPC: " + npcName);
                                e.printStackTrace();
                            }
                        });
                    }, 3 * 20L); // 3 seconds delay for the response
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("Error retrieving NPC position asynchronously: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
            }, delay * 20L); // Convert seconds to ticks (1 second = 20 ticks)
        }
    }

    // Add NPC to an existing conversation
    public void addNPCToConversation(Player player, String npcName) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.addNPC(npcName)) {
            player.sendMessage(ChatColor.GRAY + npcName + " has joined the conversation.");
            conversation.addMessage(new ConversationMessage("system", npcName + " has joined to the conversation."));
        } else {
            player.sendMessage(ChatColor.YELLOW + npcName + " is already part of the conversation.");
        }
    }

    public void removeNPCFromConversation(Player player, String npcName, boolean anyNearbyNPC) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.getNpcNames().size() != 1 && conversation.removeNPC(npcName)) {
            player.sendMessage(ChatColor.GRAY + npcName + " has left the conversation.");

            // If only one npc leaves, summarize the conversation for them
            conversation.addMessage(new ConversationMessage("system", npcName + " has left the conversation."));
            summarizeForSingleNPC(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName(), npcName);



        }
        else if (conversation.getNpcNames().size() == 1 && !anyNearbyNPC) {
            endConversation(player);
        } else if (conversation.getNpcNames().size() == 1 && anyNearbyNPC) {
            player.sendMessage(ChatColor.GRAY + npcName + " has left the conversation.");
            conversation.addMessage(new ConversationMessage("system", npcName + " has left the conversation."));
            summarizeForSingleNPC(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName(), npcName);
            conversation.removeNPC(npcName);
        }
        else {
            player.sendMessage(ChatColor.YELLOW + npcName + " is not part of the conversation.");
        }
    }

    // End a conversation
    public void endConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);

            // Summarize the conversation
            summarizeConversation(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName());

            applyEffects(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName());

            activeConversations.remove(conversation);
            player.sendMessage(ChatColor.GRAY + "The conversation has ended.");
        } else {
            activeConversations.removeIf(convo -> !convo.isActive());
        }
    }

    // Add a player's message to the group conversation
    public void addPlayerMessage(Player player, String message, boolean chatEnabled) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        String playerName = player.getName();

        if (EssentialsUtils.getNickname(playerName) != null) {
            playerName = EssentialsUtils.getNickname(playerName);
        }

        // Add the player's message to the conversation history
        conversation.addMessage(new ConversationMessage("user", playerName + ": " + message));

        if (!chatEnabled) {
            return;
        }
        // Generate responses from all NPCs sequentially
        generateGroupNPCResponses(conversation, player);
    }

    public Map<String, Integer> getHologramTasks() {
        return hologramTasks;
    }

    public void addHologramTask(String npcName, int taskId) {
        hologramTasks.put(npcName, taskId);
    }

    public void removeHologramTask(String npcName) {
        hologramTasks.remove(npcName);
    }

    public boolean isNPCInConversation(Player player, String npcName) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        return conversation != null && conversation.isActive() && conversation.getNpcNames().contains(npcName);
    }

    public boolean isNPCInConversation(String npcName) {
        for (GroupConversation conversation : activeConversations) {
            if (conversation.getNpcNames().contains(npcName)) {
                return true;
            }
        }
        return false;
    }

    public boolean addPlayerToConversation(Player player, String npcName) {
        // Join the player to another player's conversation
        for (GroupConversation conversation : activeConversations) {
            if (conversation.getNpcNames().contains(npcName)) {
                if (conversation.addPlayerToConversation(player)) {
                    player.sendMessage(ChatColor.GRAY + "You joined the conversation with " + npcName);
                    return true;
                } else {
                    player.sendMessage(ChatColor.YELLOW + "You are already part of the conversation with " + npcName);
                    return false;
                }
            }
        }
        return false;
    }

    public boolean isPlayerInConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        return conversation != null && conversation.isActive();
    }

    public void addNPCMessage(String npcName, String message) {
        for (GroupConversation conversation : activeConversations) {
            if (conversation.getNpcNames().contains(npcName)) {
                conversation.addMessage(new ConversationMessage("assistant", npcName + ": " + message));
                return;
            }
        }
    }

    // return all npc names in conversation
    public List<String> getNPCNamesInConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        return conversation != null ? conversation.getNpcNames() : new ArrayList<>();
    }

    public List<String> getAllParticipantsInConversation(Player player) {
        // get both all npc names and player names in conversation
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        List<String> npcNames = conversation != null ? conversation.getNpcNames() : new ArrayList<>();
        List<String> playerNames = conversation != null ? conversation.getPlayers().stream()
                .map(uuid -> {
                    Player playerIns = Bukkit.getPlayer(uuid);
                    return playerIns != null ? EssentialsUtils.getNickname(playerIns.getName()) : "";
                })
                .collect(Collectors.toList()) : new ArrayList<>();
        npcNames.addAll(playerNames);
        return npcNames;
    }

    public GroupConversation getActiveNPCConversation(String npcName) {
        for (GroupConversation conversation : activeConversations) {
            if (conversation.getNpcNames().contains(npcName)) {
                return conversation;
            }
        }
        return null;
    }

    // Generate NPC responses sequentially for the group conversation
    public void generateGroupNPCResponses(GroupConversation conversation, Player player) {
        List<String> npcNames = conversation.getNpcNames();
        List<ConversationMessage> conversationHistory = conversation.getConversationHistory();

        // Color codes list for npcs ( each npc has a different color )
        Map<String, String> colorCodes = new HashMap<>();
        colorCodes.put("untaken1", "#599B45");
        colorCodes.put("untaken2", "#51be6f");
        colorCodes.put("untaken3", "#5E93D1");
        colorCodes.put("untaken4", "#8A6DAD");
        colorCodes.put("untaken5", "#FE92DE");
        colorCodes.put("untaken6", "#BD2C19");

        // loop through the npc names, remove disabled npcs
        Iterator<String> iterator = npcNames.iterator();
        while (iterator.hasNext()) {
            String npcName = iterator.next();
            if (plugin.isNPCDisabled(npcName)) {
                iterator.remove();
                conversation.removeNPC(npcName);
            }
        }

        // Process NPC responses one by one with a delay
        for (int i = 0; i < npcNames.size(); i++) {
            String npcName = npcNames.get(i);
            int delay = i * 3; // 6 seconds delay for each NPC (3 seconds for "is thinking" + 3 seconds for response)

            // Schedule the "is thinking" message after the delay
            int finalI = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getNPCPos(npcName).thenAccept(npcPos -> {
                    if (npcPos == null) {
                        plugin.getLogger().warning("Failed to get position for NPC: " + npcName);
                        return;
                    }

                    // Show "is thinking" hologram
                    showThinkingHolo(npcName);

                    // Schedule the NPC response after another 3-second delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                // Fetch NPC data dynamically
                                FileConfiguration npcData = plugin.getNPCData(npcName);
                                String npcRole = npcData.getString("role", "Default role");
                                String existingContext = npcData.getString("context", null);
                                String location = npcData.getString("location", "Village");
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
                                    existingContext = npcContextGenerator.updateContext(existingContext, npcName, hours, minutes, season.toString(), date.toString(true));
                                } else {
                                    existingContext = npcContextGenerator.generateDefaultContext(npcName, npcRole, hours, minutes, season.toString(), date.toString(true));
                                }

                                // Add context to the conversation history
                                List<ConversationMessage> npcConversationHistory = plugin.getMessages(npcData);
                                if (npcConversationHistory.isEmpty()) {
                                    npcConversationHistory.add(new ConversationMessage("system", existingContext));
                                } else if (!Objects.equals(npcConversationHistory.get(0).getContent(), existingContext)) {
                                    npcConversationHistory.set(0, new ConversationMessage("system", existingContext));
                                }

                                plugin.saveNPCData(npcName, npcRole, existingContext, npcConversationHistory, location);

                                // Prepare temp history
                                List<ConversationMessage> tempHistory = new ArrayList<>(conversation.getConversationHistory());
                                List<String> playerNames = conversation.getPlayers().stream()
                                        .map(uuid -> {
                                            Player playerIns = Bukkit.getPlayer(uuid);
                                            return playerIns != null ? EssentialsUtils.getNickname(playerIns.getName()) : "";
                                        })
                                        .collect(Collectors.toList());

                                tempHistory.addFirst(new ConversationMessage("system",
                                        "You are " + npcName + " in a group conversation with " + String.join(", ", npcNames) + " , " + String.join(", ", playerNames) + "."));
                                plugin.getGeneralContexts().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));
                                if (storyLocation != null)
                                    storyLocation.getContext().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));

                                List<ConversationMessage> lastTwentyMessages = npcConversationHistory.subList(
                                        Math.max(npcConversationHistory.size() - 20, 0), npcConversationHistory.size());
                                tempHistory.add(0, new ConversationMessage("system", "Relations: " + relations.toString()));
                                tempHistory.add(0, new ConversationMessage("system", "Your responses should reflect your relations with the other characters if applicable. Never print out the relation as dialogue."));
                                lastTwentyMessages.add(0, npcConversationHistory.get(0));

                                tempHistory.addAll(0, lastTwentyMessages);


                                // Request AI response
                                String aiResponse = plugin.getAIResponse(tempHistory);

                                DHAPI.removeHologram(plugin.getNPCUUID(npcName).toString());

                                Integer taskId = hologramTasks.get(npcName);
                                if (taskId != null) {
                                    Bukkit.getScheduler().cancelTask(taskId);
                                    hologramTasks.remove(npcName);
                                }

                                if (aiResponse == null || aiResponse.isEmpty()) {
                                    plugin.getLogger().warning("Failed to generate NPC response for " + npcName);
                                    return;
                                }



                                // Add NPC response to conversation
                                conversation.addMessage(new ConversationMessage("assistant", npcName + ": " + aiResponse));
                                conversation.addMessage(new ConversationMessage("user", EssentialsUtils.getNickname(player.getName()) + " *remains silent*"));

                                if (aiResponse.contains("[End]")) {
                                    endConversation(player);
                                }

                                if (!colorCodes.containsKey(npcName)) {
                                    // get the next color code that is not 'untaken' as key
                                    Map.Entry<String, String> colorEntry = colorCodes.entrySet().stream()
                                            .filter(entry -> entry.getKey().contains("untaken"))
                                            .findFirst()
                                            .orElse(null);
                                    if (colorEntry != null) {
                                        // set the key to the npc name (change existing key)
                                        colorCodes.put(npcName, colorEntry.getValue());
                                        colorCodes.remove(colorEntry.getKey());
                                    }
                                }

                                // Broadcast NPC response
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.broadcastNPCMessage(aiResponse, npcName, false, null, null, null, colorCodes.get(npcName));
                                });

                            } catch (Exception e) {
                                plugin.getLogger().warning("Error while generating response for NPC: " + npcName);
                                e.printStackTrace();
                            }
                        });
                    }, 3 * 20L); // 3 seconds delay for the response
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("Error retrieving NPC position asynchronously: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
            }, delay * 20L); // Convert seconds to ticks (1 second = 20 ticks)
        }
    }


    public void showThinkingHolo(String npcName) {
        plugin.getNPCPos(npcName).thenAccept(npcPos -> {
            if (npcPos != null) {
                npcPos.add(0, 2.10, 0);

                // Check if a hologram already exists and remove it
                Hologram holo = DHAPI.getHologram(plugin.getNPCUUID(npcName).toString());
                if (holo != null) {
                    DHAPI.removeHologram(plugin.getNPCUUID(npcName).toString());
                }

                // Create a new hologram
                holo = DHAPI.createHologram(plugin.getNPCUUID(npcName).toString(), npcPos);
                DHAPI.addHologramLine(holo, 0, "&7&othinking...");
                DHAPI.updateHologram(npcName);

                Hologram finalHolo = holo;

                // Schedule a task to keep the hologram updated with the NPC's position
                int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    plugin.getNPCPos(npcName).thenAccept(updatedPos -> {
                        if (updatedPos != null) {
                            updatedPos.add(0, 2.10, 0);
                            DHAPI.moveHologram(finalHolo, updatedPos);
                        }
                    }).exceptionally(ex -> {
                        plugin.getLogger().warning("Failed to update hologram position for NPC: " + npcName);
                        ex.printStackTrace();
                        return null;
                    });
                }, 0L, 5L).getTaskId();

                // Store the task ID for cancellation later
                hologramTasks.put(npcName, taskId);
            } else {
                plugin.getLogger().warning("Failed to find the position of NPC: " + npcName);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Error while fetching NPC position asynchronously for hologram: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }



    // Summarize the conversation history
    private void summarizeConversation(List<ConversationMessage> history, List<String> npcNames, String playerName) {
        if (history.isEmpty()) return;

        // Build the summary prompt
        StringBuilder prompt = new StringBuilder("Summarize this conversation between ");
        prompt.append(playerName).append(" and ").append(String.join(", ", npcNames)).append(".\n");
        for (ConversationMessage msg : history) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        // Generate summary using AI
        CompletableFuture.runAsync(() -> {
            try {
                String summary = plugin.getAIResponse(Collections.singletonList(
                        new ConversationMessage("system", prompt.toString())
                ));
                if (summary != null && !summary.isEmpty()) {
                    for (String npcName : npcNames) {
                        plugin.addSystemMessage(npcName, summary);
                    }
                } else {
                    plugin.getLogger().warning("Failed to summarize the conversation.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLogger().severe("Error occurred while summarizing the conversation.");
            }
        });
    }

    private void applyEffects(List<ConversationMessage> history, List<String> npcNames, String playerName){
        if (history.isEmpty()) return;

        // Build the summary prompt
        StringBuilder prompt = new StringBuilder("Apply effects of this conversation between ");
        prompt.append(playerName).append(" and ").append(String.join(", ", npcNames)).append(".\n");

        // instructions on how to give effects
        prompt.append("To apply effects, output the effects in the following format: \n");
        prompt.append("Character: <name> possible values: [name of the npc] \n");
        prompt.append("Effect: <effect name> possible values: [relation] \n");
        prompt.append("Target: <target name> possible values: ").append(playerName).append("\n");
        prompt.append("relation: -20, 20 (only change as much needed) \n");

        prompt.append("Example: \n");
        prompt.append("Conversation summarisation: Player helps NPC greatly, which gains trust. \n");
        prompt.append("Effect: relation Target: player Value: 10 \n");
        prompt.append("Here's the conversation, apply effects only if necessary: \n");

        for (ConversationMessage msg : history) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        // Generate summary using AI
        CompletableFuture.runAsync(() -> {
            try {
                for (String npcName : npcNames) {
                    String effectsOutput = plugin.getAIResponse(Collections.singletonList(
                            new ConversationMessage("system", prompt.toString())
                    ));
                    if (effectsOutput != null && !effectsOutput.isEmpty()) {
                        effectsOutputParser(npcName, effectsOutput);
                    } else {
                        plugin.getLogger().warning("Failed to apply effects of the conversation.");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLogger().severe("Error occurred while applying effects of the conversation.");
            }
        });



    }

    private void summarizeForSingleNPC(List<ConversationMessage> history, List<String> npcNames, String playerName, String npcName){
        if (history.isEmpty()) return;

        // Build the summary prompt
        StringBuilder prompt = new StringBuilder("Summarize this conversation between ");
        prompt.append(playerName).append(" and NPCs ").append(String.join(", ", npcNames)).append(".\n");
        for (ConversationMessage msg : history) {
            prompt.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        // Generate summary using AI
        CompletableFuture.runAsync(() -> {
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
                plugin.getLogger().severe("Error occurred while summarizing the conversation.");
            }
        });
    }

    private void effectsOutputParser(String npcName, String effectsOutput) {
        String[] lines = effectsOutput.split("\n");
        String effect = null;
        String target = null;
        String value = null;

        for (String line : lines) {
            if (line.startsWith("Effect:")) {
                effect = line.split(":")[1].trim();
            } else if (line.startsWith("Target:")) {
                target = line.split(":")[1].trim();
            } else if (line.startsWith("Value:")) {
                value = line.split(":")[1].trim();
            }

            if (effect != null && target != null && value != null) {
                switch (effect) {
                    case "relation":
                       // plugin.broadcastNPCMessage("Relation of " + target + " has changed to " + value, npcName, false, null, null, null, "#599B45");
                        plugin.saveNPCRelationValue(npcName, target, value);
                        break;
                    case "title":
                        plugin.broadcastNPCMessage("Title of " + target + " has changed to " + value, npcName, false, null, null, null, "#599B45");
                        break;
                    case "item":
                        plugin.broadcastNPCMessage("Item " + value + " has been given to " + target, npcName, false, null, null, null, "#599B45");
                        break;
                    default:
                        break;
                }
                effect = null;
                target = null;
                value = null;
            }
        }
    }

    // Check if a player has an active conversation
    public boolean hasActiveConversation(Player player) {
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(player.getUniqueId()))
                .findFirst().orElse(null);
        return conversation != null && conversation.isActive();
    }

    public GroupConversation getActiveConversation(Player player) {
        return activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(player.getUniqueId()))
                .findFirst().orElse(null);
    }
}
