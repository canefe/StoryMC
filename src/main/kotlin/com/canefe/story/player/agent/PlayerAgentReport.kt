package com.canefe.story.player.agent

/**
 * The result of an LLM analysis cycle for a player.
 *
 * @param playerName The name of the player this report is for
 * @param mood Short mood label (e.g. "curious", "hostile", "disengaged")
 * @param behaviorTags Comma-separated behavior tags the LLM detected (e.g. "exploring,quest-active")
 * @param narrativeNotes Free-text DM-facing notes about what the player is doing / narrative hooks
 * @param timestamp When this report was generated (epoch ms)
 */
data class PlayerAgentReport(
    val playerName: String,
    val mood: String,
    val behaviorTags: List<String>,
    val narrativeNotes: String,
    val timestamp: Long = System.currentTimeMillis(),
)
