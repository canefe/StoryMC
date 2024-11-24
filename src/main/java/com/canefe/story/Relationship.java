package com.canefe.story;

public class Relationship {
    private String mood; // e.g., "neutral", "angry", "friendly"
    private int trustLevel; // e.g., 0 to 100

    public Relationship(String mood, int trustLevel) {
        this.mood = mood;
        this.trustLevel = trustLevel;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public int getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(int trustLevel) {
        this.trustLevel = trustLevel;
    }
}

