package com.canefe.story;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class StoryPlaceholderExpansion extends PlaceholderExpansion {

    private final Story plugin;

    public StoryPlaceholderExpansion(Story plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getAuthor() {
        return "canefe";
    }

    @Override
    public String getIdentifier() {
        return "story";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        if (params.equalsIgnoreCase("quest_title")) {
            if (player.isOnline()) {
                return plugin.getQuestTitle(player.getPlayer());
            }
            return "";
        }

        if (params.equalsIgnoreCase("quest_objective")) {
            if (player.isOnline()) {
                return plugin.getQuestObj(player.getPlayer());
            }
            return "> No quests active.";
        }

        return null;
    }
}