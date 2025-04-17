package com.canefe.story.npc.data

import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory

data class NPCData(
    var name: String,
    var role: String,
    var storyLocation: StoryLocation?,
    var context: String,
) {
    var memory: MutableList<Memory> = mutableListOf()
    var avatar: String = ""
    var relations: Map<String, Int> = hashMapOf()
    var knowledgeCategories: List<String> = listOf()


    // Helper method to add a memory
    fun addMemory(content: String, power: Double = 1.0): Memory {
        val memory = Memory(content = content, power = power)
        this.memory.add(memory)
        return memory
    }

    // Helper method to get memories sorted by strength
    fun getMemoriesByStrength(limit: Int? = null): List<Memory> {
        val sortedMemories = memory.sortedByDescending { it.getCurrentStrength() }
        return limit?.let { sortedMemories.take(it) } ?: sortedMemories
    }


    override fun toString(): String {
        return "NPCData{name=$name, role=$role, location=$storyLocation, context=$context}"
    }
}
