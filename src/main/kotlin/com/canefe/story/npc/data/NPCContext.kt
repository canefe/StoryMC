package com.canefe.story.npc.data

import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory
import com.canefe.story.npc.relationship.Relationship
import com.canefe.story.util.TimeService

/**
 * Represents contextual information about an NPC including
 * their role, location, relationships, and conversation history.
 */
data class NPCContext(
	val name: String,
	val role: String,
	val context: String,
	val appearance: String = "",
	val location: StoryLocation?,
	val avatar: String,
	val memories: List<Memory>,
	val relationships: Map<String, Relationship> = emptyMap(),
) {
	// Helper methods for memory access that accept timeService as parameter
	private fun getStrongestMemories(
		timeService: TimeService,
		limit: Int = 5,
	): List<Memory> = memories.sortedByDescending { it.getCurrentStrength(timeService) }.take(limit)

	private fun getRecentMemories(limit: Int = 5): List<Memory> =
		memories
			.sortedByDescending {
				it.gameCreatedAt
			}.take(limit)

	// Convert memories to a format suitable for AI prompts, including relationship information
	fun getMemoriesForPrompt(
		timeService: TimeService,
		limit: Int = 10,
	): String {
		// Add both strongest and recent memories, filter out duplicates
		val relevantMemories = (getStrongestMemories(timeService, limit) + getRecentMemories(limit)).distinctBy { it.id }

		val memoryText =
			if (relevantMemories.isEmpty()) {
				"No significant memories."
			} else {
				relevantMemories.joinToString("\n\n") {
					"Memory (strength: ${String.format("%.2f", it.getCurrentStrength(timeService))}): ${it.content}"
				}
			}

		// Add important relationship information
		val relationshipText =
			if (relationships.isEmpty()) {
				""
			} else {
				val importantRelationships =
					relationships.values
						.sortedByDescending { Math.abs(it.score) }
						.take(5)

				"\n\nRelationships:\n" +
					importantRelationships.joinToString("\n") { relationship ->
						"- ${relationship.targetName}: ${relationship.describe()}"
					}
			}

		return "$memoryText$relationshipText"
	}
}
