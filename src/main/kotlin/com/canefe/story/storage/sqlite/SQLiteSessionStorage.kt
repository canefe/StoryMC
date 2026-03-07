package com.canefe.story.storage.sqlite

import com.canefe.story.storage.SQLiteManager
import com.canefe.story.storage.SessionDocument
import com.canefe.story.storage.SessionStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SQLiteSessionStorage(
    private val sqlite: SQLiteManager,
) : SessionStorage {
    private val gson = Gson()

    override fun saveSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        val conn = sqlite.getConnection()
        conn
            .prepareStatement(
                "REPLACE INTO sessions (session_id, start_time, end_time, players, history, active) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, sessionId)
                stmt.setLong(2, document.startTime)
                if (document.endTime !=
                    null
                ) {
                    stmt.setLong(3, document.endTime)
                } else {
                    stmt.setNull(3, java.sql.Types.INTEGER)
                }
                stmt.setString(4, gson.toJson(document.players))
                stmt.setString(5, document.history)
                stmt.setInt(6, if (document.active) 1 else 0)
                stmt.executeUpdate()
            }
    }

    override fun loadSession(sessionId: String): SessionDocument? {
        val conn = sqlite.getConnection()
        conn.prepareStatement("SELECT * FROM sessions WHERE session_id = ?").use { stmt ->
            stmt.setString(1, sessionId)
            val rs = stmt.executeQuery()
            if (!rs.next()) return null

            val playersJson = rs.getString("players") ?: "[]"
            val players: List<String> = gson.fromJson(playersJson, object : TypeToken<List<String>>() {}.type)

            return SessionDocument(
                sessionId = rs.getString("session_id"),
                startTime = rs.getLong("start_time"),
                endTime = rs.getLong("end_time").let { if (rs.wasNull()) null else it },
                players = players,
                history = rs.getString("history") ?: "",
                active = rs.getInt("active") == 1,
            )
        }
    }

    override fun updateSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        saveSession(sessionId, document)
    }
}
