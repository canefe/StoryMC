package com.canefe.story;

import com.google.gson.Gson;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.canefe.story.Story.ConversationMessage;

public class ConversationManager {

    private static ConversationManager instance;
    private final Story plugin; // Reference to the main plugin class
    private final Gson gson = new Gson(); // JSON utility
    public final NPCContextGenerator npcContextGenerator;
    private boolean isRadiantEnabled = true;

    // Active group conversations (UUID -> GroupConversation)
    private final List<GroupConversation> activeConversations = new ArrayList<>();

    private final Map<String, Integer> hologramTasks = new HashMap<>();

    private final Map<GroupConversation, Integer> scheduledTasks = new HashMap<>();

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
    public GroupConversation startGroupConversation(Player player, List<NPC> npcs) {
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
        GroupConversation newConversation = new GroupConversation(players, npcs);
        activeConversations.add(newConversation);

        List <String> npcNames = new ArrayList<>();
        for (NPC npc : npcs) {
            npcNames.add(npc.getName());
        }

        player.sendMessage(ChatColor.GRAY + "You started a conversation with: " + String.join(", ", npcNames));
        return newConversation;
    }

    public void StopAllConversations() {
        for (GroupConversation conversation : activeConversations) {
            endConversation(conversation);
        }
    }

    public GroupConversation startGroupConversationNoPlayer(List<NPC> npcs) {
        // Initialize a new conversation with the provided NPCs
        List <UUID> players = new ArrayList<>();
        // if any one of the npc is already in a conversation, don't start
        for (NPC npc : npcs) {
            if (isNPCInConversation(npc.getName())) {
                return null;
            }
        }

        GroupConversation newConversation = new GroupConversation(players, npcs);
        activeConversations.add(newConversation);

        return newConversation;
    }

    // Start radiant conversation between two NPCs, no players involved
    public GroupConversation startRadiantConversation(List<NPC> npcs) {
        // Initialize a new conversation with the provided NPCs
        // Don't start if npc is already in a conversation
        for (NPC npc : npcs) {
            if (isNPCInConversation(npc.getName())) {
                return null;
            }
        }
        List <UUID> players = new ArrayList<>();

        GroupConversation newConversation = new GroupConversation(players, npcs);
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
        List<NPC> npcs = conversation.getNPCs();
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
                npcs.stream().filter(npc -> npc.getName().equals(npcName)).findFirst().ifPresent(npc -> {
                    // Show "is thinking" hologram
                    showThinkingHolo(npc);

                    // Schedule the NPC response after another 3-second delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                NPCUtils.NPCContext NPCContext = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(npcName);

                                // Prepare temp history
                                List<ConversationMessage> tempHistory = new ArrayList<>(conversation.getConversationHistory());

                                tempHistory.addFirst(new ConversationMessage("system",
                                        "You are " + npcName + " in a with " + String.join(", ", npcNames) + ". Conversation can be about anything like about day or recent events. Don't make it go waste by asking questions."));
                                plugin.getGeneralContexts().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));
                                if (NPCContext.location != null)
                                    NPCContext.location.getContext().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));


                                List<ConversationMessage> lastTwentyMessages = NPCContext.conversationHistory.subList(
                                        Math.max(NPCContext.conversationHistory.size() - 20, 0), NPCContext.conversationHistory.size());
                                tempHistory.addFirst(new ConversationMessage("system", "Relations: " + NPCContext.relations.toString()));
                                tempHistory.addFirst(new ConversationMessage("system", "Your responses should reflect your relations with the other characters if applicable. Never print out the relation as dialogue."));
                                lastTwentyMessages.addFirst(NPCContext.conversationHistory.get(0));

                                tempHistory.addAll(0, lastTwentyMessages);

                                // Request AI response
                                String aiResponse = plugin.getAIResponse(tempHistory);

                                DHAPI.removeHologram(npc.getUniqueId().toString());

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



                                endRadiantConversation(conversation);

                            } catch (Exception e) {
                                plugin.getLogger().warning("Error while generating response for NPC: " + npcName);
                                e.printStackTrace();
                            }
                        });
                    }, 3 * 20L); // 3 seconds delay for the response
                });
            }, delay * 20L); // Convert seconds to ticks (1 second = 20 ticks)
        }
    }

    // Add NPC to an existing conversation
    public void addNPCToConversation(Player player, NPC npc) {
        UUID playerUUID = player.getUniqueId();
        String npcName = npc.getName();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.addNPC(npc)) {
            player.sendMessage(ChatColor.GRAY + npcName + " has joined the conversation.");
            conversation.addMessage(new ConversationMessage("system", npcName + " has joined to the conversation."));
        } else {
            player.sendMessage(ChatColor.YELLOW + npcName + " is already part of the conversation.");
        }
    }

    public void removeNPCFromConversation(Player player, NPC npc, boolean anyNearbyNPC) {
        UUID playerUUID = player.getUniqueId();
        String npcName = npc.getName();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.getNpcNames().size() != 1 && conversation.removeNPC(npc)) {
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
            conversation.removeNPC(npc);
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

    public void endConversation(GroupConversation conversation) {
        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);
            summarizeConversation(conversation.getConversationHistory(), conversation.getNpcNames(), null);
            applyEffects(conversation.getConversationHistory(), conversation.getNpcNames(), null);
            activeConversations.remove(conversation);
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
                conversation.addMessage(new ConversationMessage("user", "*Rest are listening...*"));
                return;
            }
        }
    }

    // return all npc names in conversation
    public List<NPC> getNPCsInConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = activeConversations.stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);
        return conversation != null ? conversation.getNPCs() : new ArrayList<>();
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
        List<NPC> npcs = conversation.getNPCs();
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
                conversation.removeNPC(
                        Objects.requireNonNull(npcs.stream().filter(npc -> npc.getName().equals(npcName)).findFirst().orElse(null)));

            }
        }

        // Process NPC responses one by one with a delay
        for (int i = 0; i < npcNames.size(); i++) {
            String npcName = npcNames.get(i);
            int delay = i * 3; // 6 seconds delay for each NPC (3 seconds for "is thinking" + 3 seconds for response)

            // Schedule the "is thinking" message after the delay
            int finalI = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                npcs.stream().filter(npc -> npc.getName().equals(npcName)).findFirst().ifPresent(npc -> {

                    // Show "is thinking" hologram
                    showThinkingHolo(npc);

                    // Schedule the NPC response after another 3-second delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                // Fetch NPC data dynamically
                                NPCUtils.NPCContext NPCContext = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(npcName);

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
                                if (NPCContext.location != null)
                                    NPCContext.location.getContext().forEach(context -> tempHistory.addFirst(new ConversationMessage("system", context)));

                                List<ConversationMessage> lastTwentyMessages = NPCContext.conversationHistory.subList(
                                        Math.max(NPCContext.conversationHistory.size() - 20, 0), NPCContext.conversationHistory.size());
                                tempHistory.add(0, new ConversationMessage("system", "Relations: " + NPCContext.relations.toString()));
                                tempHistory.add(0, new ConversationMessage("system", "Your responses should reflect your relations with the other characters if applicable. Never print out the relation as dialogue."));
                                lastTwentyMessages.add(0, NPCContext.conversationHistory.get(0));

                                tempHistory.addAll(0, lastTwentyMessages);


                                // Request AI response
                                String aiResponse = plugin.getAIResponse(tempHistory);

                                DHAPI.removeHologram(npc.getUniqueId().toString());

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

                                //
                                if (player != null) {
                                    conversation.addMessage(new ConversationMessage("user", "..."));
                                } else {
                                    conversation.addMessage(new ConversationMessage("user", "..."));
                                }


                                if (aiResponse.contains("[End]")) {
                                    if (player != null) {
                                        endConversation(player);
                                    }
                                }
                                // sometimes other npcs speak in other npc's turn, so check the message using regex and get the npc name to check if its the same npc, then use the same color if so.
// Ensure every NPC has a color code assigned
                                Pattern npcNamePattern = Pattern.compile("^([\\w\\s']+):(?:\\s*\\1:)?");
                                Matcher matcher = npcNamePattern.matcher(aiResponse);
                                String finalNpcName;
                                if (matcher.find()) {
                                    String responseNpcName = matcher.group(1);
                                    if (!colorCodes.containsKey(responseNpcName)) {
                                        // get the next available color code
                                        Optional<Map.Entry<String, String>> colorEntry = colorCodes.entrySet().stream()
                                                .filter(entry -> entry.getKey().startsWith("untaken"))
                                                .findFirst();
                                        colorEntry.ifPresent(entry -> {
                                            colorCodes.put(responseNpcName, entry.getValue());
                                            colorCodes.remove(entry.getKey());
                                        });
                                    }
                                    finalNpcName = responseNpcName;
                                } else {
                                    if (!colorCodes.containsKey(npcName)) {
                                        // get the next available color code
                                        Optional<Map.Entry<String, String>> colorEntry = colorCodes.entrySet().stream()
                                                .filter(entry -> entry.getKey().startsWith("untaken"))
                                                .findFirst();
                                        colorEntry.ifPresent(entry -> {
                                            colorCodes.put(npcName, entry.getValue());
                                            colorCodes.remove(entry.getKey());
                                        });
                                    }
                                    finalNpcName = npcName;
                                }

// Ensure finalNpcName has a color code
                                String colorCode = colorCodes.get(finalNpcName);
                                if (colorCode != null) {
                                    // Broadcast NPC response
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        plugin.broadcastNPCMessage(aiResponse, npcName, false, null, null, null, colorCode);
                                    });
                                } else {
                                    plugin.getLogger().warning("Color code for NPC " + finalNpcName + " is null.");
                                }

                            } catch (Exception e) {
                                plugin.getLogger().warning("Error while generating response for NPC: " + npcName);
                                e.printStackTrace();
                            }
                        });
                    }, 3 * 20L); // 3 seconds delay for the response
                });
            }, delay * 20L); // Convert seconds to ticks (1 second = 20 ticks)
        }
    }


    public void showThinkingHolo(NPC npc) {
        String npcName = npc.getName();
        Location npcPos = npc.getEntity().getLocation();
        String npcUUID = npc.getUniqueId().toString();

        npcPos.add(0, 2.10, 0);

        // Check if a hologram already exists and remove it
        Hologram holo = DHAPI.getHologram(npcUUID);
        if (holo != null) {
            DHAPI.removeHologram(npcUUID);
        }

        // Create a new hologram
        holo = DHAPI.createHologram(npcUUID, npcPos);
        DHAPI.addHologramLine(holo, 0, "&7&othinking...");
        DHAPI.updateHologram(npcUUID);

        Hologram finalHolo = holo;

        // Schedule a task to keep the hologram updated with the NPC's position
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Location updatedPos = npc.getEntity().getLocation();
            updatedPos.add(0, 2.10, 0);
            DHAPI.moveHologram(finalHolo, updatedPos);
        }, 0L, 5L).getTaskId();

        // Store the task ID for cancellation later
        hologramTasks.put(npcName, taskId);
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
                        effectsOutputParser(effectsOutput);
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

    private void effectsOutputParser(String effectsOutput) {
        String[] lines = effectsOutput.split("\n");
        String npcName = null;
        String effect = null;
        String target = null;
        String value = null;

        for (String line : lines) {
            // Check for a new character block
            if (line.startsWith("Character:")) {
                npcName = line.split(":")[1].trim();
            } else if (line.startsWith("Effect:")) {
                effect = line.split(":")[1].trim();
            } else if (line.startsWith("Target:")) {
                target = line.split(":")[1].trim();
            } else if (line.startsWith("Value:")) {
                value = line.split(":")[1].trim();
            }

            // When all necessary variables are set, process the effect
            if (npcName != null && effect != null && target != null && value != null) {
                switch (effect) {
                    case "relation":
                        plugin.saveNPCRelationValue(npcName, target, value);
                        break;
                    case "title":
                        plugin.broadcastNPCMessage("Title of " + target + " has changed to " + value, npcName, false, null, null, null, "#599B45");
                        break;
                    case "item":
                        plugin.broadcastNPCMessage("Item " + value + " has been given to " + target, npcName, false, null, null, null, "#599B45");
                        break;
                    default:
                        System.out.println("Unknown effect: " + effect);
                        break;
                }
                // Reset effect-specific variables for the next effect
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
