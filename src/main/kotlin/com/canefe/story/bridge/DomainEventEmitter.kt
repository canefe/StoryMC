package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.quest.Quest
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * Emits domain events to the event bus, replacing direct storage mutations.
 * All persistence is owned by Go — this class only signals intent.
 *
 * When bridge is disabled, events are emitted to the Bukkit transport only
 * (typically no listeners), effectively becoming no-ops with a log warning.
 */
class DomainEventEmitter(
    private val eventBus: StoryEventBus,
    private val logger: Logger,
    private val bridgeEnabled: Boolean,
) {
    private fun emit(event: SerializableStoryEvent) {
        if (!bridgeEnabled) {
            logger.fine("Domain event ${event.eventType} emitted without bridge — no remote handler")
        }
        eventBus.emit(event)
    }

    // ── Character ────────────────────────────────────────────────────────

    fun emitCharacterSave(character: CharacterDTO) {
        emit(
            CharacterSaveRequestedEvent(
                characterId = character.name,
                name = character.name,
                role = character.role,
                context = character.context,
                appearance = character.appearance,
                avatar = character.avatar,
                locationName = character.locationName,
            ),
        )
    }

    fun emitCharacterDelete(characterName: String) {
        emit(
            CharacterDeleteRequestedEvent(
                characterId = characterName,
                name = characterName,
            ),
        )
    }

    // ── Memory ───────────────────────────────────────────────────────────

    fun emitMemoryObserved(
        characterId: String,
        content: String,
        significance: Double = 1.0,
    ) {
        emit(
            MemoryObservedEvent(
                characterId = characterId,
                content = content,
                significance = significance,
            ),
        )
    }

    // ── Rumors ───────────────────────────────────────────────────────────

    fun emitRumorObserved(
        content: String,
        location: String,
        significance: Double,
        gameCreatedAt: Long,
    ) {
        emit(
            RumorObservedEvent(
                content = content,
                location = location,
                significance = significance,
                gameCreatedAt = gameCreatedAt,
            ),
        )
    }

    // ── Quests ───────────────────────────────────────────────────────────

    fun emitQuestAssign(
        player: Player,
        questId: String,
    ) {
        emit(
            QuestAssignRequestedEvent(
                playerName = player.name,
                questId = questId,
            ),
        )
    }

    fun emitQuestAssignFromIntent(
        player: Player,
        questId: String,
        quest: Quest,
        npc: StoryNPC,
    ) {
        emit(
            QuestAssignFromIntentEvent(
                playerName = player.name,
                questId = questId,
                questTitle = quest.title,
                questDescription = quest.description,
                npcName = npc.name,
            ),
        )
    }

    fun emitQuestProgress(
        player: Player,
        questId: String,
        objectiveType: String? = null,
        target: String? = null,
        progress: Int = 1,
    ) {
        emit(
            QuestProgressObservedEvent(
                playerName = player.name,
                questId = questId,
                objectiveType = objectiveType,
                target = target,
                progress = progress,
            ),
        )
    }

    fun emitQuestComplete(
        player: Player,
        questId: String,
    ) {
        emit(
            QuestCompleteRequestedEvent(
                playerName = player.name,
                questId = questId,
            ),
        )
    }

    // ── Sessions ─────────────────────────────────────────────────────────

    fun emitSessionFeed(
        text: String,
        force: Boolean = false,
    ) {
        emit(
            SessionFeedEvent(
                text = text,
                force = force,
            ),
        )
    }
}
