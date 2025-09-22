package com.canefe.story.conversation

import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

class ConversationRepository {
    private val activeConversations: MutableList<Conversation> = mutableListOf()
    private val nextConversationId = AtomicInteger(1)
    val lockedConversations: MutableList<Int> = mutableListOf()

    fun addConversation(conversation: Conversation): Int {
        val newId = nextConversationId.getAndIncrement()
        conversation.id = newId
        activeConversations.add(conversation)
        return newId
    }

    fun removeConversation(conversation: Conversation) {
        activeConversations.remove(conversation)
    }

    fun getConversationByPlayer(player: Player): Conversation? = activeConversations.find { it.hasPlayer(player) }

    fun getConversationByNPC(npc: NPC): Conversation? = activeConversations.find { it.hasNPC(npc) }

    fun getConversationByNPC(npcName: String): Conversation? = activeConversations.find { it.hasNPC(npcName) }

    fun getConversationById(id: Int): Conversation? = activeConversations.find { it.id == id }

    fun getAllActiveConversations(): List<Conversation> = activeConversations.toList()
}
