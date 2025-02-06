package com.canefe.story;

import org.bukkit.Bukkit;

import java.util.*;

public class NPCContextGenerator {

    private static NPCContextGenerator instance;
    private final Story plugin;

    private NPCContextGenerator(Story plugin) {
        this.plugin = plugin;
    }

    public static NPCContextGenerator getInstance(Story plugin) {
        if (instance == null) {
            instance = new NPCContextGenerator(plugin);
        }

        return instance;
    }

    public String generateDefaultContext(String npcName, String role, int hours, int minutes, String season, String date) {
        Random random = new Random();

        // Randomly select personality traits
        // plugin.getTraitList() is a method that returns a list of traits
        String trait = plugin.getTraitList().get(random.nextInt(plugin.getTraitList().size()));
        String quirk = plugin.getQuirkList().get(random.nextInt(plugin.getQuirkList().size()));
        String motivation = plugin.getMotivationList().get(random.nextInt(plugin.getMotivationList().size()));
        String flaw = plugin.getFlawList().get(random.nextInt(plugin.getFlawList().size()));
        String tone = plugin.getToneList().get(random.nextInt(plugin.getToneList().size()));


        // Construct personality description
        String personality = " is " + trait + ", has the quirk of " + quirk +
                ", is motivated by " + motivation + ", and their flaw is " + flaw +
                ". They speak in a " + tone + " tone.";

        // Construct the context
        return  npcName + personality +
                " The time is " + hours + ":" + String.format("%02d", minutes) + " in the " + season;

    }

    public String updateContext(String context, String npcName, int hours, int minutes, String season, String date) {
        Bukkit.getLogger().info("Updating context for NPC: " + npcName);
        context = context.replaceAll("The time is \\d{1,2}:\\d{2}", "The time is " + hours + ":" + String.format("%02d", minutes));
        context = context.replaceAll("in the \\w+", "in the " + season);
        context = context.replaceAll("The date is \\d{4}-\\d{2}-\\d{2}", "The date is " + date);
        return context;
    }
}

