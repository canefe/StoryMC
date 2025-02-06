package com.canefe.story;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;

import java.util.*;

import java.util.ArrayList;
import java.util.List;

public class GroupConversation {

    private final List<UUID> players;
    private final List<String> npcNames;
    private final List<NPC> npcs;
    private final List<Story.ConversationMessage> conversationHistory;
    private boolean active;

    public GroupConversation(List<UUID> players, List<NPC> initialNPCs) {
        this.players = players;
        this.npcs = new ArrayList<>(initialNPCs);
        this.npcNames = new ArrayList<>();
        for (NPC npc : initialNPCs) {
            npcNames.add(npc.getName());
        }
        this.conversationHistory = new ArrayList<>();
        this.active = true;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public List<String> getNpcNames() {
        return new ArrayList<>(npcNames);
    }

    public List<NPC> getNPCs() {
        return new ArrayList<>(npcs);
    }

    public boolean addNPC(NPC npc) {
        String npcName = npc.getName();
        npcs.add(npc);

        if (!npcNames.contains(npcName)) {
            npcNames.add(npcName);
            return true;
        }
        return false;
    }

    public boolean removeNPC(NPC npc) {
        String npcName = npc.getName();
        npcs.remove(npc);

        if (npcNames.contains(npcName)) {
            npcNames.remove(npcName);
            return true;
        }
        return false;
    }

    public boolean addPlayerToConversation(Player player) {
        // Join the player to another player's conversation
        if (!players.contains(player.getUniqueId())) {
            players.add(player.getUniqueId());
            return true;
        }

        return false;
    }

    public List<Story.ConversationMessage> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    public void addMessage(Story.ConversationMessage message) {
        conversationHistory.add(message);
    }

    public List<UUID> getPlayers() {
        return new ArrayList<>(players);
    }
}


