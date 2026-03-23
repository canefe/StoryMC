package com.canefe.story.player

import kotlinx.serialization.Serializable

/**
 * Per-player configuration that players can toggle via commands.
 * Serialized as JSON and persisted through [com.canefe.story.storage.PlayerStorage].
 */
@Serializable
data class PlayerConfig(
    /** Whether this player uses delayed/accumulated message processing instead of per-message processing. */
    val delayedPlayerMessageProcessing: Boolean = false,
)
