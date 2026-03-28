package com.canefe.story.bridge

import com.canefe.story.Story

/**
 * Handles character stat synchronization from the sim via the [StoryEventBus].
 * Listens for [CharacterStatsUpdate] events and applies them to the relevant services.
 */
class CharacterSyncService(
    private val plugin: Story,
) {
    fun register() {
        plugin.eventBus.on<CharacterStatsUpdate> { update ->
            update.perceptionRadius?.let {
                plugin.perceptionService.setPerceptionRadius(update.characterId, it)
            }
            // Future stats: health, mood, disposition, etc.
        }
    }
}
