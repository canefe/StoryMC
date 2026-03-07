package com.canefe.story.storage.mongo

import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.PlayerStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.UUID

class MongoPlayerStorage(
    private val mongoClient: MongoClientManager,
) : PlayerStorage {
    private val teamsCollection get() = mongoClient.getCollection("teams")
    private val disabledPlayersCollection get() = mongoClient.getCollection("disabled_players")
    private val playerQuestDisplayCollection get() = mongoClient.getCollection("player_quest_display")

    override fun loadTeams(): Map<String, MutableSet<UUID>> {
        val teams = mutableMapOf<String, MutableSet<UUID>>()
        for (doc in teamsCollection.find()) {
            val teamName = doc.getString("teamName") ?: continue
            val members =
                (doc.getList("members", String::class.java) ?: emptyList())
                    .mapNotNull { str ->
                        try {
                            UUID.fromString(str)
                        } catch (_: Exception) {
                            null
                        }
                    }.toMutableSet()
            teams[teamName] = members
        }
        return teams
    }

    override fun saveTeams(teams: Map<String, Set<UUID>>) {
        // Delete all then re-insert
        teamsCollection.deleteMany(Document())
        for ((teamName, members) in teams) {
            val doc =
                Document()
                    .append("teamName", teamName)
                    .append("members", members.map { it.toString() })
            teamsCollection.insertOne(doc)
        }
    }

    override fun loadDisabledPlayers(): MutableList<String> {
        val doc =
            disabledPlayersCollection.find(Filters.eq("_key", "disabled")).first()
                ?: return mutableListOf()
        return (doc.getList("players", String::class.java) ?: emptyList()).toMutableList()
    }

    override fun saveDisabledPlayers(players: List<String>) {
        val doc =
            Document()
                .append("_key", "disabled")
                .append("players", players)
        disabledPlayersCollection.replaceOne(
            Filters.eq("_key", "disabled"),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun loadPlayerQuestDisplay(): Map<UUID, Pair<String, String>> {
        val result = mutableMapOf<UUID, Pair<String, String>>()
        for (doc in playerQuestDisplayCollection.find()) {
            val playerId =
                try {
                    UUID.fromString(doc.getString("playerId"))
                } catch (_: Exception) {
                    continue
                }
            val title = doc.getString("title") ?: continue
            val objective = doc.getString("objective") ?: continue
            result[playerId] = Pair(title, objective)
        }
        return result
    }

    override fun savePlayerQuestDisplay(
        playerId: UUID,
        title: String,
        objective: String,
    ) {
        val doc =
            Document()
                .append("playerId", playerId.toString())
                .append("title", title)
                .append("objective", objective)
        playerQuestDisplayCollection.replaceOne(
            Filters.eq("playerId", playerId.toString()),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun clearPlayerQuestDisplay(playerId: UUID) {
        playerQuestDisplayCollection.deleteOne(Filters.eq("playerId", playerId.toString()))
    }
}
