package com.canefe.story.util

import com.canefe.story.Story
import me.casperge.realisticseasons.api.SeasonsAPI
import org.bukkit.Bukkit
import org.bukkit.World
import java.time.Instant

/**
 * Interface for time services to support different time provider plugins
 */
interface TimeProvider {
    fun getCurrentGameTime(): Long

    fun getFormattedDate(): String

    fun getHours(): Int

    fun getMinutes(): Int

    fun getSeason(): String
}

/**
 * Default implementation using RealisticSeasons
 */
class RealisticSeasonsTimeProvider : TimeProvider {
    private val seasonsAPI: SeasonsAPI?
    private val defaultWorld: World = Bukkit.getWorld("world") ?: Bukkit.getWorlds()[0]

    init {
        seasonsAPI =
            try {
                SeasonsAPI.getInstance()
            } catch (e: Exception) {
                null
            }

        if (seasonsAPI == null) {
            throw IllegalStateException("Failed to initialize SeasonsAPI")
        }
    }

    override fun getCurrentGameTime(): Long {
        val date = seasonsAPI!!.getDate(defaultWorld)

        // Handle null date from API
        if (date == null) {
            Bukkit.getLogger().warning("RealisticSeasons returned null date, using fallback time calculation")
            return System.currentTimeMillis() / 1000 // Fallback to system time in seconds
        }

        val hours = seasonsAPI.getHours(defaultWorld)
        val minutes = seasonsAPI.getMinutes(defaultWorld)

        // Calculate day of year from month and day
        val daysInMonth = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val dayOfYear = daysInMonth.take(date.month - 1).sum() + date.day

        // Convert to a timestamp (year * 365 + dayOfYear) * 24 * 60 + (hour * 60 + minute)
        return (date.year * 525600 + dayOfYear * 1440 + hours * 60 + minutes).toLong()
    }

    override fun getFormattedDate(): String {
        val date = seasonsAPI!!.getDate(defaultWorld)
        return date?.toString(true) ?: "Unknown Date"
    }

    override fun getHours(): Int = seasonsAPI!!.getHours(defaultWorld)

    override fun getMinutes(): Int = seasonsAPI!!.getMinutes(defaultWorld)

    override fun getSeason(): String = seasonsAPI!!.getSeason(defaultWorld)?.toString() ?: "Unknown Season"
}

class FallbackTimeProvider : TimeProvider {
    // Use a base epoch for game time
    private val startEpoch = Instant.now().toEpochMilli()

    override fun getCurrentGameTime(): Long {
        // Simple implementation based on server ticks
        // 1 minecraft day = 24000 ticks
        // We'll use a 20:1 ratio, so 1 game minute = 20 real minutes
        val currentTime = Instant.now().toEpochMilli()
        val elapsed = currentTime - startEpoch
        return elapsed / 1000 // Convert to seconds as the game time unit
    }

    override fun getFormattedDate(): String {
        return "Day 1" // Simple fallback
    }

    override fun getHours(): Int = (System.currentTimeMillis() / 1000 % 24).toInt()

    override fun getMinutes(): Int = (System.currentTimeMillis() / 1000 % 60).toInt()

    override fun getSeason(): String {
        return "Spring" // Default season
    }
}

/**
 * Service to manage game time for memory decay and other time-based features
 */
class TimeService(
    private val plugin: Story,
) {
    private var timeProvider: TimeProvider

    init {
        // Check if RealisticSeasons is available
        timeProvider =
            if (Bukkit.getPluginManager().isPluginEnabled("RealisticSeasons")) {
                try {
                    RealisticSeasonsTimeProvider()
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to initialize RealisticSeasonsTimeProvider: ${e.message}")
                    FallbackTimeProvider()
                }
            } else {
                plugin.logger.warning("RealisticSeasons not found, using fallback time provider")
                FallbackTimeProvider()
            }
    }

    fun getCurrentGameTime(): Long = timeProvider.getCurrentGameTime()

    fun getFormattedDate(): String = timeProvider.getFormattedDate()

    fun getHours(): Int = timeProvider.getHours()

    fun getMinutes(): Int = timeProvider.getMinutes()

    fun getSeason(): String = timeProvider.getSeason()

    /**
     * Calculates decay based on game time rather than real time
     * @param createdAt The game time when memory was created
     * @param decayRate How quickly memories fade (higher values mean faster decay)
     * @param currentPower Current memory power
     * @return The decayed strength value between 0.0 and 1.0
     */
    fun calculateMemoryDecay(
        createdAt: Long,
        decayRate: Double,
        currentPower: Double,
    ): Double {
        val currentTime = getCurrentGameTime()
        val timeElapsed = currentTime - createdAt
        val timeUnits = timeElapsed.toDouble()

        // Exponential decay formula with significance factor built in
        val decayFactor = Math.exp(-decayRate * timeUnits / 1440.0) // Normalized by days
        return currentPower * decayFactor
    }
}
