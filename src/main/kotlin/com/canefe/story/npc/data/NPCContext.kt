package com.canefe.story.npc.data

import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory
/**
 * Represents contextual information about an NPC including
 * their role, location, relationships, and conversation history.
 */
data class NPCContext(
    val name: String,
    val role: String,
    val context: String,
    val relations: Map<String, Int>,
    val location: StoryLocation?,
    val avatar: String,
    val memories: List<Memory>
) {
    // Helper methods for memory access
    private fun getStrongestMemories(limit: Int = 5): List<Memory> {
        return memories.sortedByDescending { it.getCurrentStrength() }.take(limit)
    }

    private fun getRecentMemories(limit: Int = 5): List<Memory> {
        return memories.sortedByDescending { it.createdAt }.take(limit)
    }

    // Convert memories to a format suitable for AI prompts
    fun getMemoriesForPrompt(limit: Int = 10): String {
        // add both strongest and recent memories filter out same ones
        val relevantMemories = (getStrongestMemories(limit) + getRecentMemories(limit)).distinctBy { it.id }

        return if (relevantMemories.isEmpty()) {
            "No significant memories."
        } else {
            relevantMemories.joinToString("\n\n") { "Memory (strength: ${String.format("%.2f", it.getCurrentStrength())}): ${it.content}" }
        }
    }
}