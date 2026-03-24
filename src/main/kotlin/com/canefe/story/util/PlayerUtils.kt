package com.canefe.story.util

import com.canefe.story.Story
import com.canefe.story.api.character.CharacterRecord
import org.bukkit.entity.Player

/**
 * Character-aware player extensions. Replaces EssentialsUtils.getNickname for identity.
 * Falls back to Minecraft username for unregistered (guest) players.
 */

val Player.characterName: String
    get() =
        try {
            Story.instance.characterRegistry
                .getByPlayer(this)
                ?.name ?: this.name
        } catch (_: UninitializedPropertyAccessException) {
            this.name
        }

val Player.characterId: String?
    get() =
        try {
            Story.instance.characterRegistry.getCharacterIdForPlayer(this)
        } catch (_: UninitializedPropertyAccessException) {
            null
        }

val Player.character: CharacterRecord?
    get() =
        try {
            Story.instance.characterRegistry.getByPlayer(this)
        } catch (_: UninitializedPropertyAccessException) {
            null
        }

val Player.isRegisteredCharacter: Boolean
    get() =
        try {
            Story.instance.characterRegistry.isRegistered(this)
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
