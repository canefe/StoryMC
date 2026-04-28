package com.canefe.story.api.character

import kotlinx.serialization.Serializable

/**
 * Represents a character document in the `characters` MongoDB collection.
 * Source of truth for character identity across all frontends.
 * No frontend-specific fields — those live in `frontend_config`.
 *
 * `appearance` is the canonical structured trait map (`hair_color → blonde`,
 * `beard → full`, ...). Render to prose at the point of use via
 * `appearance.toProse(gender, promptService)`. The map shape lets us layer
 * per-perceiver rendering (color blindness, illusion effects) on top later
 * without a schema migration.
 */
@Serializable
data class CharacterRecord(
    val id: String,
    val name: String,
    val race: String? = null,
    val gender: Gender = Gender.UNKNOWN,
    val appearance: Map<String, String> = emptyMap(),
    val traits: List<String> = emptyList(),
    val type: CharacterType = CharacterType.NPC,
    val customVoice: String? = null,
    val knowledgeCategories: List<String> = emptyList(),
) {
    @Serializable
    enum class CharacterType {
        @kotlinx.serialization.SerialName("npc")
        NPC,

        @kotlinx.serialization.SerialName("player")
        PLAYER,
    }
}

@Serializable
enum class Gender {
    @kotlinx.serialization.SerialName("male")
    MALE,

    @kotlinx.serialization.SerialName("female")
    FEMALE,

    @kotlinx.serialization.SerialName("other")
    OTHER,

    @kotlinx.serialization.SerialName("unknown")
    UNKNOWN,
    ;

    companion object {
        fun fromWire(s: String?): Gender = when (s?.lowercase()) {
            "male" -> MALE
            "female" -> FEMALE
            "other" -> OTHER
            else -> UNKNOWN
        }
    }
}
