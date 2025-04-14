package com.canefe.story;

import com.google.gson.Gson;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;

import java.util.concurrent.atomic.AtomicInteger;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.RotationTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.canefe.story.Story.ConversationMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ConversationManager {

    private static ConversationManager instance;
    private final Story plugin; // Reference to the main plugin class
    private final Gson gson = new Gson(); // JSON utility
    public final NPCContextGenerator npcContextGenerator;
    private LoreBookManager loreBookManager;
    private boolean isRadiantEnabled = true;

    // Active group conversations (UUID -> GroupConversation)
    private final Map<Integer, GroupConversation> activeConversations = new HashMap<>();

    private final Map<String, Integer> hologramTasks = new HashMap<>();

    public Map<String, Long> npcLastLookTimes;
    public Map<String, Integer> npcLookIntervals;

    public Map<GroupConversation, Integer> getScheduledTasks() {
        return scheduledTasks;
    }

    private final Map<GroupConversation, Integer> scheduledTasks = new HashMap<>();

    public ConversationManager(Story plugin) {
        this.plugin = plugin;
        this.npcContextGenerator = NPCContextGenerator.getInstance(plugin);
        this.loreBookManager = LoreBookManager.getInstance(plugin);
        npcLastLookTimes = new HashMap<>();
        npcLookIntervals = new HashMap<>();
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

    public Map<Integer, GroupConversation> getActiveConversations() {
        return activeConversations;
    }

    public GroupConversation getConversationById(Integer id) {
        return activeConversations.get(id);
    }

    private final AtomicInteger nextConversationId = new AtomicInteger(1);

    private void addConversation(GroupConversation conversation) {
        // Get next available ID (thread-safe)
        int newId = nextConversationId.getAndIncrement();
        activeConversations.put(newId, conversation);
    }

    private void removeConversation(GroupConversation conversation) {
        activeConversations.values().removeIf(convo -> convo.equals(conversation));
    }

    private GroupConversation getConversationByPlayer(Player player) {
        return activeConversations.values().stream()
                .filter(convo -> convo.getPlayers().contains(player.getUniqueId()))
                .findFirst().orElse(null);
    }

    public void reloadConfig() {
        loreBookManager.loadConfig();
    }

    // Start a new group conversation
    public GroupConversation startGroupConversation(Player player, List<NPC> npcs) {
        UUID playerUUID = player.getUniqueId();

        // End any existing conversation GroupConversation.players (list of UUIDs)
        // Check if player is in ANY active conversation first
        for (GroupConversation conversation : activeConversations.values()) {
            if (conversation.isActive() && conversation.getPlayers().contains(playerUUID)) {
                // Remove player from this conversation before starting a new one
                conversation.removePlayer(playerUUID);

                // If the conversation is now empty, clean it up
                if (conversation.getPlayers().isEmpty()) {
                    endConversation(conversation);
                }

                // No need to continue the loop, one player can only be in one conversation
                break;
            }
        }

        // Initialize a new conversation with the provided NPCs
        List <UUID> players = new ArrayList<>();
        players.add(playerUUID);
        GroupConversation newConversation = new GroupConversation(players, npcs);
        addConversation(newConversation);

        List <String> npcNames = new ArrayList<>();
        for (NPC npc : npcs) {
            npcNames.add(npc.getName());
        }

        player.sendMessage(ChatColor.GRAY + "You started a conversation with: " + String.join(", ", npcNames));
        return newConversation;
    }

    public void StopAllConversations() {
        // Create a copy of the conversations to avoid concurrent modification
        List<GroupConversation> conversationsToEnd = new ArrayList<>(activeConversations.values());

        // End each conversation in the copy
        for (GroupConversation conversation : conversationsToEnd) {
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
        addConversation(newConversation);

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
        addConversation(newConversation);

        generateRadiantResponses(newConversation);

        return newConversation;
    }

    public void endRadiantConversation(GroupConversation conversation) {


        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);
            removeConversation(conversation);
        }

    }
    public void generateRadiantResponses(GroupConversation conversation) {
        List<String> npcNames = conversation.getNpcNames();
        List<NPC> npcs = conversation.getNPCs();
        List<ConversationMessage> conversationHistory = conversation.getConversationHistory();

        if (npcNames.size() == 1) {
            endConversation(conversation);
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
                                if (NPCContext.location != null) {
                                    // Get all contexts including parent locations
                                    List<String> allLocContexts = plugin.getLocationManager().getAllContextForLocation(NPCContext.location.getName());
                                    allLocContexts.forEach(context ->
                                            tempHistory.addFirst(new ConversationMessage("system", context))
                                    );
                                }


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

                                // Broadcast NPC response
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.broadcastNPCMessage(aiResponse, npcName, false, npc, null, null, NPCContext.avatar, plugin.randomColor(npcName));
                                });



                                endConversation(conversation);

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
        GroupConversation conversation = getConversationByPlayer(player);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.addNPC(npc)) {
            player.sendMessage(ChatColor.GRAY + npcName + " has joined the conversation.");
            conversation.addMessage(new ConversationMessage("system", npcName + " has joined to the conversation."));
        }
    }

    public void removeNPCFromConversation(Player player, NPC npc, boolean anyNearbyNPC) {
        UUID playerUUID = player.getUniqueId();
        String npcName = npc.getName();
        GroupConversation conversation = activeConversations.values().stream()
                .filter(convo -> convo.getPlayers().contains(playerUUID))
                .findFirst().orElse(null);

        if (conversation == null || !conversation.isActive()) {
            player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
            return;
        }

        if (conversation.getNpcNames().size() != 1 && conversation.removeNPC(npc)) {
            // Clean up the NPC's hologram and facing behavior
            cleanupNPCHologram(npc);

            player.sendMessage(ChatColor.GRAY + npcName + " has left the conversation.");

            // If only one npc leaves, summarize the conversation for them
            conversation.addMessage(new ConversationMessage("system", npcName + " has left the conversation."));
            summarizeForSingleNPC(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName(), npcName);
        }
        else if (conversation.getNpcNames().size() == 1 && !anyNearbyNPC) {
            // Clean up the NPC's hologram and facing behavior
            cleanupNPCHologram(npc);

            endConversation(player);
        } else if (conversation.getNpcNames().size() == 1 && anyNearbyNPC) {
            // Clean up the NPC's hologram and facing behavior
            cleanupNPCHologram(npc);

            player.sendMessage(ChatColor.GRAY + npcName + " has left the conversation.");
            conversation.addMessage(new ConversationMessage("system", npcName + " has left the conversation."));
            summarizeForSingleNPC(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName(), npcName);
            conversation.removeNPC(npc);
        }
        else {
            player.sendMessage(ChatColor.YELLOW + npcName + " is not part of the conversation.");
        }
    }

    public void endConversation(GroupConversation conversation) {
        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);

            // Clean up all NPCs in the conversation
            for (NPC npc : conversation.getNPCs()) {
                cleanupNPCHologram(npc);
            }

            // Get location name
            String locationName = "Village"; // Default location
            List<NPC> npcs = conversation.getNPCs();
            if (!npcs.isEmpty()) {
                NPC firstNPC = npcs.get(0);
                if (firstNPC != null) {
                    NPCUtils.NPCContext context = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(firstNPC.getName());
                    if (context != null && context.location != null) {
                        locationName = context.location.getName();
                    }
                }
            }

            // Existing functionality
            summarizeConversation(conversation.getConversationHistory(), conversation.getNpcNames(), null);
            applyEffects(conversation.getConversationHistory(), conversation.getNpcNames(), null);

            // New: Process the conversation for rumors and personal knowledge
            RumorManager.getInstance(plugin).processConversationSignificance(
                    conversation.getConversationHistory(),
                    conversation.getNpcNames(),
                    locationName
            );

            removeConversation(conversation);
        }
    }

    /**
     * Adds an NPC to an existing conversation
     * @param npc The NPC to add
     * @param conversation The conversation to add the NPC to
     * @param greetingMessage Optional greeting message (can be null)
     * @return True if successful, false otherwise
     */
    /**
     * Adds an NPC to an existing conversation by making the NPC walk to the conversation location first
     *
     * @param npc The NPC to add to the conversation
     * @param conversation The conversation to add the NPC to
     * @param greetingMessage Optional greeting message for the NPC to say when joining (can be null)
     * @return true if the NPC was added successfully, false otherwise
     */
    public boolean addNPCToConversationWalk(NPC npc, GroupConversation conversation, String greetingMessage) {
        if (npc == null || !npc.isSpawned() || conversation == null || !conversation.isActive()) {
            return false;
        }

        // Check if NPC is already in this conversation
        if (conversation.getNpcNames().contains(npc.getName())) {
            return false;
        }

        // Get the NPCContext for avatar and color information
        NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npc.getName());

        // Try multiple potential target locations
        List<Location> potentialTargets = new ArrayList<>();

        // First try players in the conversation
        for (UUID playerUUID : conversation.getPlayers()) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                potentialTargets.add(player.getLocation());
            }
        }

        // Then try other NPCs in the conversation
        for (String npcName : conversation.getNpcNames()) {
            NPC targetNPC = plugin.getNPCByName(npcName);
            if (targetNPC != null && targetNPC.isSpawned() && !targetNPC.equals(npc)) {
                potentialTargets.add(targetNPC.getEntity().getLocation());
            }
        }

        // If no targets available, return false
        if (potentialTargets.isEmpty()) {
            return false;
        }

        // Find the closest navigable target location
        Location bestTarget = null;
        double shortestDistance = Double.MAX_VALUE;
        Player targetPlayer = null;

        for (Location target : potentialTargets) {
            double distance = npc.getEntity().getLocation().distance(target);
            if (distance < shortestDistance && npc.getNavigator().canNavigateTo(target)) {
                shortestDistance = distance;
                bestTarget = target;

                // If this is a player's location, store the player
                for (UUID playerUUID : conversation.getPlayers()) {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.getLocation().equals(target)) {
                        targetPlayer = player;
                        break;
                    }
                }
            }
        }

        // If no navigable location found, add NPC without walking
        if (bestTarget == null) {
            //handleNPCJoiningConversationDirectly(npc, conversation, greetingMessage, npcContext);
            return false;
        }

        // Make final references for use in lambdas
        final Location finalTarget = bestTarget;
        final Player finalTargetPlayer = targetPlayer;

        // Start staged navigation process
        startStagedNavigation(npc, finalTarget, finalTargetPlayer, conversation, greetingMessage, npcContext);
        return true;
    }

    public void handleNPCJoiningConversationDirectly(NPC npc, GroupConversation conversation,
                                                      String greetingMessage, NPCUtils.NPCContext npcContext) {
        conversation.addNPC(npc);
        String joinMessage = npc.getName() + " has joined the conversation.";
        conversation.addMessage(new Story.ConversationMessage("system", joinMessage));

        if (greetingMessage != null && !greetingMessage.isEmpty()) {
            conversation.addMessage(new Story.ConversationMessage(
                    "assistant", npc.getName() + ": " + greetingMessage));

            plugin.broadcastNPCMessage(
                    greetingMessage,
                    npc.getName(),
                    false,
                    npc,
                    null,
                    null,
                    npcContext.avatar,
                    plugin.randomColor(npc.getName())
            );
        }
    }

    private void startStagedNavigation(NPC npc, Location finalTarget, Player targetPlayer,
                                       GroupConversation conversation, String greetingMessage, NPCUtils.NPCContext npcContext) {

        if (npc == null || !npc.isSpawned()) {
            return;
        }

        Runnable onArrival = () -> {
            // Handle NPC joining conversation
            handleNPCJoiningConversation(npc, conversation, greetingMessage, targetPlayer, npcContext);
        };

        if (targetPlayer != null) {
           plugin.npcManager.walkToLocation(npc, targetPlayer.getLocation(), 1, 1.25f, 30, onArrival, null);
           return;
        }
        if (npc.getNavigator().canNavigateTo(finalTarget)) {
            // If NPC can navigate directly to the target, do so
            plugin.npcManager.walkToLocation(npc, finalTarget, 1, 1.25f, 30, onArrival, null);
        }
    }

    private List<Location> createWaypoints(Location start, Location end, double segmentDistance) {
        List<Location> waypoints = new ArrayList<>();

        double totalDistance = start.distance(end);
        int segments = (int) Math.ceil(totalDistance / segmentDistance);

        // If the distance is shorter than one segment, just use the end point
        if (segments <= 1) {
            waypoints.add(end);
            return waypoints;
        }

        // Create waypoints along the straight line between start and end
        org.bukkit.util.Vector direction = end.toVector().subtract(start.toVector()).normalize();

        for (int i = 1; i <= segments; i++) {
            double segmentLength = i < segments ? segmentDistance : totalDistance - ((segments - 1) * segmentDistance);
            org.bukkit.util.Vector pathSegment = direction.clone().multiply(segmentDistance * i);

            if (i < segments) {
                Location waypoint = start.clone().add(pathSegment);
                waypoints.add(waypoint);
            } else {
                // Last waypoint is the exact destination
                waypoints.add(end);
            }
        }

        return waypoints;
    }

    private void navigateToNextWaypoint(NPC npc, List<Location> waypoints, int currentIndex, Location finalDestination,
                                        Player targetPlayer, GroupConversation conversation, String greetingMessage,
                                        NPCUtils.NPCContext npcContext) {
        if (currentIndex >= waypoints.size() || !npc.isSpawned()) {
            // Navigation complete or NPC despawned
            handleNPCJoiningConversation(npc, conversation, greetingMessage, targetPlayer, npcContext);
            return;
        }

        Location currentWaypoint = waypoints.get(currentIndex);

        // Set up navigation parameters
        Navigator navigator = npc.getNavigator();
        navigator.getLocalParameters()
                .speedModifier(1.0f)
                .range(25)
                .distanceMargin(2.0);

        // Start navigation to current waypoint
        boolean navigationStarted = navigator.isNavigating() || navigator.canNavigateTo(currentWaypoint);

        navigator.setTarget(currentWaypoint);

        if (!navigationStarted) {
            // If navigation failed to start, try adding NPC directly
            handleNPCJoiningConversation(npc, conversation, greetingMessage, targetPlayer, npcContext);
            return;
        }

        // Create a unique listener for this navigation leg
        NavigationListener listener = new NavigationListener(npc, waypoints, currentIndex, finalDestination,
                targetPlayer, conversation, greetingMessage, npcContext);

        // Register the listener
        Bukkit.getPluginManager().registerEvents(listener, plugin);

        // Set a timeout for this navigation leg (10 seconds)
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Unregister the listener to avoid duplicate events
            NavigationCompleteEvent.getHandlerList().unregister(listener);

            if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
                // Cancel current navigation
                npc.getNavigator().cancelNavigation();

                // If we're close enough to final destination, consider it complete
                if (npc.getEntity().getLocation().distance(finalDestination) < 10) {
                    handleNPCJoiningConversation(npc, conversation, greetingMessage, targetPlayer, npcContext);
                } else {
                    // Try next waypoint anyway
                    navigateToNextWaypoint(npc, waypoints, currentIndex + 1, finalDestination,
                            targetPlayer, conversation, greetingMessage, npcContext);
                }
            }
        }, 10 * 20L).getTaskId(); // 10 seconds timeout

        // Store the task ID in the listener for cancellation
        listener.setTimeoutTaskId(taskId);
    }

    // Inner class for handling navigation events
    private class NavigationListener implements Listener {
        private final NPC npc;
        private final List<Location> waypoints;
        private final int currentIndex;
        private final Location finalDestination;
        private final Player targetPlayer;
        private final GroupConversation conversation;
        private final String greetingMessage;
        private final NPCUtils.NPCContext npcContext;
        private int timeoutTaskId = -1;

        public NavigationListener(NPC npc, List<Location> waypoints, int currentIndex, Location finalDestination,
                                  Player targetPlayer, GroupConversation conversation, String greetingMessage,
                                  NPCUtils.NPCContext npcContext) {
            this.npc = npc;
            this.waypoints = waypoints;
            this.currentIndex = currentIndex;
            this.finalDestination = finalDestination;
            this.targetPlayer = targetPlayer;
            this.conversation = conversation;
            this.greetingMessage = greetingMessage;
            this.npcContext = npcContext;
        }

        public void setTimeoutTaskId(int timeoutTaskId) {
            this.timeoutTaskId = timeoutTaskId;
        }

        @EventHandler
        public void onNavigationComplete(NavigationCompleteEvent event) {
            if (!event.getNPC().equals(npc)) {
                return;
            }

            // Unregister this listener
            NavigationCompleteEvent.getHandlerList().unregister(this);

            // Cancel the timeout task
            if (timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(timeoutTaskId);
            }

            // If this is the last waypoint, handle NPC joining conversation
            if (currentIndex >= waypoints.size() - 1) {
                handleNPCJoiningConversation(npc, conversation, greetingMessage, targetPlayer, npcContext);
            } else {
                // Navigate to the next waypoint
                navigateToNextWaypoint(npc, waypoints, currentIndex + 1, finalDestination,
                        targetPlayer, conversation, greetingMessage, npcContext);
            }
        }
    }

    // Helper method to handle the NPC joining conversation logic
    private void handleNPCJoiningConversation(NPC npc, GroupConversation conversation,
                                              String greetingMessage, Player targetPlayer,
                                              NPCUtils.NPCContext npcContext) {
        // Add NPC to conversation
        conversation.addNPC(npc);

        // System message about NPC joining
        String joinMessage = npc.getName() + " has joined the conversation.";
        conversation.addMessage(new Story.ConversationMessage("system", joinMessage));

        String finalGreetingMessage = greetingMessage;

        // If greeting null, create one
        if (finalGreetingMessage == null || finalGreetingMessage.isEmpty()) {
            finalGreetingMessage = generateNPCGreeting(npc, conversation);
        }

        // Handle greeting message if provided
        if (finalGreetingMessage != null && !finalGreetingMessage.isEmpty()) {
            // Add NPC's greeting to the conversation
            conversation.addMessage(new Story.ConversationMessage(
                    "assistant", npc.getName() + ": " + finalGreetingMessage));

            // Broadcast the greeting message
            plugin.broadcastNPCMessage(
                    finalGreetingMessage,
                    npc.getName(),
                    false,
                    npc,
                    targetPlayer != null ? targetPlayer.getUniqueId() : null,
                    targetPlayer,
                    npcContext.avatar,
                    plugin.randomColor(npc.getName())
            );
        }

        // Generate a response from the other NPCs in the conversation
        generateGroupNPCResponses(conversation, null);
    }

    /**
     * Adds an NPC's greeting to a conversation and triggers responses
     */
    private void addNPCGreeting(NPC npc, GroupConversation conversation, String greeting) {
        String npcName = npc.getName();

        // Add the greeting to conversation history
        conversation.addMessage(new Story.ConversationMessage(npcName, greeting));
        NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npcName);

        // Broadcast the greeting message
        Bukkit.getScheduler().runTask(plugin, () -> {
            String colorCode = plugin.randomColor(npcName);
            plugin.broadcastNPCMessage(greeting, npcName, false, npc, null, null, npcContext.avatar, colorCode);

            // Generate responses from other NPCs in the conversation
            generateGroupNPCResponses(conversation, null);
        });
    }

    /**
     * Generate a greeting for an NPC joining a conversation
     */
    private String generateNPCGreeting(NPC npc, GroupConversation conversation) {
        String npcName = npc.getName();
        NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npcName);

        // Build the list of existing participants
        StringBuilder participantsStr = new StringBuilder();
        for (UUID player : conversation.getPlayers()) {
            Player playerObj = Bukkit.getPlayer(player);
            if (playerObj == null) {
                continue; // Skip if player is offline
            }
            String playerName = playerObj.getName();
            if (EssentialsUtils.getNickname(playerName) != null) {
                playerName = EssentialsUtils.getNickname(playerName);
            }
            participantsStr.append(playerName).append(", ");
        }
        for (NPC existingNPC : conversation.getNPCs()) {
            if (!existingNPC.equals(npc)) {
                participantsStr.append(existingNPC.getName()).append(", ");
            }
        }
        if (participantsStr.length() > 2) {
            participantsStr.setLength(participantsStr.length() - 2); // Remove trailing comma and space
        }

        List<ConversationMessage> prompts = new ArrayList<>();
        // Create prompt for generating greeting
        // Add general and location contexts
        plugin.getGeneralContexts().forEach(context ->
                prompts.add(new ConversationMessage("system", context)));

        if (npcContext.location != null) {
            // Get all contexts including parent locations
            List<String> allLocContexts = plugin.getLocationManager().getAllContextForLocation(npcContext.location.getName());
            allLocContexts.forEach(context ->
                    prompts.addFirst(new ConversationMessage("system", context))
            );
        }

        // Add recent conversation history for context
        List<Story.ConversationMessage> recentHistory = conversation.getConversationHistory()
                .subList(Math.max(conversation.getConversationHistory().size() - 10, 0), conversation.getConversationHistory().size());

        prompts.addAll(recentHistory);




        prompts.add(new ConversationMessage("system", "Relations: " + npcContext.relations.toString()));

        // include last 5 messages from the conversation history
        List<ConversationMessage> history = npcContext.conversationHistory;

        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            ConversationMessage message = history.get(i);
            prompts.add(new ConversationMessage(message.getRole(), message.getContent()));
        }

        // Add system context
        prompts.add(new Story.ConversationMessage("system",
                npcContext.context +
                        "\n\nYou are joining an ongoing conversation with: " + participantsStr.toString() +
                        "\n\nGenerate a greeting or introduction that acknowledges the ongoing conversation. " +
                        "Keep it brief and in-character. Don't use quotation marks or indicate who is speaking."));



        // Add final instruction
        prompts.add(new Story.ConversationMessage("user",
                "Write a single greeting or introduction line as " + npcName + " joining this conversation."));

        // Generate the greeting
        try {
            String greeting = plugin.getAIResponse(prompts);
            return greeting != null ? greeting.trim() : null;
        } catch (Exception e) {
            plugin.getLogger().severe("Error generating NPC greeting: " + e.getMessage());
            return null;
        }
    }

    // Do the same change for the Player version of endConversation
    public void endConversation(Player player) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = getConversationByPlayer(player);

        if (conversation != null && conversation.isActive()) {
            conversation.setActive(false);

            // Clean up all NPCs in the conversation
            for (NPC npc : conversation.getNPCs()) {
                cleanupNPCHologram(npc);
            }

            // Get location name
            String locationName = "Village"; // Default location
            List<NPC> npcs = conversation.getNPCs();
            if (!npcs.isEmpty()) {
                NPC firstNPC = npcs.get(0);
                if (firstNPC != null) {
                    NPCUtils.NPCContext context = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(firstNPC.getName());
                    if (context != null && context.location != null) {
                        locationName = context.location.getName();
                    }
                }
            }

            // Summarize the conversation (existing functionality)
            summarizeConversation(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName());
            applyEffects(conversation.getConversationHistory(), conversation.getNpcNames(), player.getName());

            // New: Process the conversation for rumors and personal knowledge
            RumorManager.getInstance(plugin).processConversationSignificance(
                    conversation.getConversationHistory(),
                    conversation.getNpcNames(),
                    locationName
            );

            removeConversation(conversation);
            player.sendMessage(ChatColor.GRAY + "The conversation has ended.");
        } else {
            activeConversations.values().removeIf(convo -> !convo.isActive());
        }
    }

    // Add a player's message to the group conversation
    public void addPlayerMessage(Player player, String message, boolean chatEnabled) {
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = getConversationByPlayer(player);

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

        // Cancel any existing scheduled tasks for this conversation
        Integer existingTaskId = scheduledTasks.get(conversation);
        if (existingTaskId != null) {
            Bukkit.getScheduler().cancelTask(existingTaskId);
        }

        // Schedule a new task to generate responses after 5 seconds
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Generate responses from all NPCs sequentially
            generateGroupNPCResponses(conversation, player);
            // Remove from scheduled tasks map since it's now executing
            scheduledTasks.remove(conversation);
        }, plugin.getResponseDelay() * 20L).getTaskId(); // 5 seconds * 20 ticks/second

        // Store the task ID
        scheduledTasks.put(conversation, taskId);
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
        GroupConversation conversation = getConversationByPlayer(player);
        return conversation != null && conversation.isActive() && conversation.getNpcNames().contains(npcName);
    }

    public boolean isNPCInConversation(String npcName) {
        for (GroupConversation conversation : activeConversations.values()) {
            if (conversation.getNpcNames().contains(npcName)) {
                return true;
            }
        }
        return false;
    }

    public boolean addPlayerToConversation(Player player, String npcName) {
        // Join the player to another player's conversation
        for (GroupConversation conversation : activeConversations.values()) {
            if (conversation.getNpcNames().contains(npcName)) {
                if (conversation.addPlayer(player)) {
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
        GroupConversation conversation = getConversationByPlayer(player);
        return conversation != null && conversation.isActive();
    }

    public void addNPCMessage(String npcName, String message) {
        for (GroupConversation conversation : activeConversations.values()) {
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
        GroupConversation conversation = getConversationByPlayer(player);
        return conversation != null ? conversation.getNPCs() : new ArrayList<>();
    }

    public List<String> getAllParticipantsInConversation(Player player) {
        // get both all npc names and player names in conversation
        UUID playerUUID = player.getUniqueId();
        GroupConversation conversation = getConversationByPlayer(player);
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
        return activeConversations.values().stream()
                .filter(conversation -> conversation.getNpcNames().contains(npcName))
                .findFirst().orElse(null);
    }

    /**
     * Checks the entire conversation history for lorebook keywords and adds relevant contexts
     */
    private void checkAndAddLoreContexts(GroupConversation conversation) {
        List<ConversationMessage> allMessages = conversation.getConversationHistory();
        if (allMessages.isEmpty()) {
            return;
        }
        reloadConfig();

        // Track lore names that we've already checked to avoid redundant processing
        Set<String> processedMessages = new HashSet<>();
        Set<String> addedLoreNames = new HashSet<>();

        // Iterate through all messages in the conversation history
        for (ConversationMessage message : allMessages) {
            // Skip system messages and already processed content
            if ("system".equals(message.getRole()) || processedMessages.contains(message.getContent())) {
                continue;
            }

            // Mark this message as processed
            processedMessages.add(message.getContent());

            String messageContent = message.getContent();

            // Extract the actual message content if it includes speaker name (format: "Name: message")
            int colonIndex = messageContent.indexOf(":");
            if (colonIndex > 0) {
                messageContent = messageContent.substring(colonIndex + 1).trim();
            }

            // Check for relevant lore contexts
            List<LoreBookManager.LoreContext> relevantContexts =
                    loreBookManager.findRelevantLoreContexts(messageContent, conversation);

            // Add any found contexts to the conversation if not already added
            for (LoreBookManager.LoreContext context : relevantContexts) {
                if (addedLoreNames.add(context.getLoreName())) {
                    conversation.addMessage(new Story.ConversationMessage("system", context.getContext()));
                    plugin.getLogger().info("Added lore context from '" + context.getLoreName() +
                            "' to conversation based on message: " + messageContent);
                }
            }
        }
    }

    // Generate NPC responses sequentially for the group conversation
    public CompletableFuture<Void> generateGroupNPCResponses(GroupConversation conversation, Player player) {
        // Skip if no NPCs in conversation
        if (conversation.getNpcNames().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Check for relevant lorebook contexts based on the entire conversation history
        checkAndAddLoreContexts(conversation);

        // First, show listening/watching holograms for all NPCs
        for (NPC npc : conversation.getNPCs()) {
            showListeningHolo(npc);
        }

        return determineNextSpeaker(conversation, player)
                .thenCompose(nextSpeakerName -> {
                    if (nextSpeakerName == null) {
                        return CompletableFuture.completedFuture(null);
                    }

                    NPC npc = conversation.getNPCs().stream()
                            .filter(n -> n.getName().equals(nextSpeakerName))
                            .findFirst()
                            .orElse(null);

                    if (npc == null || plugin.isNPCDisabled(nextSpeakerName)) {
                        return CompletableFuture.completedFuture(null);
                    }

                    CompletableFuture<Void> future = new CompletableFuture<>();

                    // Replace listening hologram with thinking hologram for speaker
                    showThinkingHolo(npc);

                    // Generate response after delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                // --- AI logic and setup code ---
                                NPCUtils.NPCContext NPCContext = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(nextSpeakerName);

                                List<ConversationMessage> tempHistory = new ArrayList<>(conversation.getConversationHistory());
                                List<String> playerNames = conversation.getPlayers().stream()
                                        .map(uuid -> {
                                            Player playerIns = Bukkit.getPlayer(uuid);
                                            return playerIns != null ? EssentialsUtils.getNickname(playerIns.getName()) : "";
                                        })
                                        .collect(Collectors.toList());

                                // Add system contexts
                                plugin.getGeneralContexts().forEach(context ->
                                        tempHistory.addFirst(new ConversationMessage("system", context))
                                );

                                if (NPCContext.location != null) {
                                    // Get all contexts including parent locations
                                    List<String> allLocContexts = plugin.getLocationManager().getAllContextForLocation(NPCContext.location.getName());
                                    allLocContexts.forEach(context ->
                                            tempHistory.addFirst(new ConversationMessage("system", context))
                                    );
                                }

                                // Add NPC conversation history
                                List<ConversationMessage> lastTwentyMessages = NPCContext.conversationHistory.subList(
                                        Math.max(NPCContext.conversationHistory.size() - 10, 0),
                                        NPCContext.conversationHistory.size());
                                lastTwentyMessages.add(0, NPCContext.conversationHistory.get(0));
                                tempHistory.addAll(0, lastTwentyMessages);

                                // Add specific instruction for this NPC's turn
                                tempHistory.add(new ConversationMessage("system",
                                        "You are " + nextSpeakerName + ". You are currently in a conversation with: " +
                                                String.join(", ", conversation.getNpcNames()) + " and " + String.join(", ", playerNames) + "." +
                                                " This is YOUR turn to speak. Do NOT generate dialogue for others. " +
                                                "Address the relevant character(s) naturally based on previous dialogue."));

                                tempHistory.add(new ConversationMessage("system", "Relations: " + NPCContext.relations.toString()));
                                tempHistory.add(new ConversationMessage("system", "Your responses should reflect your relations with the other characters if applicable. Never print out the relation as dialogue."));

                                String aiResponse = plugin.getAIResponse(tempHistory);

                                // --- Post-AI logic ---
                                // Remove thinking hologram
                                DHAPI.removeHologram(npc.getUniqueId().toString());

                                Integer taskId = hologramTasks.get(nextSpeakerName);
                                if (taskId != null) {
                                    Bukkit.getScheduler().cancelTask(taskId);
                                    hologramTasks.remove(nextSpeakerName);
                                }

                                // Remove all listening holograms
                                for (NPC otherNpc : conversation.getNPCs()) {
                                    if (!otherNpc.getName().equals(nextSpeakerName)) {
                                        DHAPI.removeHologram(otherNpc.getUniqueId().toString());
                                        Integer otherTaskId = hologramTasks.get(otherNpc.getName());
                                        if (otherTaskId != null) {
                                            Bukkit.getScheduler().cancelTask(otherTaskId);
                                            hologramTasks.remove(otherNpc.getName());
                                        }
                                    }
                                }

                                if (aiResponse == null || aiResponse.isEmpty()) {
                                    plugin.getLogger().warning("Failed to generate NPC response for " + nextSpeakerName);
                                    future.complete(null);
                                    return;
                                }

                                Pattern npcNamePattern = Pattern.compile("^([\\w\\s']+):(?:\\s*\\1:)?");
                                Matcher matcher = npcNamePattern.matcher(aiResponse);
                                String finalNpcName = matcher.find() ? matcher.group(1) : nextSpeakerName;

                                conversation.addMessage(new ConversationMessage("assistant", nextSpeakerName + ": " + aiResponse));
                                conversation.addMessage(new ConversationMessage("user", "..."));

                                if (aiResponse.contains("[End]") && player != null) {
                                    endConversation(player);
                                }

                                String colorCode = plugin.randomColor(finalNpcName);
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        plugin.broadcastNPCMessage(aiResponse, nextSpeakerName, false, npc, null, null, NPCContext.avatar, colorCode)
                                );

                                future.complete(null);

                            } catch (Exception e) {
                                plugin.getLogger().warning("Error generating NPC response for " + nextSpeakerName);
                                e.printStackTrace();
                                future.completeExceptionally(e);
                            }
                        });
                    }, 3 * 20L);

                    return future;
                });
    }

    private CompletableFuture<String> determineNextSpeaker(GroupConversation conversation, Player player) {
        CompletableFuture<String> future = new CompletableFuture<>();


        // Short-circuit for the simple case of only one NPC
        if (conversation.getNpcNames().size() == 1) {
            future.complete(conversation.getNpcNames().get(0));
            return future;
        }


        // Create a list of Messages for the AI to analyze
        List<ConversationMessage> speakerSelectionPrompt = new ArrayList<>();

        // Get recent conversation history (last 10 messages)
        List<ConversationMessage> recentHistory = conversation.getConversationHistory();
        int historySize = Math.min(recentHistory.size(), 10);
        List<ConversationMessage> contextMessages = recentHistory.subList(
                Math.max(0, recentHistory.size() - historySize),
                recentHistory.size());

        // Add system prompt for NPC selection
        speakerSelectionPrompt.add(new ConversationMessage("system",
                "Based on the conversation history below, determine which character should speak next. " +
                        "Consider: who was addressed in the last message, who has relevant information, " +
                        "and who hasn't spoken recently. " +
                        "Available characters: " + String.join(", ", conversation.getNpcNames()) + "\n\n" +
                        "Respond with ONLY the name of who should speak next. No explanation or additional text."
        ));

        // Add conversation context
        speakerSelectionPrompt.addAll(contextMessages);

        // Add a default NPC if the list is empty to avoid errors
        if (conversation.getNpcNames().isEmpty()) {
            future.complete(null);
            return future;
        }

        // Run this asynchronously to avoid blocking
        CompletableFuture.runAsync(() -> {
            try {
                String speakerSelection = plugin.getAIResponse(speakerSelectionPrompt);

                // Clean up the response and validate
                if (speakerSelection != null && !speakerSelection.isEmpty()) {
                    speakerSelection = speakerSelection.trim();

                    // Check if the selected speaker is a valid NPC in the conversation
                    if (conversation.getNpcNames().contains(speakerSelection)) {
                        future.complete(speakerSelection);
                    } else {
                        // Fall back to the first NPC if the selected speaker is invalid
                        future.complete(conversation.getNpcNames().get(0));
                    }
                } else {
                    // Fall back to the first NPC if no response
                    future.complete(conversation.getNpcNames().get(0));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error determining next speaker: " + e.getMessage());
                // Fall back to the first NPC on error
                future.complete(conversation.getNpcNames().get(0));
            }
        });

        return future;
    }

    public void showListeningHolo(NPC npc) {
        String npcName = npc.getName();

        if (!npc.isSpawned() || npc.getEntity() == null) return;


        // Only attempt to remove holograms if DecentHolograms is available
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            try {
                Location npcPos = npc.getEntity().getLocation().clone().add(0, 2.10, 0);
                String npcUUID = npc.getUniqueId().toString();

                // Remove any existing hologram for this NPC
                Hologram holo = DHAPI.getHologram(npcUUID);
                if (holo != null) DHAPI.removeHologram(npcUUID);

                // Create a new hologram
                holo = DHAPI.createHologram(npcUUID, npcPos);

                String[] listeningStates = {"&7&olistening...", "&7&owatching...", "&7&onodding..."};
                String chosenState = listeningStates[new Random().nextInt(listeningStates.length)];

                DHAPI.addHologramLine(holo, 0, chosenState);
                DHAPI.updateHologram(npcUUID);

                Hologram finalHolo = holo;
                Random random = new Random();

                // Find the conversation this NPC is in
                GroupConversation conversation = null;
                for (GroupConversation conv : activeConversations.values()) {
                    if (conv.getNpcNames().contains(npcName)) {
                        conversation = conv;
                        break;
                    }
                }

                // If NPC is not in any conversation, don't create the hologram task
                if (conversation == null) {
                    DHAPI.removeHologram(npcUUID);
                    return;
                }

                // Store the final reference for lambdas
                final GroupConversation finalConversation = conversation;

                // Create a class-level map to store the last look time per NPC if it doesn't exist
                if (npcLastLookTimes == null) {
                    npcLastLookTimes = new HashMap<>();
                }

                // Set initial look time and interval if not already set
                Long lastLookTime = npcLastLookTimes.get(npcName);
                if (lastLookTime == null) {
                    lastLookTime = System.currentTimeMillis();
                    npcLastLookTimes.put(npcName, lastLookTime);
                }

                // Store next look interval in a map if not already set
                if (npcLookIntervals == null) {
                    npcLookIntervals = new HashMap<>();
                }

                Integer lookInterval = npcLookIntervals.get(npcName);
                if (lookInterval == null) {
                    lookInterval = random.nextInt(3000) + 2000; // 2-5 seconds
                    npcLookIntervals.put(npcName, lookInterval);
                }

                // Cancel any existing task for this NPC
                Integer existingTaskId = hologramTasks.get(npcName);
                if (existingTaskId != null) {
                    Bukkit.getScheduler().cancelTask(existingTaskId);
                    hologramTasks.remove(npcName);
                }

                int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    // Check if NPC is still spawned and in a conversation
                    if (!npc.isSpawned() || npc.getEntity() == null || !isNPCInConversation(npcName)) {
                        // Remove the hologram
                        DHAPI.removeHologram(npcUUID);

                        // Cancel this task
                        Integer taskToCancel = hologramTasks.get(npcName);
                        if (taskToCancel != null) {
                            Bukkit.getScheduler().cancelTask(taskToCancel);
                            hologramTasks.remove(npcName);
                        }

                        // Reset look traits if applicable
                        if (npc.isSpawned() && npc.hasTrait(LookClose.class)) {
                            LookClose lookTrait = npc.getTraitNullable(LookClose.class);
                            if (lookTrait != null) {
                                lookTrait.lookClose(false);
                            }
                        }
                        return;
                    }

                    // Update hologram position
                    Location updatedPos = npc.getEntity().getLocation().clone().add(0, 2.10, 0);
                    DHAPI.moveHologram(finalHolo, updatedPos);

                    // Check if it's time to look at someone else using our persistent maps
                    long currentTime = System.currentTimeMillis();
                    Long storedLastLook = npcLastLookTimes.get(npcName);
                    Integer storedInterval = npcLookIntervals.get(npcName);

                    if (storedLastLook != null && storedInterval != null &&
                            currentTime - storedLastLook > storedInterval && finalConversation != null) {

                        // Reset the timer and set a new random interval
                        npcLastLookTimes.put(npcName, currentTime);
                        int newInterval = random.nextInt(3000) + 2000; // 2-5 seconds
                        npcLookIntervals.put(npcName, newInterval);

                        // Choose someone to look at
                        List<Object> targets = new ArrayList<>();

                        // Add only NPCs that are still in the conversation
                        for (NPC otherNpc : finalConversation.getNPCs()) {
                            if (otherNpc.isSpawned() && !otherNpc.equals(npc) &&
                                    finalConversation.getNpcNames().contains(otherNpc.getName())) {
                                targets.add(otherNpc.getEntity());
                            }
                        }

                        // Add only Players that are still in the conversation
                        for (UUID playerUUID : finalConversation.getPlayers()) {
                            Player player = Bukkit.getPlayer(playerUUID);
                            if (player != null && player.isOnline()) {
                                targets.add(player);
                            }
                        }

                        // Look at a random target if any are available
                        if (!targets.isEmpty() && random.nextInt(10) < 8) { // 70% chance to look at someone
                            Object target = targets.get(random.nextInt(targets.size()));
                            if (target instanceof Entity) {
                                // Set a slower head rotation speed for more natural movement
                                npc.getNavigator().getDefaultParameters().speedModifier(1f);
                                RotationTrait rot = npc.getOrAddTrait(RotationTrait.class);
                                rot.getPhysicalSession().rotateToFace((Entity) target);
                            }
                        }
                    }
                }, 0L, 5L).getTaskId();

                hologramTasks.put(npcName, taskId);
            } catch (Exception e) {
                plugin.getLogger().warning("Error while showing listening hologram: " + e.getMessage());
            }
        }
    }



    public void showThinkingHolo(NPC npc) {
        String npcName = npc.getName();

        if (!npc.isSpawned() || npc.getEntity() == null) return;

        // Only attempt to remove holograms if DecentHolograms is available
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {

            try {

                Location npcPos = npc.getEntity().getLocation().clone().add(0, 2.10, 0);
                String npcUUID = npc.getUniqueId().toString();

                Hologram holo = DHAPI.getHologram(npcUUID);
                if (holo != null) DHAPI.removeHologram(npcUUID);

                holo = DHAPI.createHologram(npcUUID, npcPos);
                DHAPI.addHologramLine(holo, 0, "&9&othinking...");
                DHAPI.updateHologram(npcUUID);

                Hologram finalHolo = holo;

                int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (!npc.isSpawned() || npc.getEntity() == null) {
                        // Remove the hologram if the NPC is gone
                        DHAPI.removeHologram(npc.getUniqueId().toString());

                        // Check if the task ID exists in the map before canceling
                        Integer taskToCancel = hologramTasks.get(npcName);
                        if (taskToCancel != null) {
                            Bukkit.getScheduler().cancelTask(taskToCancel);
                            hologramTasks.remove(npcName);
                        }
                        return;
                    }

                    Location updatedPos = npc.getEntity().getLocation().clone().add(0, 2.10, 0);
                    DHAPI.moveHologram(finalHolo, updatedPos);
                }, 0L, 5L).getTaskId();

                hologramTasks.put(npcName, taskId);
            } catch (Exception e) {
                plugin.getLogger().warning("Error while showing thinking hologram: " + e.getMessage());
            }
        }
    }

    public void cleanupNPCHologram(NPC npc) {
        if (npc == null) return;

        String npcName = npc.getName();
        String npcUUID = npc.getUniqueId().toString();


        // Only attempt to remove holograms if DecentHolograms is available
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {

            try {
                // Remove the hologram
                Hologram holo = DHAPI.getHologram(npcUUID);
                if (holo != null) {
                    DHAPI.removeHologram(npcUUID);
                }

                // Cancel the task
                Integer taskId = hologramTasks.get(npcName);
                if (taskId != null) {
                    Bukkit.getScheduler().cancelTask(taskId);
                    hologramTasks.remove(npcName);
                }

                // Reset NPC look traits
                if (npc.isSpawned()) {
                    if (npc.hasTrait(LookClose.class)) {
                        LookClose lookTrait = npc.getTraitNullable(LookClose.class);
                        if (lookTrait != null) {
                            lookTrait.lookClose(false);
                        }
                    }
                }

                // Clear tracking data
                npcLastLookTimes.remove(npcName);
                npcLookIntervals.remove(npcName);
            } catch (Exception e) {
                plugin.getLogger().warning("Error while cleaning up NPC hologram: " + e.getMessage());
            }
        }
    }


    public void addSystemMessage(GroupConversation conversation, String message) {
        conversation.addMessage(new ConversationMessage("system", message));
    }



    // Summarize the conversation history
// Summarize the conversation history
    private void summarizeConversation(List<ConversationMessage> history, List<String> npcNames, String playerName) {
        if (history.isEmpty() || history.size() < 3) return; // Skip trivial conversations

        // List of prompts to be sent to the AI
        List<ConversationMessage> prompts = new ArrayList<>();

        // Add the conversation history
        prompts.addAll(history);

        // Create a more direct prompt for summarization with clear output formatting requirements
        prompts.add(new ConversationMessage("system", """
        Summarize this conversation concisely and chronologically, focusing on key information and events.
        Analyze what happened and rate the conversation's significance on a scale of 0-10:
        - 0-2: Not significant (greetings, small talk with no useful information)
        - 3-5: Somewhat significant (basic information shared)
        - 6-8: Significant (meaningful interaction, relationship development)
        - 9-10: Highly significant (major revelations, critical information)
        
        Format your response exactly like this:
        [SUMMARY]
        Your actual summary text here...
        [SIGNIFICANCE: X]
        
        Where X is the numeric significance rating (0-10).
        Both sections are required.
        """));

        // Generate summary using AI
        CompletableFuture.runAsync(() -> {
            try {
                String summaryResult = plugin.getAIResponse(prompts);
                if (summaryResult != null && !summaryResult.isEmpty()) {
                    // Extract summary and significance
                    String summary = "";
                    int significance = 5; // Default to middle rating

                    // Extract the summary section
                    Pattern summaryPattern = Pattern.compile("\\[SUMMARY\\](.*?)(?:\\[SIGNIFICANCE|$)", Pattern.DOTALL);
                    Matcher summaryMatcher = summaryPattern.matcher(summaryResult);
                    if (summaryMatcher.find()) {
                        summary = summaryMatcher.group(1).trim();
                    } else {
                        // If formatting failed, use the whole response
                        summary = summaryResult;
                    }

                    // Extract significance rating
                    Pattern significancePattern = Pattern.compile("\\[SIGNIFICANCE:\\s*(\\d+)\\]");
                    Matcher significanceMatcher = significancePattern.matcher(summaryResult);
                    if (significanceMatcher.find()) {
                        significance = Integer.parseInt(significanceMatcher.group(1));
                    }

                    plugin.getLogger().info("Conversation summary significance: " + significance);

                    // Only add significant conversations to NPC memory
                    if (significance > 2) {
                        for (String npcName : npcNames) {
                            plugin.addSystemMessage(npcName, summary);
                        }
                        plugin.getLogger().info("Added significant conversation to memory");
                    } else {
                        plugin.getLogger().info("Skipped adding insignificant conversation to memory");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLogger().severe("Error occurred while summarizing conversation: " + e.getMessage());
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
                // Parse the character name(s)
                String characterEntry = line.split(":")[1].trim();
                npcName = characterEntry;
            } else if (line.startsWith("Effect:")) {
                effect = line.split(":")[1].trim();
            } else if (line.startsWith("Target:")) {
                target = line.split(":")[1].trim();
            } else if (line.startsWith("Value:")) {
                value = line.split(":")[1].trim();
            }

            // When all necessary variables are set, process the effect
            if (npcName != null && effect != null && target != null && value != null) {
                // Split the NPC name by commas and process each one individually
                String[] npcNames = npcName.split(",\\s*");

                for (String singleNpcName : npcNames) {
                    switch (effect) {
                        case "relation":
                            plugin.saveNPCRelationValue(singleNpcName.trim(), target, value);
                            break;
                        case "title":
                            //plugin.broadcastNPCMessage("Title of " + target + " has changed to " + value, singleNpcName, false, null, null, null, "#599B45");
                            break;
                        case "item":
                            // Handle item effects
                            break;
                        default:
                            // Handle other effects
                            break;
                    }
                }

                // Reset variables for the next effect
                npcName = null;
                effect = null;
                target = null;
                value = null;
            }
        }
    }


    // Check if a player has an active conversation
    public boolean hasActiveConversation(Player player) {
        GroupConversation conversation = getConversationByPlayer(player);
        return conversation != null && conversation.isActive();
    }

    public GroupConversation getActiveConversation(Player player) {
        return getConversationByPlayer(player);
    }
}
