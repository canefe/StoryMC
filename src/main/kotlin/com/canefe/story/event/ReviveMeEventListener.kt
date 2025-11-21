package com.canefe.story.event

import com.canefe.story.Story
import com.canefe.story.util.EssentialsUtils
import net.kokoricraft.reviveme.events.PlayerDownedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class ReviveMeEventListener(
    private val plugin: Story,
) : Listener {
    @EventHandler
    fun onPlayerDowned(event: PlayerDownedEvent) {
        val player = event.player
        val enemy =
            event.enemy?.let {
                EssentialsUtils.getNickname(it.name)
            } ?: event.cause.name

        // Check if in conversation
        val conversation =
            plugin.conversationManager.getConversation(player)
                ?: return

        conversation.addSystemMessage(
            "${EssentialsUtils.getNickname(
                player.name,
            )} fell on the ground, downed by $enemy. They need to be revived.",
        )
    }
}
