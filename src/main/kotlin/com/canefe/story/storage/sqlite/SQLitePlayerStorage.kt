package com.canefe.story.storage.sqlite

import com.canefe.story.player.PlayerConfig
import com.canefe.story.storage.PlayerStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class SQLitePlayerStorage(
    private val sqlite: SQLiteManager,
) : PlayerStorage {
    private val gson = Gson()
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadTeams(): Map<String, MutableSet<UUID>> {
        val result = mutableMapOf<String, MutableSet<UUID>>()
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM teams")
            while (rs.next()) {
                val teamName = rs.getString("team_name")
                val membersJson = rs.getString("members") ?: "[]"
                val memberStrings: List<String> = gson.fromJson(membersJson, object : TypeToken<List<String>>() {}.type)
                result[teamName] = memberStrings.map { UUID.fromString(it) }.toMutableSet()
            }
        }
        return result
    }

    override fun saveTeams(teams: Map<String, Set<UUID>>) {
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM teams")
        }

        conn.prepareStatement("INSERT INTO teams (team_name, members) VALUES (?, ?)").use { stmt ->
            for ((teamName, members) in teams) {
                stmt.setString(1, teamName)
                stmt.setString(2, gson.toJson(members.map { it.toString() }))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun loadDisabledPlayers(): MutableList<String> {
        val result = mutableListOf<String>()
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT player_name FROM disabled_players")
            while (rs.next()) {
                result.add(rs.getString("player_name"))
            }
        }
        return result
    }

    override fun saveDisabledPlayers(players: List<String>) {
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM disabled_players")
        }

        conn.prepareStatement("INSERT INTO disabled_players (player_name) VALUES (?)").use { stmt ->
            for (player in players) {
                stmt.setString(1, player)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    override fun loadPlayerQuestDisplay(): Map<UUID, Pair<String, String>> {
        val result = mutableMapOf<UUID, Pair<String, String>>()
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM player_quest_display")
            while (rs.next()) {
                val playerId = UUID.fromString(rs.getString("player_id"))
                val title = rs.getString("title")
                val objective = rs.getString("objective")
                result[playerId] = Pair(title, objective)
            }
        }
        return result
    }

    override fun savePlayerQuestDisplay(
        playerId: UUID,
        title: String,
        objective: String,
    ) {
        val conn = sqlite.getConnection()
        conn
            .prepareStatement(
                "REPLACE INTO player_quest_display (player_id, title, objective) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, playerId.toString())
                stmt.setString(2, title)
                stmt.setString(3, objective)
                stmt.executeUpdate()
            }
    }

    override fun clearPlayerQuestDisplay(playerId: UUID) {
        val conn = sqlite.getConnection()
        conn.prepareStatement("DELETE FROM player_quest_display WHERE player_id = ?").use { stmt ->
            stmt.setString(1, playerId.toString())
            stmt.executeUpdate()
        }
    }

    override fun loadPlayerConfig(playerId: UUID): PlayerConfig {
        val conn = sqlite.getConnection()
        conn.prepareStatement("SELECT config_json FROM player_configs WHERE player_id = ?").use { stmt ->
            stmt.setString(1, playerId.toString())
            val rs = stmt.executeQuery()
            if (rs.next()) {
                return try {
                    json.decodeFromString<PlayerConfig>(rs.getString("config_json"))
                } catch (_: Exception) {
                    PlayerConfig()
                }
            }
        }
        return PlayerConfig()
    }

    override fun savePlayerConfig(
        playerId: UUID,
        config: PlayerConfig,
    ) {
        val conn = sqlite.getConnection()
        conn
            .prepareStatement(
                "REPLACE INTO player_configs (player_id, config_json) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setString(1, playerId.toString())
                stmt.setString(2, json.encodeToString(config))
                stmt.executeUpdate()
            }
    }
}
