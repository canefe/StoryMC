package com.canefe.story.util

import com.canefe.story.Story
import com.canefe.story.api.character.CharacterRecord
import org.bukkit.entity.Player

/**
 * Character-aware player extensions. Replaces EssentialsUtils.getNickname for identity.
 * Falls back to Minecraft username for unregistered (guest) players.
 */

val Player.characterId: String?
    get() =
        try {
            // Prefer the unified players-collection mapping (activeCharacters.minecraft).
            // Falls back to the legacy frontend_config lookup when no player doc exists.
            Story.instance.characterRegistry.getActiveCharacterForPlayer(this)
        } catch (_: Exception) {
            null
        }

val Player.character: CharacterRecord?
    get() =
        try {
            characterId?.let { Story.instance.characterRegistry.getById(it) }
                ?: Story.instance.characterRegistry.getByPlayer(this)
        } catch (_: Exception) {
            null
        }

val Player.characterName: String
    get() {
        try {
            val record = character
            if (record != null && record.name.isNotEmpty()) return record.name
        } catch (_: Exception) {
        }
        return this.name
    }

val Player.isRegisteredCharacter: Boolean
    get() = characterId != null
