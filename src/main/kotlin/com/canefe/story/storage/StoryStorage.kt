package com.canefe.story.storage

import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.npc.memory.Memory
import com.canefe.story.quest.Quest
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * Abstraction for all persistent write operations in the story system.
 *
 * Mirrors [com.canefe.story.intelligence.StoryIntelligence]: LocalStorage handles writes
 * directly via existing managers (deprecated fallback), BridgeStorage delegates to the
 * Go orchestrator which persists via story-bot/story-mcp.
 */
interface StoryStorage {
    // ── Character Memory ────────────────────────────────────────────────

    /** Create a memory for a character and update any resulting relationships. */
    fun createMemory(
        characterName: String,
        content: String,
        significance: Double = 1.0,
    ): CompletableFuture<Void>

    /** Update character relationships based on a memory. */
    fun updateRelationshipFromMemory(
        memory: Memory,
        characterName: String,
    ): CompletableFuture<Void>

    // ── Character Data ──────────────────────────────────────────────────

    /** Save character data (role, context, appearance, etc.). */
    fun saveCharacterData(character: CharacterDTO): CompletableFuture<Void>

    /** Delete a character's persistent data. */
    fun deleteCharacterData(characterName: String): CompletableFuture<Void>

    // ── Rumors ──────────────────────────────────────────────────────────

    /** Add a rumor to a location. Session-gated in local mode. */
    fun addRumor(
        content: String,
        location: String,
        significance: Double,
        gameCreatedAt: Long,
    ): CompletableFuture<Void>

    // ── Quests ──────────────────────────────────────────────────────────

    /** Register and assign a quest from a character intent. */
    fun assignQuestFromIntent(
        player: Player,
        questId: String,
        quest: Quest,
        npc: StoryNPC,
    ): CompletableFuture<Void>

    /** Assign an existing quest to a player. */
    fun assignQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void>

    /** Update quest objective progress. */
    fun updateQuestProgress(
        player: Player,
        questId: String,
        objectiveType: String? = null,
        target: String? = null,
        progress: Int = 1,
    ): CompletableFuture<Void>

    /** Complete a quest for a player. */
    fun completeQuest(
        player: Player,
        questId: String,
    ): CompletableFuture<Void>

    /** Save a quest definition. */
    fun saveQuest(quest: Quest): CompletableFuture<Void>

    // ── Locations ───────────────────────────────────────────────────────

    /** Create a new location. */
    fun createLocation(
        name: String,
        bukkitLocation: Location?,
    ): CompletableFuture<Void>

    /** Save an existing location. */
    fun saveLocation(location: StoryLocation): CompletableFuture<Void>

    // ── Sessions ────────────────────────────────────────────────────────

    /** Start a new session. */
    fun startSession(): CompletableFuture<Void>

    /** End the current session. */
    fun endSession(): CompletableFuture<Void>

    /** Feed text into the current session history. */
    fun feedSession(
        text: String,
        force: Boolean = false,
    ): CompletableFuture<Void>

    // ── Lore ────────────────────────────────────────────────────────────

    /** Save a lore book. */
    fun saveLoreBook(loreBook: LoreBook): CompletableFuture<Void>

    /** Delete a lore book by name. */
    fun deleteLoreBook(name: String): CompletableFuture<Void>
}
