package com.canefe.story.storage

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.information.Rumor
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import com.canefe.story.quest.Quest
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * Local implementation of [StoryStorage] that writes directly via existing managers.
 * Preserves all current behavior including session gating and relationship chaining.
 * Serves as the deprecated fallback when Go orchestrator is not available.
 */
class LocalStorage(
    private val plugin: Story,
) : StoryStorage {
    // ── Character Memory ────────────────────────────────────────────────

    override fun createMemory(
        characterName: String,
        content: String,
        significance: Double,
    ): CompletableFuture<Void> {
        plugin.npcDataManager.createMemoryForNPC(characterName, content, significance)

        val savedMemories = plugin.npcDataManager.loadNPCMemory(characterName)
        savedMemories.lastOrNull()?.let { memory ->
            plugin.relationshipManager.updateRelationshipFromMemory(memory, characterName)
        }

        return CompletableFuture.completedFuture(null)
    }

    override fun updateRelationshipFromMemory(
        memory: Memory,
        characterName: String,
    ): CompletableFuture<Void> {
        plugin.relationshipManager.updateRelationshipFromMemory(memory, characterName)
        return CompletableFuture.completedFuture(null)
    }

    // ── Character Data ──────────────────────────────────────────────────

    override fun saveCharacterData(character: CharacterDTO): CompletableFuture<Void> {
        val location = character.locationName?.let { plugin.locationManager.getLocation(it) }
        val key = character.id ?: character.name
        val npcData =
            plugin.npcDataManager.getNPCData(key)
                ?: NPCData(character.name, character.role, location, character.context)

        npcData.role = character.role
        npcData.context = character.context
        npcData.appearance = character.appearance
        npcData.avatar = character.avatar
        if (location != null) npcData.storyLocation = location

        plugin.npcDataManager.saveNPCData(key, npcData)
        return CompletableFuture.completedFuture(null)
    }

    override fun deleteCharacterData(characterName: String): CompletableFuture<Void> {
        plugin.npcDataManager.deleteNPCFile(characterName)
        return CompletableFuture.completedFuture(null)
    }

    // ── Rumors ──────────────────────────────────────────────────────────

    override fun addRumor(
        content: String,
        location: String,
        significance: Double,
        gameCreatedAt: Long,
    ): CompletableFuture<Void> {
        if (!plugin.sessionManager.hasActiveSession()) {
            plugin.logger.info("Location rumor skipped for $location — no active session")
            return CompletableFuture.completedFuture(null)
        }

        val rumor =
            Rumor(
                content = content,
                gameCreatedAt = gameCreatedAt,
                location = location,
                significance = significance,
            )
        plugin.rumorManager.addRumor(rumor)
        plugin.logger.info("Added rumor to $location: $content")

        return CompletableFuture.completedFuture(null)
    }

    // ── Quests ──────────────────────────────────────────────────────────

    override fun assignQuestFromIntent(
        player: Player,
        questId: String,
        quest: Quest,
        npc: StoryNPC,
    ): CompletableFuture<Void> {
        plugin.questManager.registerQuest(quest, npc)
        plugin.questManager.assignQuestToPlayer(player, questId)
        return CompletableFuture.completedFuture(null)
    }

    override fun assignQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void> {
        plugin.questManager.assignQuestToPlayer(player, questId)
        return CompletableFuture.completedFuture(null)
    }

    override fun updateQuestProgress(
        player: Player,
        questId: String,
        objectiveType: String?,
        target: String?,
        progress: Int,
    ): CompletableFuture<Void> {
        val type =
            objectiveType?.let {
                try {
                    com.canefe.story.quest.ObjectiveType
                        .valueOf(it)
                } catch (_: Exception) {
                    null
                }
            }
        plugin.questManager.updateObjectiveProgress(player, questId, type, target, progress)
        return CompletableFuture.completedFuture(null)
    }

    override fun completeQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void> {
        plugin.questManager.completeQuest(player, questId)
        return CompletableFuture.completedFuture(null)
    }

    override fun saveQuest(quest: Quest): CompletableFuture<Void> {
        plugin.questManager.saveQuest(quest)
        return CompletableFuture.completedFuture(null)
    }

    // ── Locations ───────────────────────────────────────────────────────

    override fun createLocation(
        name: String,
        bukkitLocation: Location?,
    ): CompletableFuture<Void> {
        plugin.locationManager.createLocation(name, bukkitLocation)
        return CompletableFuture.completedFuture(null)
    }

    override fun saveLocation(location: StoryLocation): CompletableFuture<Void> {
        plugin.locationManager.saveLocation(location)
        return CompletableFuture.completedFuture(null)
    }

    // ── Sessions ────────────────────────────────────────────────────────

    override fun startSession(): CompletableFuture<Void> {
        plugin.sessionManager.startSession()
        return CompletableFuture.completedFuture(null)
    }

    override fun endSession(): CompletableFuture<Void> {
        plugin.sessionManager.endSession()
        return CompletableFuture.completedFuture(null)
    }

    override fun feedSession(
        text: String,
        force: Boolean,
    ): CompletableFuture<Void> {
        plugin.sessionManager.feed(text, force)
        return CompletableFuture.completedFuture(null)
    }

    // ── Lore ────────────────────────────────────────────────────────────

    override fun saveLoreBook(loreBook: LoreBook): CompletableFuture<Void> {
        plugin.lorebookManager.saveLoreBook(loreBook)
        return CompletableFuture.completedFuture(null)
    }

    override fun deleteLoreBook(name: String): CompletableFuture<Void> {
        plugin.lorebookManager.deleteLoreBook(name)
        return CompletableFuture.completedFuture(null)
    }
}
