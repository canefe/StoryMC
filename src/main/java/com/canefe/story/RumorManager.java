package com.canefe.story;

import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RumorManager {
    private final Story plugin;
    private static RumorManager instance;

    private RumorManager(Story plugin) {
        this.plugin = plugin;
    }

    public static RumorManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new RumorManager(plugin);
        }
        return instance;
    }

    /**
     * Analyzes a conversation to extract significant information
     * that should be remembered by NPCs or spread as rumors
     */
    public void processConversationSignificance(List<Story.ConversationMessage> history, List<String> npcNames, String locationName) {
        if (history.isEmpty()) return;

        // Create a prompt for the AI to analyze the conversation
        List<Story.ConversationMessage> prompts = new ArrayList<>();

        // Add system prompt explaining the task
        prompts.add(new Story.ConversationMessage("system",
                "Analyze the following conversation and identify any significant information that should be:" +
                        "\n1. Remembered by the specific NPCs involved (personal knowledge)" +
                        "\n2. Spread as rumors throughout the location (location-based knowledge)" +
                        "\n3. Ignored as trivial conversation" +
                        "\nLocation: " + locationName +
                        "\nNPCs involved: " + String.join(", ", npcNames) +
                        "\nFor each significant piece of information, format your response like this:" +
                        "\n---" +
                        "\nType: [PERSONAL or RUMOR]" +
                        "\nTarget: [NPC name or 'location']" +
                        "\nImportance: [LOW, MEDIUM, HIGH]" +
                        "\nInformation: [Concise description of what should be remembered]" +
                        "\n---" +
                        "\nIf nothing significant occurred, respond with 'Nothing significant.'"
        ));

        // Add conversation history
        prompts.addAll(history);

        // Send to AI for analysis
        CompletableFuture.runAsync(() -> {
            try {
                String analysisResult = plugin.getAIResponse(prompts);
                if (analysisResult != null && !analysisResult.isEmpty() &&
                        !analysisResult.contains("Nothing significant")) {
                    processSignificanceResults(analysisResult, npcNames, locationName);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error analyzing conversation significance: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Process the AI's analysis of significant information
     */
    private void processSignificanceResults(String analysis, List<String> npcNames, String locationName) {
        // Split the analysis into separate findings
        String[] findings = analysis.split("---");

        for (String finding : findings) {
            if (finding.trim().isEmpty()) continue;

            Map<String, String> info = parseSignificanceInfo(finding);
            if (info.isEmpty()) continue;

            String type = info.get("Type");
            String target = info.get("Target");
            String importance = info.get("Importance");
            String information = info.get("Information");

            if (type == null || target == null || information == null) continue;

            // Process based on type
            if ("PERSONAL".equalsIgnoreCase(type)) {
                // Split target by comma to handle multiple NPCs
                String[] targetNpcs = target.split(",\\s*");

                for (String singleTarget : targetNpcs) {
                    // Only add personal knowledge if target is a valid NPC name
                    if (npcNames.contains(singleTarget.trim())) {
                        addPersonalKnowledge(singleTarget.trim(), information, importance);
                    } else {
                        plugin.getLogger().warning("Ignoring personal knowledge for unknown NPC: " + singleTarget);
                    }
                }
            } else if ("RUMOR".equalsIgnoreCase(type)) {
                // Rumor to spread in location
                if ("location".equalsIgnoreCase(target)) {
                    addLocationRumor(locationName, information, importance);
                } else {
                    // Split target by comma to handle multiple NPCs
                    String[] targetNpcs = target.split(",\\s*");
                    boolean validTargetFound = false;

                    for (String singleTarget : targetNpcs) {
                        singleTarget = singleTarget.trim();
                        // Only if target is a valid NPC name
                        if (npcNames.contains(singleTarget)) {
                            addPersonalKnowledge(singleTarget, information, importance);
                            validTargetFound = true;
                        }
                    }

                    // Add location rumor only once, if at least one valid NPC was found
                    if (validTargetFound) {
                        addLocationRumor(locationName, "Rumor: " + information, importance);
                    } else {
                        // If no valid targets, just add it as location rumor
                        plugin.getLogger().warning("No valid NPCs found in target: " + target + ", treating as location rumor");
                        addLocationRumor(locationName, information, importance);
                    }
                }
            }
        }
    }

    /**
     * Parse the AI output into structured information
     */
    private Map<String, String> parseSignificanceInfo(String finding) {
        Map<String, String> info = new HashMap<>();
        String[] lines = finding.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(":", 2);
            if (parts.length != 2) continue;

            String key = parts[0].trim();
            String value = parts[1].trim();

            if (key.equalsIgnoreCase("Type") ||
                    key.equalsIgnoreCase("Target") ||
                    key.equalsIgnoreCase("Importance") ||
                    key.equalsIgnoreCase("Information")) {
                info.put(key, value);
            }
        }

        return info;
    }

    /**
     * Add personal knowledge to an NPC's context
     */
    private void addPersonalKnowledge(String npcName, String information, String importance) {
        try {
            // Get the NPC's existing context
            NPCUtils.NPCContext npcContext = NPCUtils.getInstance(plugin).getOrCreateContextForNPC(npcName);
            if (npcContext == null) return;

            // Determine prefix based on importance
            String prefix = "";
            if ("HIGH".equalsIgnoreCase(importance)) {
                prefix = "Important knowledge: ";
            } else if ("MEDIUM".equalsIgnoreCase(importance)) {
                prefix = "Notable memory: ";
            } else {
                prefix = "Memory: ";
            }

            // Append the new information to the NPC's context
            String updatedContext = npcContext.context + "\n" + prefix + information;

            // Save updated context
            plugin.saveNPCData(npcName, npcContext.npcRole, updatedContext, npcContext.conversationHistory,
                    npcContext.location != null ? npcContext.location.getName() : "Village");

            plugin.getLogger().info("Added personal knowledge to " + npcName + ": " + information);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add personal knowledge to " + npcName + ": " + e.getMessage());
        }
    }

    /**
     * Add a rumor to a location's context
     */
    private void addLocationRumor(String locationName, String information, String importance) {
        try {
            // Get the location from the location manager
            StoryLocation location = plugin.locationManager.getLocation(locationName);
            if (location == null) return;

            // Determine prefix based on importance
            String prefix = "";
            if ("HIGH".equalsIgnoreCase(importance)) {
                prefix = "Major news: ";
            } else if ("MEDIUM".equalsIgnoreCase(importance)) {
                prefix = "Local rumor: ";
            } else {
                prefix = "Minor gossip: ";
            }

            // Add the rumor to the location's context list
            List<String> currentContext = location.getContext();
            currentContext.add(prefix + information);

            // Save the updated location
            plugin.locationManager.saveLocation(location);

            plugin.getLogger().info("Added rumor to " + locationName + ": " + information);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add rumor to " + locationName + ": " + e.getMessage());
        }
    }
}