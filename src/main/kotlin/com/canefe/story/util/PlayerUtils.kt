package com.canefe.story.util

import com.canefe.story.Story
import com.canefe.story.api.character.CharacterRecord
import org.bukkit.entity.Player

/**
 * Character-aware player utilities. Replaces EssentialsUtils.getNickname for identity.
 * Falls back to Minecraft username for unregistered (guest) players.
 */
object PlayerUtils {
    fun getCharacterName(player: Player): String =
        Story.instance.characterRegistry
            .getByPlayer(player)
            ?.name ?: player.name

    fun getCharacterId(player: Player): String? = Story.instance.characterRegistry.getCharacterIdForPlayer(player)

    fun getCharacter(player: Player): CharacterRecord? = Story.instance.characterRegistry.getByPlayer(player)

    fun isRegistered(player: Player): Boolean = Story.instance.characterRegistry.isRegistered(player)
}
