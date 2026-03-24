package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.util.*
import net.citizensnpcs.api.CitizensAPI
import net.kokoricraft.reviveme.events.PlayerDownedEvent
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class ReviveMeEventListener(
    private val plugin: Story,
) : Listener {
    @EventHandler
    fun onPlayerDowned(event: PlayerDownedEvent) {
        val player = event.player
        val enemy = event.enemy?.let { getEntityName(it) } ?: event.cause.name

        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        val playerName = getEntityName(player)
        conversation.addSystemMessage(
            "$playerName fell on the ground, downed by $enemy. They need to be revived.",
        )
    }

    private fun getEntityName(entity: Entity): String {
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
                return CitizensAPI.getNPCRegistry().getNPC(entity).name
            }
        } catch (_: Exception) {
        }

        if (entity is Player) {
            return try {
                if (entity is Player) entity.characterName else entity.name
            } catch (_: Exception) {
                entity.name
            }
        }

        return entity.name
    }
}
