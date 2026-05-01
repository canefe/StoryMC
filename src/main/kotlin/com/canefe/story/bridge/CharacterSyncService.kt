package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.perception.PerceptionStats

/**
 * Handles character stat synchronization from the sim via the [StoryEventBus].
 * Listens for [CharacterStatsUpdate] events and updates the [CharacterStatsCache].
 */
class CharacterSyncService(
    private val plugin: Story,
) {
    fun register() {
        plugin.eventBus.on<CharacterStatsUpdate> { update ->
            val stats = PerceptionStats(
                sightRange = update.sightRange,
                visionRange = update.visionRange,
                consciousness = update.consciousness,
                fov = update.fov,
            )
            plugin.characterStatsCache.update(update.characterId, stats)
            // Prefer hearing_radius from sim; fall back to legacy perceptionRadius field
            val radius = if (update.hearingRadius > 0.0) update.hearingRadius else update.perceptionRadius
            radius?.let { plugin.perceptionService.setPerceptionRadius(update.characterId, it) }
        }
    }
}
