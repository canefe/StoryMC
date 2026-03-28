package com.canefe.story.api.character

import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of a [Character] for storage and wire transport.
 * No Minecraft dependencies — usable across any frontend.
 */
@Serializable
data class CharacterDTO(
    val id: String? = null,
    val name: String,
    val role: String = "",
    val appearance: String = "",
    val context: String = "",
    val avatar: String = "",
    val locationName: String? = null,
    val traits: List<String> = emptyList(),
    val race: String? = null,
) {
    companion object {
        fun from(
            character: Character,
            avatar: String = "",
            locationName: String? = null,
        ): CharacterDTO =
            CharacterDTO(
                id = character.id,
                name = character.name,
                role = character.role,
                appearance = character.appearance,
                context = character.context,
                avatar = avatar,
                locationName = locationName ?: character.location?.name,
            )

        fun from(record: CharacterRecord): CharacterDTO =
            CharacterDTO(
                id = record.id,
                name = record.name,
                appearance = record.appearance,
                traits = record.traits,
                race = record.race,
            )
    }
}
