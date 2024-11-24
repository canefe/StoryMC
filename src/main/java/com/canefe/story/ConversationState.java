package com.canefe.story;

public class ConversationState {
    private final String npcName;
    private boolean active;

    public ConversationState(String npcName, boolean active) {
        this.npcName = npcName;
        this.active = active;
    }

    public String getNpcName() {
        return npcName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
