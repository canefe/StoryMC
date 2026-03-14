package com.canefe.story.conversation.theme

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import org.bukkit.Bukkit

class ViolenceTheme : ConversationTheme() {
    override val name: String = NAME
    override val displayName: String = "Violence"
    override val description: String = "Combat, fighting, threats, or physical aggression. Activate when violence occurs or is imminent."
    override val compatibleWith: Set<String> = setOf(ChatTheme.NAME)

    private val sentinelAvailable: Boolean by lazy {
        try {
            Class.forName("org.mcmonkey.sentinel.SentinelTrait")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    override fun onActivate(conversation: Conversation) {
        if (!sentinelAvailable) return

        val plugin = Story.instance
        val npcs = conversation.npcs
        val playerUUIDs = conversation.players

        if (npcs.isEmpty() || playerUUIDs.isEmpty()) return

        // Make each NPC attack each player in the conversation
        for (npc in npcs) {
            for (playerUUID in playerUUIDs) {
                val player = Bukkit.getPlayer(playerUUID) ?: continue
                plugin.askForPermission(
                    "Violence theme activated — make <gold>${npc.name}</gold> attack <red>${player.name}</red>?",
                    onAccept = {
                        npc.attack(player)
                        if (plugin.config.debugMessages) {
                            plugin.logger.info("[ViolenceTheme] ${npc.name} is now attacking ${player.name}")
                        }
                    },
                    onRefuse = {},
                )
            }
        }
    }

    override fun onDeactivate(conversation: Conversation) {
        if (!sentinelAvailable) return

        val plugin = Story.instance
        val npcs = conversation.npcs
        val playerUUIDs = conversation.players

        // Stop NPCs from attacking players when violence theme ends
        for (npc in npcs) {
            for (playerUUID in playerUUIDs) {
                val player = Bukkit.getPlayer(playerUUID) ?: continue
                npc.stopAttacking(player)
            }
            if (plugin.config.debugMessages) {
                plugin.logger.info("[ViolenceTheme] ${npc.name} stopped attacking")
            }
        }
    }

    companion object {
        const val NAME = "violence"
    }
}
