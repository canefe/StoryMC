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
}
