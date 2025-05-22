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
