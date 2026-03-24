package com.canefe.story.storage

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for storage requests sent to the Go orchestrator via WebSocket.
 */

@Serializable
data class CreateMemoryRequest(
    val requestId: String,
    val method: String = StorageMethod.CREATE_MEMORY,
    val characterId: String,
    val content: String,
    val significance: Double,
)

@Serializable
data class AddRumorRequest(
    val requestId: String,
    val method: String = StorageMethod.ADD_RUMOR,
    val content: String,
    val location: String,
    val significance: Double,
    val gameCreatedAt: Long,
)

@Serializable
data class UpdateRelationshipRequest(
    val requestId: String,
    val method: String = StorageMethod.UPDATE_RELATIONSHIP,
    val characterId: String,
    val memoryId: String,
    val memoryContent: String,
    val memorySignificance: Double,
    val memoryPower: Double,
)

@Serializable
data class SaveCharacterDataRequest(
    val requestId: String,
    val method: String = StorageMethod.SAVE_CHARACTER_DATA,
    val name: String,
    val role: String,
    val context: String,
    val appearance: String,
    val avatar: String,
    val locationName: String?,
)

@Serializable
data class DeleteCharacterDataRequest(
    val requestId: String,
    val method: String = StorageMethod.DELETE_CHARACTER_DATA,
    val characterName: String,
)

@Serializable
data class AssignQuestRequest(
    val requestId: String,
    val method: String = StorageMethod.ASSIGN_QUEST,
    val playerName: String,
    val questId: String,
    val questTitle: String,
    val questDescription: String,
    val npcName: String,
)

@Serializable
data class AssignExistingQuestRequest(
    val requestId: String,
    val method: String = StorageMethod.ASSIGN_EXISTING_QUEST,
    val playerName: String,
    val questId: String,
)

@Serializable
data class UpdateQuestProgressRequest(
    val requestId: String,
    val method: String = StorageMethod.UPDATE_QUEST_PROGRESS,
    val playerName: String,
    val questId: String,
    val objectiveType: String? = null,
    val target: String? = null,
    val progress: Int = 1,
)

@Serializable
data class CompleteQuestRequest(
    val requestId: String,
    val method: String = StorageMethod.COMPLETE_QUEST,
    val playerName: String,
    val questId: String,
)

@Serializable
data class SaveQuestRequest(
    val requestId: String,
    val method: String = StorageMethod.SAVE_QUEST,
    val questId: String,
    val title: String,
    val description: String,
)

@Serializable
data class CreateLocationRequest(
    val requestId: String,
    val method: String = StorageMethod.CREATE_LOCATION,
    val name: String,
    val world: String?,
    val x: Double?,
    val y: Double?,
    val z: Double?,
)

@Serializable
data class SaveLocationRequest(
    val requestId: String,
    val method: String = StorageMethod.SAVE_LOCATION,
    val name: String,
    val description: String,
    val parentLocationName: String?,
    val world: String?,
    val x: Double,
    val y: Double,
    val z: Double,
)

@Serializable
data class SessionActionRequest(
    val requestId: String,
    val method: String,
    val text: String? = null,
    val force: Boolean? = null,
)

@Serializable
data class SaveLoreBookRequest(
    val requestId: String,
    val method: String = StorageMethod.SAVE_LORE_BOOK,
    val name: String,
    val content: String,
)

@Serializable
data class DeleteLoreBookRequest(
    val requestId: String,
    val method: String = StorageMethod.DELETE_LORE_BOOK,
    val name: String,
)

@Serializable
data class StorageCapabilitiesRequest(
    val requestId: String,
    val method: String = StorageMethod.GET_CAPABILITIES,
)

object StorageEventType {
    const val STORAGE_REQUEST = "storage.request"
    const val STORAGE_RESPONSE = "storage.response"
    const val STORAGE_CAPABILITIES = "storage.capabilities"
}

object StorageMethod {
    const val GET_CAPABILITIES = "storage.getCapabilities"

    // NPC
    const val CREATE_MEMORY = "storage.createMemory"
    const val UPDATE_RELATIONSHIP = "storage.updateRelationship"
    const val SAVE_CHARACTER_DATA = "storage.saveCharacterData"
    const val DELETE_CHARACTER_DATA = "storage.deleteCharacterData"

    // Rumors
    const val ADD_RUMOR = "storage.addRumor"

    // Quests
    const val ASSIGN_QUEST = "storage.assignQuest"
    const val ASSIGN_EXISTING_QUEST = "storage.assignExistingQuest"
    const val UPDATE_QUEST_PROGRESS = "storage.updateQuestProgress"
    const val COMPLETE_QUEST = "storage.completeQuest"
    const val SAVE_QUEST = "storage.saveQuest"

    // Locations
    const val CREATE_LOCATION = "storage.createLocation"
    const val SAVE_LOCATION = "storage.saveLocation"

    // Sessions
    const val START_SESSION = "storage.startSession"
    const val END_SESSION = "storage.endSession"
    const val FEED_SESSION = "storage.feedSession"

    // Lore
    const val SAVE_LORE_BOOK = "storage.saveLoreBook"
    const val DELETE_LORE_BOOK = "storage.deleteLoreBook"
}
