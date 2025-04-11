package com.canefe.story;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.ai.AStarNavigationStrategy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;

public class NPCManager {

    private static NPCManager instance; // Singleton instance
    private final Story plugin;
    private final Map<String, NPCData> npcDataMap = new HashMap<>(); // Centralized NPC storage
    private final Map<GroupConversation, Integer> scheduledTasks = new HashMap<>();

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

    public void makeNPCWalkAway(NPC npc, GroupConversation convo) {
        if (!npc.isSpawned()) return;

        Location npcLocation = npc.getEntity().getLocation();
        Location targetLocation = findWalkableLocation(npc, convo);

        if (targetLocation == null) {
            // If we can't find a walkable location, just cancel any navigation
            npc.getNavigator().cancelNavigation();
            return;
        }

        // Set up navigation
        Navigator navigator = npc.getNavigator();
        navigator.getLocalParameters()
                .speedModifier(1.0f)
                .distanceMargin(1.5);

        navigator.cancelNavigation();
        navigator.setTarget(targetLocation);

        // Create a task ID holder
        final int[] taskId = {-1};

        // Create a task that periodically updates the navigation target and checks completion
        taskId[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!npc.isSpawned() || !navigator.isNavigating()) {
                // Cancel task if NPC is no longer valid or navigation has stopped
                Bukkit.getScheduler().cancelTask(taskId[0]);
                return;
            }

            // If NPC is stuck, try to find a new target
            if (!navigator.isNavigating()) {
                navigator.cancelNavigation();
                navigator.setTarget(targetLocation);
            }
        }, 20L, 20L); // Check every second (20 ticks)

        // Add a timeout to cancel the task after 10 seconds
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (taskId[0] != -1) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                if (npc.isSpawned() && navigator.isNavigating()) {
                    navigator.cancelNavigation();
                }
            }
        }, 200L); // 10 seconds (200 ticks)
    }

    private Location findWalkableLocation(NPC npc, GroupConversation convo) {
        if (!npc.isSpawned()) return null;

        Location npcLocation = npc.getEntity().getLocation();
        Location convoCenter = plugin.calculateConversationCenter(convo);

        if (convoCenter == null) {
            // If no conversation center, just pick a random direction
            double angle = Math.random() * Math.PI * 2;
            org.bukkit.util.Vector direction = new org.bukkit.util.Vector(
                    Math.cos(angle), 0, Math.sin(angle));

            Location targetLocation = npcLocation.clone().add(direction.multiply(15));
            targetLocation.setY(npcLocation.getWorld().getHighestBlockYAt(
                    targetLocation.getBlockX(), targetLocation.getBlockZ()) + 1);
            return targetLocation;
        }

        // Get direction vector away from conversation center
        org.bukkit.util.Vector direction = npcLocation.toVector()
                .subtract(convoCenter.toVector())
                .normalize();

        // Set target location chatRadius + 10 blocks away in that direction
        double targetDistance = plugin.getChatRadius() + 10.0;

        Location targetLocation = npcLocation.clone().add(direction.multiply(targetDistance));

        // Adjust Y coordinate to ground level plus 1
        targetLocation.setY(targetLocation.getWorld().getHighestBlockYAt(
                targetLocation.getBlockX(), targetLocation.getBlockZ()) + 1);

        return targetLocation;
    }

    public void eventGoToPlayerAndTalk(NPC npc, Player player, String message, List<Story.ConversationMessage> conversationHistory) {
        // Get NPC's context
        NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npc.getName());

        // Switch to the main thread for Citizens API calls
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Create a task ID holder
            final int[] taskId = {-1};

            // Register event listener for navigation completion
            Listener navigationListener = new Listener() {
                @EventHandler
                public void onNavigationComplete(NavigationCompleteEvent event) {
                    if (event.getNPC().equals(npc)) {
                        // Cancel the location update task when navigation completes
                        if (taskId[0] != -1) {
                            Bukkit.getScheduler().cancelTask(taskId[0]);
                        }

                        // Unregister the listener after completion
                        NavigationCompleteEvent.getHandlerList().unregister(this);

                        // Start the conversation once NPC reaches the player
                        List<NPC> npcs = new ArrayList<>();
                        npcs.add(npc);
                        GroupConversation conversation = plugin.conversationManager.startGroupConversation(player, npcs);

                        if (conversation == null) return;

                        String playerName = player.getName();
                        if (EssentialsUtils.getNickname(playerName) != null) {
                            playerName = EssentialsUtils.getNickname(playerName);
                        }

                        if (conversationHistory != null) {
                            for (Story.ConversationMessage message : conversationHistory) {
                                conversation.addMessage(message);
                            }
                        } else {
                            // Add system message about the NPC initiating conversation
                            conversation.addMessage(new Story.ConversationMessage("system",
                                    npc.getName() + " approached " + playerName + " and started a conversation."));

                            // Add NPC message to conversation
                            conversation.addMessage(new Story.ConversationMessage("assistant", npc.getName() + ": " + message));
                        }
                        // Broadcast message
                        plugin.broadcastNPCMessage(message, npc.getName(), false, npc, player.getUniqueId(),
                                player, npcContext.avatar, plugin.randomColor(npc.getName()));

                        // Schedule proximity check
                        plugin.scheduleProximityCheck(player, npc, conversation);
                    }
                }
            };

            // Register navigation listener
            Bukkit.getPluginManager().registerEvents(navigationListener, plugin);

            // Initial navigation setup
            Navigator navigator = npc.getNavigator();
            navigator.getDefaultParameters().distanceMargin(2.0); // Stop 2 blocks away
            navigator.setTarget(player.getLocation());

            // Create a task that periodically updates the navigation target
            taskId[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                if (!player.isOnline() || !npc.isSpawned()) {
                    // Cancel task and unregister listener if player or NPC is no longer valid
                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    NavigationCompleteEvent.getHandlerList().unregister(navigationListener);
                    return;
                }

                // Check if we're already close enough
                if (npc.getEntity().getLocation().distance(player.getLocation()) <= 3.0) {
                    // Trigger navigation complete manually
                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    NavigationCompleteEvent.getHandlerList().unregister(navigationListener);

                    // Start conversation
                    List<NPC> npcs = new ArrayList<>();
                    npcs.add(npc);
                    GroupConversation conversation = plugin.conversationManager.startGroupConversation(player, npcs);

                    if (conversation != null) {
                        conversation.addMessage(new Story.ConversationMessage("assistant", npc.getName() + ": " + message));
                        plugin.broadcastNPCMessage(message, npc.getName(), false, npc, player.getUniqueId(),
                                player, npcContext.avatar, plugin.randomColor(npc.getName()));
                        plugin.scheduleProximityCheck(player, npc, conversation);
                    }
                    return;
                }

                // Update target location if player moved
                navigator.setTarget(player.getLocation());
            }, 10L, 20L); // Check every second (20 ticks)
        });
    }

    public void eventGoToPlayerAndSay(NPC npc, String playerName, String message) {
        // Asynchronously get the NPC
            // Get the player by name
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                Bukkit.getLogger().warning("Player with name '" + playerName + "' is not online!");
                return;
            }
            NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npc.getName());
            // Switch back to the main thread for Citizens API calls
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Get the player's location
                Location playerLocation = player.getLocation();

                // Make the NPC navigate to the player
                Navigator navigator = npc.getNavigator();
                navigator.getDefaultParameters().distanceMargin(2.0); // Stop 2 blocks away
                navigator.setTarget(playerLocation); // Set the target location
                Bukkit.getLogger().info("Navigator target set for NPC: " + npc.getName() + " to player: " + player.getName());

                Listener listener = new Listener() {
                    @EventHandler
                    public void onNavigationComplete(NavigationCompleteEvent event) {
                        if (event.getNPC().equals(npc)) {
                            // Unregister the listener after completion
                            NavigationCompleteEvent.getHandlerList().unregister(this);

                            // Start the conversation once NPC reaches the player
                            List<NPC> npcs = new ArrayList<>();
                            npcs.add(npc);
                            GroupConversation conversation = plugin.conversationManager.startGroupConversation(player, npcs);

                            String colorCode = plugin.randomColor(npc.getName());

                            // Send the message to the player
                            conversation.addMessage(new Story.ConversationMessage("assistant", npc.getName() + ": " + message));
                            plugin.broadcastNPCMessage(message, npc.getName(), false, npc, player.getUniqueId(), player, npcContext.avatar, colorCode);
                        }
                    }
                };
                // Register event listener for navigation completion
                Bukkit.getPluginManager().registerEvents(listener, plugin);
            });
    }


    /**
     * Makes an NPC walk to a specified location with progress monitoring using waypoints for long distances
     *
     * @param npc The NPC to move
     * @param targetLocation The location to move to
     * @param distanceMargin How close the NPC needs to get to consider arrival (in blocks)
     * @param speedModifier Speed multiplier for the NPC's movement
     * @param timeout Maximum time in seconds before canceling (0 for no timeout)
     * @param onArrival Callback to run when NPC arrives (can be null)
     * @param onFailed Callback to run if navigation fails (can be null)
     * @return Navigation task ID that can be used to cancel the movement
     */
    public int walkToLocation(NPC npc, Location targetLocation,
                              double distanceMargin, float speedModifier,
                              int timeout,
                              Runnable onArrival, Runnable onFailed) {

        if (!npc.isSpawned()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        Location npcLocation = npc.getEntity().getLocation();
        double totalDistance = npcLocation.distance(targetLocation);

        // No need for waypoints if distance is small or within pathfinding range

        return directWalkToLocation(npc, targetLocation, distanceMargin, speedModifier,
                    timeout, onArrival, onFailed);


        // For long distances, use waypoints
        /* Calculate how many segments we need (max 20 blocks per segment)
        double segmentSize = Math.min(maxPathfindingRange, 20.0);
        int numSegments = (int) Math.ceil(totalDistance / segmentSize);

        // Create waypoints along the path
        List<Location> waypoints = new ArrayList<>();
        org.bukkit.util.Vector direction = targetLocation.toVector().subtract(npcLocation.toVector()).normalize();

        for (int i = 1; i < numSegments; i++) {
            // Calculate position at segmentSize * i distance along the path
            double distanceFromStart = segmentSize * i;
            org.bukkit.util.Vector waypointVector = npcLocation.toVector().add(direction.clone().multiply(distanceFromStart));

            Location waypoint = new Location(npcLocation.getWorld(),
                    waypointVector.getX(), waypointVector.getY(), waypointVector.getZ(),
                    npcLocation.getYaw(), npcLocation.getPitch());

            // Adjust Y coordinate to ground level plus 1 block
            waypoint.setY(waypoint.getWorld().getHighestBlockYAt(waypoint.getBlockX(), waypoint.getBlockZ()) + 1);
            waypoints.add(waypoint);
        }

        // Add the final destination
        waypoints.add(targetLocation);

        // Start the navigation chain
        return walkToWaypoints(npc, waypoints, 0, distanceMargin, speedModifier,
                maxPathfindingRange, timeout, onArrival, onFailed);

         */
    }

    public int walkToLocation(NPC npc, Entity target,
                              double distanceMargin, float speedModifier, int timeout,
                              Runnable onArrival, Runnable onFailed) {

        if (!npc.isSpawned()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        Location npcLocation = npc.getEntity().getLocation();

        // No need for waypoints if distance is small or within pathfinding range

        return directWalkToLocation(npc, target, distanceMargin, speedModifier, timeout, onArrival, onFailed);
    }

    /**
     * Internal method that handles navigation through a sequence of waypoints
     */
    private int walkToWaypoints(NPC npc, List<Location> waypoints, int currentIndex,
                                double distanceMargin, float speedModifier,
                                float maxPathfindingRange, int timeout,
                                Runnable onArrival, Runnable onFailed) {

        if (currentIndex >= waypoints.size() || !npc.isSpawned()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        Location currentWaypoint = waypoints.get(currentIndex);
        boolean isLastWaypoint = (currentIndex == waypoints.size() - 1);

        // Use the appropriate distance margin
        double waypointMargin = isLastWaypoint ? distanceMargin : 2.0;

        return directWalkToLocation(
                npc,
                currentWaypoint,
                waypointMargin,
                speedModifier,
                timeout,
                // On waypoint arrival
                () -> {
                    if (isLastWaypoint) {
                        // Reached final destination
                        if (onArrival != null) onArrival.run();
                    } else {
                        // Proceed to next waypoint
                        walkToWaypoints(npc, waypoints, currentIndex + 1,
                                distanceMargin, speedModifier,
                                maxPathfindingRange, timeout,
                                onArrival, onFailed);
                    }
                },
                // On failure
                onFailed
        );
    }

    /**
     * Direct walk method for a single segment of movement
     */
    private int directWalkToLocation(NPC npc, Location targetLocation,
                                     double distanceMargin, float speedModifier,
                                     int timeout,
                                     Runnable onArrival, Runnable onFailed) {

        if (!npc.isSpawned()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        // Set up navigation parameters
        Navigator navigator = npc.getNavigator();
        navigator.getDefaultParameters()
                .speedModifier(speedModifier)
                .range(100)
                .distanceMargin(distanceMargin)
                        .useNewPathfinder();

        // Start navigation
        navigator.cancelNavigation();
        navigator.setTarget(targetLocation);

        // Check if navigation actually started
        if (!navigator.isNavigating()) {
            if (onFailed != null) {
                Bukkit.getScheduler().runTask(plugin, onFailed);
            }
            return -1;
        }

        // Variables for tracking movement
        final Location[] lastLocation = {npc.getEntity().getLocation()};
        final int[] stuckCounter = {0};
        final int[] taskId = {-1};

        // Navigation monitoring task
        taskId[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // Check if NPC is still valid and spawned
            if (!npc.isSpawned()) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                if (onFailed != null) onFailed.run();
                return;
            }

            Location currentLocation = npc.getEntity().getLocation();

            // Check if reached destination
            double distanceToTarget = currentLocation.distance(targetLocation);
            if (distanceToTarget <= distanceMargin * 2) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                navigator.cancelNavigation();
                if (onArrival != null) onArrival.run();
                return;
            }

            // Check if stuck

            if (!navigator.isNavigating()) {
                    navigator.cancelNavigation();
                    navigator.setTarget(targetLocation);
            }


            // Check if navigation is still active
           /* if (!navigator.isNavigating()) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                if (onFailed != null) onFailed.run();
                return;
            } */

            // Update last location
            lastLocation[0] = currentLocation;
        }, 20L, 20L); // Check every second

        // Set timeout if specified
        if (timeout > 0) {
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                // Check if task is still running
                if (Bukkit.getScheduler().isQueued(taskId[0]) ||
                        Bukkit.getScheduler().isCurrentlyRunning(taskId[0])) {

                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    navigator.cancelNavigation();

                    if (onFailed != null) onFailed.run();
                }
            }, timeout * 20L); // Convert seconds to ticks
        }

        return taskId[0];
    }

    private int directWalkToLocation(NPC npc, Entity target,
                                     double distanceMargin, float speedModifier,
                                     int timeout,
                                     Runnable onArrival, Runnable onFailed) {

        if (!npc.isSpawned()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        if (target == null || !target.isValid()) {
            if (onFailed != null) onFailed.run();
            return -1;
        }

        // Set up navigation parameters
        Navigator navigator = npc.getNavigator();
        navigator.getDefaultParameters()
                .speedModifier(speedModifier)
                .range(100)
                .distanceMargin(distanceMargin)
                .useNewPathfinder();

        // Start navigation
        navigator.cancelNavigation();
        navigator.setTarget(target, false);

        // Check if navigation actually started
        if (!navigator.isNavigating()) {
            if (onFailed != null) {
                Bukkit.getScheduler().runTask(plugin, onFailed);
            }
            return -1;
        }

        // Variables for tracking movement
        final Location[] lastLocation = {npc.getEntity().getLocation()};
        final int[] stuckCounter = {0};
        final int[] taskId = {-1};

        // Navigation monitoring task
        taskId[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // Check if NPC is still valid and spawned
            if (!npc.isSpawned()) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                if (onFailed != null) onFailed.run();
                return;
            }

            Location currentLocation = npc.getEntity().getLocation();

            // Check if reached destination
            double distanceToTarget = currentLocation.distance(target.getLocation());
            if (distanceToTarget <= distanceMargin) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                navigator.cancelNavigation();
                if (onArrival != null) onArrival.run();
                return;
            }

            // Check if stuck

            if (!navigator.isNavigating()) {
                navigator.cancelNavigation();
                navigator.setTarget(target, false);
            }


            lastLocation[0] = currentLocation;
        }, 20L, 20L); // Check every second

        // Set timeout if specified
        if (timeout > 0) {
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                // Check if task is still running
                if (Bukkit.getScheduler().isQueued(taskId[0]) ||
                        Bukkit.getScheduler().isCurrentlyRunning(taskId[0])) {

                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    navigator.cancelNavigation();

                    if (onFailed != null) onFailed.run();
                }
            }, timeout * 20L); // Convert seconds to ticks
        }

        return taskId[0];
    }


    public void walkToNPC(NPC npc, NPC targetNPC, String firstMessage) {
        // Asynchronously get the NPC
        NPCUtils.NPCContext npcContext = plugin.npcUtils.getOrCreateContextForNPC(npc.getName());
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Get the target NPC's location
            Location targetLocation = targetNPC.getEntity().getLocation();

            // Make the NPC navigate to the target NPC
            Navigator navigator = npc.getNavigator();
            navigator.getDefaultParameters().distanceMargin(2.0); // Stop 2 blocks away
            navigator.setTarget(targetLocation); // Set the target location

            // Register event listener for navigation completion
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onNavigationComplete(NavigationCompleteEvent event) {
                    if (event.getNPC().equals(npc)) {
                        // Unregister the listener after completion
                        NavigationCompleteEvent.getHandlerList().unregister(this);

                        // Start the conversation once NPC reaches the target NPC
                        List<NPC> npcs = new ArrayList<>();
                        npcs.add(npc);
                        npcs.add(targetNPC);
                        GroupConversation conversation = plugin.conversationManager.startGroupConversationNoPlayer(npcs);
                        // Send the message to the player
                        conversation.addMessage(new Story.ConversationMessage("system", npc.getName() + ": " + firstMessage));
                        conversation.addMessage(new Story.ConversationMessage("user", targetNPC.getName() + " is listening..."));

                        String colorCode = plugin.randomColor(npc.getName());

                        plugin.broadcastNPCMessage(firstMessage, npc.getName(), false, npc, null, null, npcContext.avatar, colorCode);

                        plugin.conversationManager.generateRadiantResponses(conversation);


                    }
                }
            }, plugin);
        });

    }

}
