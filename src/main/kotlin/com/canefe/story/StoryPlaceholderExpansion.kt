package com.canefe.story

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

class StoryPlaceholderExpansion(private val plugin: Story) : PlaceholderExpansion() {
	override fun getAuthor(): String {
		return "canefe"
	}

	override fun getIdentifier(): String {
		return "story"
	}

	override fun getVersion(): String {
		return "1.0.0"
	}

	override fun persist(): Boolean {
		return true
	}

	override fun onRequest(
		player: OfflinePlayer?,
		params: String,
	): String? {
		if (player == null) {
			return ""
		}

		when {
			params.equals("quest_title", ignoreCase = true) -> {
				return if (player.isOnline) {
					player.player?.let { plugin.playerManager.getQuestTitle(it) }
				} else {
					""
				}
			}
			params.equals("quest_objective", ignoreCase = true) -> {
				return if (player.isOnline) {
					player.player?.let { plugin.playerManager.getQuestObjective(it) }
				} else {
					"> No quests active."
				}
			}
		}

		return null
	}
}
