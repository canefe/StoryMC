package com.canefe.story.webui

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import java.util.*

// Main DTO for the entire plugin state
data class PluginStateDTO(
	val conversations: List<ConversationDTO>,
	val npcs: List<NPCContextDTO>,
)

// DTOs for conversations
data class ConversationDTO(
	val id: Int,
	val npcNames: List<String>,
	val playerNames: List<String>,
	val messages: List<ConversationMessageDTO>,
)

data class ConversationMessageDTO(
	val role: String,
	val content: String,
	val timestamp: Long = System.currentTimeMillis(),
)

// DTOs for NPCs
data class NPCContextDTO(
	val name: String,
	val role: String,
	val context: String,
	val locationName: String,
	val avatar: String?,
	val memories: List<MemoryDTO>,
)

data class MemoryDTO(
	val content: String,
	val power: Double,
	val created: Long,
	val gameCreatedAt: Long,
	val lastAccessed: Long,
)

// DTOs for locations
data class StoryLocationDTO(
	val name: String,
	val parentLocationName: String?,
	val isSubLocation: Boolean,
	val context: List<String>,
	val allowedNPCs: List<String>,
	val worldName: String?,
	val x: Double?,
	val y: Double?,
	val z: Double?,
)

data class LeaderDTO(
	val name: String,
	val title: String,
	val diplomacy: Int,
	val martial: Int,
	val stewardship: Int,
	val intrigue: Int,
	val charisma: Int,
	val traits: List<LeaderTraitDTO>,
)

data class LeaderTraitDTO(
	val name: String,
	val description: String,
)

data class SettlementActionDTO(
	val timestamp: Long,
	val description: String,
	val effects: Map<String, Double>,
)

// Conversation extensions
fun Conversation.toDTO(): ConversationDTO =
	ConversationDTO(
		id = id,
		npcNames = npcNames,
		playerNames = players.map { it.toString() },
		messages = history.map { it.toDTO() },
	)

fun ConversationMessage.toDTO(): ConversationMessageDTO =
	ConversationMessageDTO(
		role = role,
		content = content,
		timestamp = timestamp,
	)

fun Conversation.applyFromDTO(
	dto: ConversationDTO,
	plugin: Story,
) {
	this.clearHistory()

	for (dtoMessage in dto.messages) {
		val message = dtoMessage.toDomain()
		this.addMessage(message)
	}
}

fun NPCData.toDTO(): NPCContextDTO =
	NPCContextDTO(
		name = name,
		role = role,
		context = context,
		locationName = storyLocation?.name ?: "Unknown",
		avatar = avatar,
		memories = memory.map { it.toDTO() },
	)

fun Memory.toDTO(): MemoryDTO =
	MemoryDTO(
		content = content,
		power = power,
		created = realCreatedAt.toEpochMilli(),
		gameCreatedAt = gameCreatedAt,
		lastAccessed = lastAccessed,
	)

fun ConversationMessageDTO.toDomain(): ConversationMessage =
	ConversationMessage(
		role = role,
		content = content,
		timestamp = timestamp,
	)
