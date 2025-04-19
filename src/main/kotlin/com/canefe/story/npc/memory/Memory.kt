package com.canefe.story.npc.memory

import com.canefe.story.util.TimeService
import java.time.Instant
import java.util.*

/**
 * Represents an NPC's memory of a past conversation or event.
 */
data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val realCreatedAt: Instant = Instant.now(),
    val gameCreatedAt: Long = 0, // Store game time when created
    var power: Double = 1.0,
    var lastAccessed: Long = 0, // Store game time when accessed
    private var _significance: Double = 1.0 // How emotionally significant this memory is
) {

    // Property with custom getter and setter
    var significance: Double
        get() = _significance
        set(value) {
            _significance = value.coerceIn(1.0, 5.0)
        }

    /**
     * Calculate the current strength of this memory using game-time decay
     * Significant memories decay more slowly and have a higher floor
     */
    fun getCurrentStrength(timeService: TimeService, decayRate: Double = 0.05): Double {
        // Calculate base decay
        val baseDecay = timeService.calculateMemoryDecay(gameCreatedAt, decayRate / significance, power)

        // Calculate memory floor based on significance (0.2-0.8 for significance 1.0-5.0)
        val memoryFloor = 0.2 * significance.coerceAtMost(5.0) / 5.0

        // Return the higher of the decayed value or the floor
        return baseDecay.coerceAtLeast(memoryFloor * power)
    }

    /**
     * Updates the last accessed time and reinforces the memory.
     * @param timeService The time service to get current game time
     * @param reinforcement Amount to increase the power
     */
    fun access(timeService: TimeService, reinforcement: Double = 0.1) {
        lastAccessed = timeService.getCurrentGameTime()
        power = (power + reinforcement).coerceAtMost(3.0) // Increased maximum power
    }

    override fun toString(): String {
        return content
    }
}