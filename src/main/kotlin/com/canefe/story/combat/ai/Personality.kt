package com.canefe.story.combat.ai

import com.canefe.story.combat.SwingDir

/**
 * Per-NPC combat personality knobs supplied by sim. Read once per swing per
 * spec §7. Defaults are neutral (mid-aggression, no preferred direction, no
 * feint multiplier change).
 */
data class Personality(
    val aggression: Double = 0.5,
    val preferredDirection: SwingDir? = null,
    val feintPropensity: Double = 1.0,
) {
    companion object {
        val DEFAULT = Personality()
    }
}
