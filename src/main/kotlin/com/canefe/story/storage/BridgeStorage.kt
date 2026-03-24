package com.canefe.story.storage

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.bridge.StoryEvent
import com.canefe.story.bridge.StoryEventBus
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.npc.memory.Memory
import com.canefe.story.quest.Quest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bridge implementation of [StoryStorage] that delegates writes to the Go orchestrator
 * via WebSocket. Uses the same capabilities pattern as BridgeIntelligence.
 * Falls back to [LocalStorage] for unsupported methods.
 */
class BridgeStorage(
    private val plugin: Story,
    private val local: LocalStorage,
    private val eventBus: StoryEventBus,
) : StoryStorage {
    private val pendingRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val supportedMethods = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private val json = Json { encodeDefaults = true }
    }

    init {
        eventBus.onType(StorageEventType.STORAGE_RESPONSE) { event ->
            val data = event.toWireData() ?: return@onType
            val requestId = data["requestId"]?.toString()?.trim('"') ?: return@onType
            pendingRequests.remove(requestId)?.complete(data)
        }

        eventBus.onType(StorageEventType.STORAGE_CAPABILITIES) { event ->
            val data = event.toWireData() ?: return@onType
            val methods = data["methods"]
            if (methods is JsonArray) {
                supportedMethods.clear()
                methods.forEach { element ->
                    supportedMethods.add(element.toString().trim('"'))
                }
                plugin.logger.info("Bridge storage capabilities: $supportedMethods")
            }
        }
    }

    fun requestCapabilities() {
        val dto = StorageCapabilitiesRequest(requestId = UUID.randomUUID().toString())
        emitDto(dto)
        plugin.logger.info("Requested storage capabilities from bridge")
    }

    private fun isSupported(method: String): Boolean = supportedMethods.contains(method)

    // ── NPC Memory ──────────────────────────────────────────────────────

    override fun createMemory(
        npcName: String,
        content: String,
        significance: Double,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.CREATE_MEMORY)) return local.createMemory(npcName, content, significance)

        val dto =
            CreateMemoryRequest(
                requestId = UUID.randomUUID().toString(),
                npcName = npcName,
                content = content,
                significance = significance,
            )
        return fireAndForget(dto.requestId, dto) { local.createMemory(npcName, content, significance) }
    }

    override fun updateRelationshipFromMemory(
        memory: Memory,
        npcName: String,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.UPDATE_RELATIONSHIP)) return local.updateRelationshipFromMemory(memory, npcName)

        val dto =
            UpdateRelationshipRequest(
                requestId = UUID.randomUUID().toString(),
                npcName = npcName,
                memoryId = memory.id,
                memoryContent = memory.content,
                memorySignificance = memory.significance,
                memoryPower = memory.power,
            )
        return fireAndForget(dto.requestId, dto) { local.updateRelationshipFromMemory(memory, npcName) }
    }

    // ── NPC Data ────────────────────────────────────────────────────────

    override fun saveCharacterData(character: CharacterDTO): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.SAVE_CHARACTER_DATA)) return local.saveCharacterData(character)

        val dto =
            SaveCharacterDataRequest(
                requestId = UUID.randomUUID().toString(),
                name = character.name,
                role = character.role,
                context = character.context,
                appearance = character.appearance,
                avatar = character.avatar,
                locationName = character.locationName,
            )
        return fireAndForget(dto.requestId, dto) { local.saveCharacterData(character) }
    }

    override fun deleteCharacterData(characterName: String): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.DELETE_CHARACTER_DATA)) return local.deleteCharacterData(characterName)

        val dto =
            DeleteCharacterDataRequest(
                requestId = UUID.randomUUID().toString(),
                characterName = characterName,
            )
        return fireAndForget(dto.requestId, dto) { local.deleteCharacterData(characterName) }
    }

    // ── Rumors ──────────────────────────────────────────────────────────

    override fun addRumor(
        content: String,
        location: String,
        significance: Double,
        gameCreatedAt: Long,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.ADD_RUMOR)) return local.addRumor(content, location, significance, gameCreatedAt)

        val dto =
            AddRumorRequest(
                requestId = UUID.randomUUID().toString(),
                content = content,
                location = location,
                significance = significance,
                gameCreatedAt = gameCreatedAt,
            )
        return fireAndForget(dto.requestId, dto) { local.addRumor(content, location, significance, gameCreatedAt) }
    }

    // ── Quests ──────────────────────────────────────────────────────────

    override fun assignQuestFromIntent(
        player: Player,
        questId: String,
        quest: Quest,
        npc: StoryNPC,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.ASSIGN_QUEST)) return local.assignQuestFromIntent(player, questId, quest, npc)

        val dto =
            AssignQuestRequest(
                requestId = UUID.randomUUID().toString(),
                playerName = player.name,
                questId = questId,
                questTitle = quest.title,
                questDescription = quest.description,
                npcName = npc.name,
            )
        return fireAndForget(dto.requestId, dto) { local.assignQuestFromIntent(player, questId, quest, npc) }
    }

    override fun assignQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.ASSIGN_EXISTING_QUEST)) return local.assignQuest(player, questId)

        val dto =
            AssignExistingQuestRequest(
                requestId = UUID.randomUUID().toString(),
                playerName = player.name,
                questId = questId,
            )
        return fireAndForget(dto.requestId, dto) { local.assignQuest(player, questId) }
    }

    override fun updateQuestProgress(
        player: Player,
        questId: String,
        objectiveType: String?,
        target: String?,
        progress: Int,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.UPDATE_QUEST_PROGRESS)) {
            return local.updateQuestProgress(player, questId, objectiveType, target, progress)
        }

        val dto =
            UpdateQuestProgressRequest(
                requestId = UUID.randomUUID().toString(),
                playerName = player.name,
                questId = questId,
                objectiveType = objectiveType,
                target = target,
                progress = progress,
            )
        return fireAndForget(dto.requestId, dto) {
            local.updateQuestProgress(player, questId, objectiveType, target, progress)
        }
    }

    override fun completeQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.COMPLETE_QUEST)) return local.completeQuest(player, questId)

        val dto =
            CompleteQuestRequest(
                requestId = UUID.randomUUID().toString(),
                playerName = player.name,
                questId = questId,
            )
        return fireAndForget(dto.requestId, dto) { local.completeQuest(player, questId) }
    }

    override fun saveQuest(quest: Quest): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.SAVE_QUEST)) return local.saveQuest(quest)

        val dto =
            SaveQuestRequest(
                requestId = UUID.randomUUID().toString(),
                questId = quest.id,
                title = quest.title,
                description = quest.description,
            )
        return fireAndForget(dto.requestId, dto) { local.saveQuest(quest) }
    }

    // ── Locations ───────────────────────────────────────────────────────

    override fun createLocation(
        name: String,
        bukkitLocation: Location?,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.CREATE_LOCATION)) return local.createLocation(name, bukkitLocation)

        val dto =
            CreateLocationRequest(
                requestId = UUID.randomUUID().toString(),
                name = name,
                world = bukkitLocation?.world?.name,
                x = bukkitLocation?.x,
                y = bukkitLocation?.y,
                z = bukkitLocation?.z,
            )
        return fireAndForget(dto.requestId, dto) { local.createLocation(name, bukkitLocation) }
    }

    override fun saveLocation(location: StoryLocation): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.SAVE_LOCATION)) return local.saveLocation(location)

        val dto =
            SaveLocationRequest(
                requestId = UUID.randomUUID().toString(),
                name = location.name,
                description = location.description,
                parentLocationName = location.parentLocationName,
                world = null,
                x = 0.0,
                y = 0.0,
                z = 0.0,
            )
        return fireAndForget(dto.requestId, dto) { local.saveLocation(location) }
    }

    // ── Sessions ────────────────────────────────────────────────────────

    override fun startSession(): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.START_SESSION)) return local.startSession()

        val dto =
            SessionActionRequest(
                requestId = UUID.randomUUID().toString(),
                method = StorageMethod.START_SESSION,
            )
        return fireAndForget(dto.requestId, dto) { local.startSession() }
    }

    override fun endSession(): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.END_SESSION)) return local.endSession()

        val dto =
            SessionActionRequest(
                requestId = UUID.randomUUID().toString(),
                method = StorageMethod.END_SESSION,
            )
        return fireAndForget(dto.requestId, dto) { local.endSession() }
    }

    override fun feedSession(
        text: String,
        force: Boolean,
    ): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.FEED_SESSION)) return local.feedSession(text, force)

        val dto =
            SessionActionRequest(
                requestId = UUID.randomUUID().toString(),
                method = StorageMethod.FEED_SESSION,
                text = text,
                force = force,
            )
        return fireAndForget(dto.requestId, dto) { local.feedSession(text, force) }
    }

    // ── Lore ────────────────────────────────────────────────────────────

    override fun saveLoreBook(loreBook: LoreBook): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.SAVE_LORE_BOOK)) return local.saveLoreBook(loreBook)

        val dto =
            SaveLoreBookRequest(
                requestId = UUID.randomUUID().toString(),
                name = loreBook.name,
                content = loreBook.context,
            )
        return fireAndForget(dto.requestId, dto) { local.saveLoreBook(loreBook) }
    }

    override fun deleteLoreBook(name: String): CompletableFuture<Void> {
        if (!isSupported(StorageMethod.DELETE_LORE_BOOK)) return local.deleteLoreBook(name)

        val dto =
            DeleteLoreBookRequest(
                requestId = UUID.randomUUID().toString(),
                name = name,
            )
        return fireAndForget(dto.requestId, dto) { local.deleteLoreBook(name) }
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Send a DTO to the bridge and return a fire-and-forget future.
     * On failure, falls back to the local implementation.
     */
    private inline fun <reified T : Any> fireAndForget(
        requestId: String,
        dto: T,
        crossinline fallback: () -> CompletableFuture<Void>,
    ): CompletableFuture<Void> {
        val jsonObject = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), dto).jsonObject
        return sendRequest(requestId, jsonObject)
            .thenApply<Void> { null }
            .exceptionally { e ->
                plugin.logger.warning("Bridge storage failed for $requestId, falling back to local: ${e.message}")
                fallback().get()
                null
            }
    }

    private inline fun <reified T : Any> emitDto(dto: T) {
        val jsonObject =
            json
                .encodeToJsonElement(
                    kotlinx.serialization.serializer<T>(),
                    dto,
                ).jsonObject
        eventBus.emit(
            object : StoryEvent {
                override val eventType: String = StorageEventType.STORAGE_REQUEST

                override fun toWireData(): JsonObject = jsonObject
            },
        )
    }

    private fun sendRequest(
        requestId: String,
        data: JsonObject,
    ): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        pendingRequests[requestId] = future

        eventBus.emit(
            object : StoryEvent {
                override val eventType: String = StorageEventType.STORAGE_REQUEST

                override fun toWireData(): JsonObject = data
            },
        )

        future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally {
            pendingRequests.remove(requestId)
            null
        }

        return future
    }
}
