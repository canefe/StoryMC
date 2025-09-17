package com.canefe.story.npc.data

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
    var knowledgeCategories: List<String> = listOf()
    var appearance: String = ""
    var randomPathing: Boolean = true
    var customVoice: String? = null // Custom voice ID for this NPC
    var generic: Boolean = false // Generic NPCs have no memories and generate temporary personalities

    // New fields for name aliasing system
    var nameBank: String? = null // Name bank to use for alias generation (e.g., "alboran.guard")
    var npcId: String? = null // Unique identifier for this NPC (Citizens ID, MythicMob UUID, etc.)
    var anchorKey: String? = null // Anchor key for deterministic generation (spawner_id, region_cell, etc.)
    var canonicalName: String? = null // Full name for dialogue (e.g., "Arik Mossveil")
    var displayHandle: String? = null // Short name for MC display (e.g., "A. Mossveil")
    var callsign: String? = null // Optional differentiator (e.g., "Spearhand")

    // Helper method to add a memory
    fun addMemory(
        content: String,
        power: Double = 1.0,
    ): Memory {
        val memory = Memory(content = content, power = power)
        this.memory.add(memory)
        return memory
    }

    override fun toString(): String = "NPCData{name=$name, role=$role, location=$storyLocation, context=$context}"
}
