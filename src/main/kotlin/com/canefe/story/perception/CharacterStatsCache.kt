package com.canefe.story.perception

import java.util.concurrent.ConcurrentHashMap

data class PerceptionStats(
    val sightRange: Double,   // max distance to perceive (metres/blocks)
    val visionRange: Double,  // secondary range hint from race stats
    val consciousness: Double, // 0.0 = unconscious, 1.0 = fully aware — scales detection
    val fov: Double,          // half-angle in degrees; 0 = use default (90°)
)

private val DEFAULT = PerceptionStats(
    sightRange = 16.0,
    visionRange = 0.0,
    consciousness = 1.0,
    fov = 90.0,
)

class CharacterStatsCache {
    private val cache = ConcurrentHashMap<String, PerceptionStats>()

    fun update(characterId: String, stats: PerceptionStats) {
        cache[characterId] = stats
    }

    fun get(characterId: String): PerceptionStats =
        cache[characterId] ?: DEFAULT

    fun effectiveSightRange(characterId: String): Double {
        val s = get(characterId)
        val base = if (s.sightRange > 0.0) s.sightRange else DEFAULT.sightRange
        return base * s.consciousness.coerceIn(0.1, 1.0)
    }

    fun effectiveFov(characterId: String): Double {
        val s = get(characterId)
        return if (s.fov > 0.0) s.fov else DEFAULT.fov
    }
}
