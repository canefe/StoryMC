package com.canefe.story;

import org.bukkit.Bukkit;

import java.util.*;

public class NPCContextGenerator {

    private static final List<String> TRAITS = Arrays.asList(
            "brave", "cunning", "cowardly", "loyal", "deceitful", "kind", "selfish", "compassionate",
            "apathetic", "hot-tempered", "calculating", "stubborn", "ambitious", "charismatic",
            "reserved", "resourceful", "daring", "pessimistic", "idealistic", "practical", "scheming",
            "patient", "impulsive"
    );

    private static final List<String> QUIRKS = Arrays.asList(
            "hates authority", "collects useless objects", "always suspicious", "has a dark sense of humor",
            "obsessed with cleanliness", "talks in riddles", "hoards shiny objects", "fearful of water",
            "constantly fidgets", "addicted to gambling", "loves to sing, even at inappropriate times",
            "refers to themselves in third person", "refuses to lie, even when they should",
            "obsessed with documenting every detail of their life", "superstitious to the point of paranoia",
            "obsessed with food and always snacking", "takes everything literally",
            "constantly quotes ancient proverbs", "has an annoying laugh", "cannot resist a dare"
    );

    private static final List<String> MOTIVATIONS = Arrays.asList(
            "revenge", "greed", "self-preservation", "honor", "power", "freedom", "love", "curiosity",
            "survival", "recognition", "adventure", "family loyalty", "faith or religious duty", "justice",
            "chaos for chaos’s sake", "personal redemption", "knowledge", "glory in battle",
            "protecting their home", "escaping their past", "overcoming a curse or prophecy",
            "building a legacy", "fear of being forgotten"
    );

    private static final List<String> FLAWS = Arrays.asList(
            "arrogance", "naivety", "paranoia", "laziness", "recklessness", "jealousy", "short-tempered",
            "greedy", "self-doubt", "overly trusting", "easily manipulated", "fearful", "stubborn to a fault",
            "vengeful", "prone to lying", "overly competitive", "prideful", "obsessive",
            "emotionally distant", "impulsive", "cruel under pressure", "haughty", "cowardly in danger",
            "judgmental"
    );

    private static final List<String> TONES = Arrays.asList(
            "sarcastic", "blunt", "formal", "playful", "serious", "melancholic", "cheerful", "apathetic",
            "arrogant", "cautious", "dismissive", "flirtatious", "mischievous", "pessimistic", "optimistic",
            "passionate", "calm", "distant", "mocking", "cryptic", "eager", "conflicted", "suspicious",
            "humorous", "desperate"
    );

    public static String generateDefaultContext(String npcName, String role, int hours, int minutes, String season, String date) {
        Random random = new Random();

        // Randomly select personality traits
        String trait = TRAITS.get(random.nextInt(TRAITS.size()));
        String quirk = QUIRKS.get(random.nextInt(QUIRKS.size()));
        String motivation = MOTIVATIONS.get(random.nextInt(MOTIVATIONS.size()));
        String flaw = FLAWS.get(random.nextInt(FLAWS.size()));
        String tone = TONES.get(random.nextInt(TONES.size()));

        // Construct personality description
        String personality = "This character is " + trait + ", has the quirk of " + quirk +
                ", is motivated by " + motivation + ", and their flaw is " + flaw +
                ". They speak in a " + tone + " tone.";

        // Construct the context
        return "This is a conversation with " + npcName + " in a medieval Minecraft universe; trades are through Minecraft items, and " +
                npcName + " is talking. " + personality +
                " This character’s personality, quirks, and motivations should drive their responses, which must feel natural, grounded, and reflective of human emotions. " +
                "The time is " + hours + ":" + String.format("%02d", minutes) + " in the " + season +
                ". The date is " + date +
                ". Do not include the name of the NPC in the response. Just their dialogue is allowed as output. " +
                "Responses must prioritize realism and emotional authenticity, dynamically reflecting the stakes of the situation and the NPC’s relationship with the player. " +
                "Personality quirks and tone should flavor responses but never override human-like decision-making, especially in critical or escalating situations. " +
                "Responses should remain concise and consistent with the NPC’s personality, emotional state, and current context. " +
                "In escalating scenarios (e.g., threats, demands, or violence), NPCs must prioritize self-preservation and adjust their tone to match the severity of the situation. " +
                "They may comply, negotiate, or retaliate depending on their motivations, flaws, and emotions, but they must always act with a clear and believable sense of self-interest and survival. " +
                "Responses must not exceed 20 words unless the personality specifically calls for elaborate speech." +
                "NPCs must avoid repetitive or evasive behavior in high-stakes situations, responding directly when necessary while maintaining their distinct personality." +
                "Take into account the name of the person you are talking to, and adjust your responses accordingly.";
    }

    public static String updateContext(String context, String npcName, int hours, int minutes, String season, String date) {
        Bukkit.getLogger().info("Updating context for NPC: " + npcName);
        context = context.replaceAll("The time is \\d{1,2}:\\d{2}", "The time is " + hours + ":" + String.format("%02d", minutes));
        context = context.replaceAll("in the \\w+", "in the " + season);
        context = context.replaceAll("The date is \\d{4}-\\d{2}-\\d{2}", "The date is " + date);
        return context;
    }
}

