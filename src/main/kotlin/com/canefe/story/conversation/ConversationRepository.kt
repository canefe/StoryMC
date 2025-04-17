package com.canefe.story.conversation

import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

class ConversationRepository {
    private val activeConversations: MutableList<Conversation> = mutableListOf()
    private val nextConversationId = AtomicInteger(1)

    fun addConversation(conversation: Conversation): Int {
        val newId = nextConversationId.getAndIncrement()
        conversation.id = newId
        activeConversations.add(conversation)
        return newId
    }

    fun removeConversation(conversation: Conversation) {
        activeConversations.remove(conversation)
    }

    fun getConversationByPlayer(player: Player): Conversation? {
        return activeConversations.find { it.hasPlayer(player) }
    }

    fun getConversationByNPC(npc: NPC): Conversation? {
        return activeConversations.find { it.hasNPC(npc) }
    }

    fun getConversationByNPC(npcName: String): Conversation? {
        return activeConversations.find { it.hasNPC(npcName) }
    }

    fun getConversationById(id: Int): Conversation? {
        return activeConversations.find { it.id == id }
    }

    fun getAllActiveConversations(): List<Conversation> {
        return activeConversations.toList()
    }
}