package com.canefe.story.npc.squad

import kotlin.math.abs

/**
 * Stable squad color: hash the squad id into the HSL hue space.
 * Returns 0xRRGGBB (no alpha). Same input → same output forever.
 */
object SquadColorScheme {
    fun colorFor(squadId: String): Int {
        val hash = abs(squadId.hashCode())
        val hue = (hash % 360) / 360.0f
        val saturation = 0.65f
        val lightness = 0.55f
        return hslToRgb(hue, saturation, lightness)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hp = h * 6
        val x = c * (1 - kotlin.math.abs(hp.rem(2) - 1))
        val (r1, g1, b1) =
            when {
                hp < 1 -> Triple(c, x, 0f)
                hp < 2 -> Triple(x, c, 0f)
                hp < 3 -> Triple(0f, c, x)
                hp < 4 -> Triple(0f, x, c)
                hp < 5 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
        val m = l - c / 2
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
