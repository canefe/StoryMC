package com.canefe.story.api.event

import com.canefe.story.conversation.Conversation
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

// Define a sealed interface for conversation participants
sealed interface ConversationParticipant {
    // Common properties or methods could go here
}

// Implement the interface for both types
data class PlayerParticipant(
    val player: Player,
) : ConversationParticipant

data class NPCParticipant(
    val npc: NPC,
) : ConversationParticipant

class ConversationJoinEvent(
    val conversation: Conversation,
    val participant: ConversationParticipant,
) : Event(),
    Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        this.cancelled = cancel
    }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }

    override fun getHandlers(): HandlerList = HANDLERS
}
