package com.canefe.story.storage

interface SessionStorage {
    fun saveSession(
        sessionId: String,
        document: SessionDocument,
    )

    fun loadSession(sessionId: String): SessionDocument?

    fun updateSession(
        sessionId: String,
        document: SessionDocument,
    )
}

data class SessionDocument(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val players: List<String> = emptyList(),
    val history: String = "",
    val active: Boolean = true,
)
