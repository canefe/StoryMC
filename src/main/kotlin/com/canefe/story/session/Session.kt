package com.canefe.story.session

/**
 * Data class representing a gameplay session.
 */
data class Session(
    val startTime: Long,
    val players: MutableSet<String> = mutableSetOf(),
    val history: StringBuilder = StringBuilder(),
) {
    var endTime: Long? = null

    /** Periodic summary of session history, used as context for AI instead of full history. */
    var historySummary: String? = null

    /** Number of entries added since the last summary was generated. */
    var entriesSinceLastSummary: Int = 0
}
