package com.canefe.story.api.squad

import com.canefe.story.storage.mongo.InstantEpochMilliSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * In-fiction military / command structure of NPCs.
 *
 * Different from a puppet group:
 *   - Persistent (survives restart)
 *   - Owned by a CHARACTER (not a player), so login changes don't break it
 *   - Can be shared with other characters as joint commanders
 *   - NPC members are aware they're in this squad (injected into LLM prompt)
 *
 * Squad name is unique per [ownerCharacterId], not server-wide. Different
 * commanders can each have a "First Squad" without colliding.
 */
enum class SquadFormation { LINE, WEDGE, COLUMN, LOOSE, SQUARE, CIRCLE, ECHELON }

@Serializable
data class SquadRecord(
    /** Stable squad identifier (UUID string). */
    val id: String,
    /** Display name shown in commands and prompt context. */
    val name: String,
    /** Character who owns this squad (controls roster + sharing). */
    val ownerCharacterId: String,
    /**
     * Character IDs of members, in roster order. Members are referenced by
     * CharacterRecord id (stable across server restarts) rather than by NPC
     * entity stableUniqueId — the latter changes when MythicMobs respawn the
     * backing entity, breaking the link.
     *
     * Resolved live to a StoryNPC at order/render time via:
     *   npcRegistry.all().firstOrNull { characterRegistry.getCharacterIdForNPC(it) == characterId }
     */
    val memberCharacterIds: List<String> = emptyList(),
    /** Other characters granted command authority. */
    val sharedWithCharacterIds: List<String> = emptyList(),
    /** Default formation used for movement orders. */
    val formation: SquadFormation = SquadFormation.LINE,
    @Serializable(with = InstantEpochMilliSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantEpochMilliSerializer::class)
    val updatedAt: Instant = Instant.now(),
)
