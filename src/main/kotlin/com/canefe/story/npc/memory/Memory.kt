package com.canefe.story.npc.memory

import java.time.Instant
import java.util.*

/**
 * Represents an NPC's memory of a past conversation or event.
 */
data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val createdAt: Instant = Instant.now(),
    var power: Double = 1.0,
    var lastAccessed: Instant = Instant.now()
) {
    /**
     * Calculate the current strength of this memory based on its power and decay over time.
     * @param decayRate How quickly memories fade (higher values mean faster decay)
     * @return The current strength value between 0.0 and 1.0
     */
    fun getCurrentStrength(decayRate: Double = 0.01): Double {
        val daysSinceCreation = (Instant.now().epochSecond - createdAt.epochSecond) / 86400.0
        val decayFactor = Math.exp(-decayRate * daysSinceCreation)
        return power * decayFactor
    }

    /**
     * Updates the last accessed time to now and optionally reinforces the memory.
     * @param reinforcement Amount to increase the power (using a memory reinforces it)
     */
    fun access(reinforcement: Double = 0.1) {
        lastAccessed = Instant.now()
        power = (power + reinforcement).coerceAtMost(1.0)
    }

    override fun toString(): String {
        return content
    }
}