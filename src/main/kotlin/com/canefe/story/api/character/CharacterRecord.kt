package com.canefe.story.api.character

import kotlinx.serialization.Serializable

/**
 * Represents a character document in the `characters` MongoDB collection.
 * Source of truth for character identity across all frontends.
 * No frontend-specific fields — those live in `frontend_config`.
 */
@Serializable
data class CharacterRecord(
    val id: String,
    val name: String,
    val race: String? = null,
    val appearance: String = "",
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
