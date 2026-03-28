package com.canefe.story.util

import com.canefe.story.Story
import com.canefe.story.api.character.CharacterRecord
import org.bukkit.entity.Player

/**
 * Character-aware player extensions. Replaces EssentialsUtils.getNickname for identity.
 * Falls back to Minecraft username for unregistered (guest) players.
 */

val Player.characterName: String
    get() {
        try {
            val record = Story.instance.characterRegistry.getByPlayer(this)
            if (record != null && record.name.isNotEmpty()) return record.name
        } catch (_: Exception) {
        }
        return this.getName()
    }

val Player.characterId: String?
    get() =
        try {
            Story.instance.characterRegistry.getCharacterIdForPlayer(this)
        } catch (_: Exception) {
            null
        }

val Player.character: CharacterRecord?
    get() =
        try {
            Story.instance.characterRegistry.getByPlayer(this)
        } catch (_: Exception) {
            null
        }

val Player.isRegisteredCharacter: Boolean
    get() =
        try {
            Story.instance.characterRegistry.isRegistered(this)
        } catch (_: Exception) {
            false
        }
