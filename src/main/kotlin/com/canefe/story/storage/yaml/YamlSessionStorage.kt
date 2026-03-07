@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.storage.SessionDocument
import com.canefe.story.storage.SessionStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlSessionStorage(
    private val sessionFolder: File,
    private val logger: Logger,
) : SessionStorage {
    init {
        if (!sessionFolder.exists()) sessionFolder.mkdirs()
    }

    override fun saveSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        val file = File(sessionFolder, "$sessionId.yml")
        writeToFile(file, document)
    }

    override fun loadSession(sessionId: String): SessionDocument? {
        val file = File(sessionFolder, "$sessionId.yml")
        if (!file.exists()) return null

        val config = YamlConfiguration.loadConfiguration(file)
        return SessionDocument(
            sessionId = sessionId,
            startTime = config.getLong("startTime", 0),
            endTime = if (config.contains("endTime")) config.getLong("endTime") else null,
            players = config.getStringList("players"),
            history = config.getString("history") ?: "",
            active = config.getBoolean("active", true),
        )
    }

    override fun updateSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        saveSession(sessionId, document)
    }

    fun getSessionFile(sessionId: String): File = File(sessionFolder, "$sessionId.yml")

    private fun writeToFile(
        file: File,
        document: SessionDocument,
    ) {
        val config = YamlConfiguration()
        config.set("startTime", document.startTime)
        document.endTime?.let { config.set("endTime", it) }
        config.set("players", document.players)
        config.set("history", document.history)
        config.set("active", document.active)
        config.save(file)
    }
}
