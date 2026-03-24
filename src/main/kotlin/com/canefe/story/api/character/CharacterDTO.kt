package com.canefe.story.api.character

import com.canefe.story.npc.data.NPCData
import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of a [Character] for storage and wire transport.
 * No Minecraft dependencies — usable across any frontend.
 */
@Serializable
data class CharacterDTO(
    val name: String,
    val role: String,
    val appearance: String,
    val context: String,
    val avatar: String = "",
    val locationName: String? = null,
) {
    companion object {
        fun from(
            character: Character,
            avatar: String = "",
            locationName: String? = null,
        ): CharacterDTO =
            CharacterDTO(
                name = character.name,
                role = character.role,
                appearance = character.appearance,
                context = character.context,
                avatar = avatar,
                locationName = locationName ?: character.location?.name,
            )

        fun from(data: NPCData): CharacterDTO =
            CharacterDTO(
                name = data.name,
                role = data.role,
                appearance = data.appearance,
                context = data.context,
                avatar = data.avatar,
                locationName = data.storyLocation?.name,
            )
    }
}
