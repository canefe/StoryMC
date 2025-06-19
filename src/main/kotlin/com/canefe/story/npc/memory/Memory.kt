package com.canefe.story.npc.memory

import com.canefe.story.util.TimeService
import java.time.Instant
import java.util.*
import kotlin.compareTo
import kotlin.div

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
	private var _significance: Double = 1.0, // How emotionally significant this memory is
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
	 * Calculates time elapsed since memory creation in game time.
	 * @param timeService The time service to get current game time
	 * @return A formatted string representing elapsed time (e.g. "3 days", "2 weeks", "4 months", "1 year")
	 */
	fun getElapsedTime(timeService: TimeService): String {
		val currentGameTime = timeService.getCurrentGameTime()
		val elapsedGameTime = currentGameTime - gameCreatedAt

		// Since game time is in minutes:
		// 1 day = 1440 minutes
		// 1 week = 10080 minutes
		// 1 month = ~43200 minutes (30 days)
		// 1 year = 525600 minutes

		return when {
			elapsedGameTime >= 525600 -> {
				val years = elapsedGameTime / 525600
				"$years ${if (years == 1L) "year" else "years"}"
			}
			elapsedGameTime >= 43200 -> {
				val months = elapsedGameTime / 43200
				"$months ${if (months == 1L) "month" else "months"}"
			}
			elapsedGameTime >= 10080 -> {
				val weeks = elapsedGameTime / 10080
				"$weeks ${if (weeks == 1L) "week" else "weeks"}"
			}
			elapsedGameTime >= 1440 -> {
				val days = elapsedGameTime / 1440
				"$days ${if (days == 1L) "day" else "days"}"
			}
			elapsedGameTime >= 60 -> {
				val hours = elapsedGameTime / 60
				"$hours ${if (hours == 1L) "hour" else "hours"}"
			}
			else -> {
				val minutes = elapsedGameTime.coerceAtLeast(1)
				"$minutes ${if (minutes == 1L) "minute" else "minutes"}"
			}
		}
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

	override fun toString(): String = content
}
