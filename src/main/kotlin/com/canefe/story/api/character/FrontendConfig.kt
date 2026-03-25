package com.canefe.story.api.character

import java.util.UUID

/**
 * Frontend-specific configuration for a character, stored in the `frontend_config`
 * MongoDB collection. Keyed by (characterId, frontend).
 *
 * Each frontend type stores its own fields. Minecraft-specific fields like
 * citizensNpcId, avatar, displayHandle live here — not on [CharacterRecord].
 */
data class FrontendConfig(
    val characterId: String,
    val frontend: String,
    val properties: Map<String, Any?> = emptyMap(),
) {
    // ── Minecraft convenience accessors ─────────────────────────────────

    val minecraftUuid: UUID? get() =
        (properties["minecraftUuid"] as? String)?.let {
            try {
                UUID.fromString(it)
            } catch (_: Exception) {
                null
            }
        }
    val citizensNpcId: Int? get() = (properties["citizensNpcId"] as? Number)?.toInt()
    val citizensUuid: UUID? get() =
        (properties["citizensUuid"] as? String)?.let {
            try {
                UUID.fromString(it)
            } catch (
                _: Exception,
            ) {
                null
            }
        }
    val avatar: String? get() = properties["avatar"] as? String
    val displayHandle: String? get() = properties["displayHandle"] as? String
    val randomPathing: Boolean get() = properties["randomPathing"] as? Boolean ?: true

    companion object {
        const val MINECRAFT = "minecraft"
        const val DISCORD = "discord"

        fun minecraft(
            characterId: String,
            minecraftUuid: UUID? = null,
            citizensNpcId: Int? = null,
            citizensUuid: UUID? = null,
            avatar: String? = null,
            displayHandle: String? = null,
            randomPathing: Boolean = true,
        ): FrontendConfig =
            FrontendConfig(
                characterId = characterId,
                frontend = MINECRAFT,
                properties =
                    buildMap {
                        minecraftUuid?.let { put("minecraftUuid", it.toString()) }
                        citizensNpcId?.let { put("citizensNpcId", it) }
                        citizensUuid?.let { put("citizensUuid", it.toString()) }
                        avatar?.let { put("avatar", it) }
                        displayHandle?.let { put("displayHandle", it) }
                        put("randomPathing", randomPathing)
                    },
            )
    }
}
