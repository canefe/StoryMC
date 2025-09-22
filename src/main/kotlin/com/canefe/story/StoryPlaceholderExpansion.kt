package com.canefe.story

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class StoryPlaceholderExpansion(
    private val plugin: Story,
) : PlaceholderExpansion() {
    override fun getAuthor(): String = "canefe"

    override fun getIdentifier(): String = "story"

    override fun getVersion(): String = "1.0.0"

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onPlaceholderRequest(
        player: Player?,
        params: String,
    ): String? {
        if (player == null) {
            return ""
        }

        when {
            params.equals("quest_title", ignoreCase = true) -> {
                return player.player?.let { plugin.playerManager.getQuestTitle(it) }
            }
            params.equals("quest_objective", ignoreCase = true) -> {
                return player.player?.let { plugin.playerManager.getQuestObjective(it) }
            }
        }

        return null
    }
}
