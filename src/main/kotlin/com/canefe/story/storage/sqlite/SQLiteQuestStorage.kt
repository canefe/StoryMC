package com.canefe.story.storage.sqlite

import com.canefe.story.quest.*
import com.canefe.story.storage.QuestStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SQLiteQuestStorage(
    private val sqlite: SQLiteManager,
) : QuestStorage {
    private val gson = Gson()

    override fun loadAllQuests(): Map<String, Quest> {
        val quests = mutableMapOf<String, Quest>()
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM quests")
        val rs = stmt.executeQuery()

        while (rs.next()) {
            val quest = rowToQuest(rs) ?: continue
            quests[quest.id] = quest
        }

        rs.close()
        stmt.close()
        return quests
    }

    override fun loadQuest(questId: String): Quest? {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM quests WHERE quest_id = ?")
        stmt.setString(1, questId)
        val rs = stmt.executeQuery()

        val result = if (rs.next()) rowToQuest(rs) else null
        rs.close()
        stmt.close()
        return result
    }

    override fun saveQuest(quest: Quest) {
        val conn = sqlite.getConnection()

        val objectivesJson =
            gson.toJson(
                quest.objectives.map { obj ->
                    mapOf(
                        "description" to obj.description,
                        "type" to obj.type.name,
                        "target" to obj.target,
                        "required" to obj.required,
                    )
                },
            )

        val rewardsJson =
            gson.toJson(
                quest.rewards.map { reward ->
                    mapOf(
                        "type" to reward.type.name,
                        "amount" to reward.amount,
                    )
                },
            )

        val stmt =
            conn.prepareStatement(
                """REPLACE INTO quests (quest_id, title, description, type, prerequisites,
               next_quests, objectives, rewards)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            )

        stmt.setString(1, quest.id)
        stmt.setString(2, quest.title)
        stmt.setString(3, quest.description)
        stmt.setString(4, quest.type.name)
        stmt.setString(5, gson.toJson(quest.prerequisites))
        stmt.setString(6, gson.toJson(quest.nextQuests))
        stmt.setString(7, objectivesJson)
        stmt.setString(8, rewardsJson)
        stmt.executeUpdate()
        stmt.close()
    }

    override fun deleteQuest(questId: String) {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("DELETE FROM quests WHERE quest_id = ?")
        stmt.setString(1, questId)
        stmt.executeUpdate()
        stmt.close()
    }

    override fun loadPlayerQuests(playerId: UUID): Map<String, PlayerQuest> {
        val result = mutableMapOf<String, PlayerQuest>()
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM player_quests WHERE player_id = ?")
        stmt.setString(1, playerId.toString())
        val rs = stmt.executeQuery()

        while (rs.next()) {
            val questId = rs.getString("quest_id") ?: continue
            val status = QuestStatus.valueOf(rs.getString("status") ?: "NOT_STARTED")
            val completionDate = rs.getLong("completion_date")
            val playerQuest = PlayerQuest(questId, playerId, status, completionDate)

            val progressJson = rs.getString("objective_progress")
            if (progressJson != null) {
                val progressMap: Map<String, Double> =
                    gson.fromJson(
                        progressJson,
                        object : TypeToken<Map<String, Double>>() {}.type,
                    )
                for ((key, value) in progressMap) {
                    val index = key.toIntOrNull() ?: continue
                    playerQuest.objectiveProgress[index] = value.toInt()
                }
            }

            result[questId] = playerQuest
        }

        rs.close()
        stmt.close()
        return result
    }

    override fun savePlayerQuest(
        playerId: UUID,
        playerQuest: PlayerQuest,
    ) {
        val conn = sqlite.getConnection()

        val progressMap = mutableMapOf<String, Int>()
        for ((index, progress) in playerQuest.objectiveProgress) {
            progressMap[index.toString()] = progress
        }

        val stmt =
            conn.prepareStatement(
                """REPLACE INTO player_quests (player_id, quest_id, status, completion_date,
               objective_progress)
               VALUES (?, ?, ?, ?, ?)""",
            )

        stmt.setString(1, playerId.toString())
        stmt.setString(2, playerQuest.questId)
        stmt.setString(3, playerQuest.status.name)
        stmt.setLong(4, playerQuest.completionDate)
        stmt.setString(5, gson.toJson(progressMap))
        stmt.executeUpdate()
        stmt.close()
    }

    override fun deletePlayerQuests(playerId: UUID) {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("DELETE FROM player_quests WHERE player_id = ?")
        stmt.setString(1, playerId.toString())
        stmt.executeUpdate()
        stmt.close()
    }

    private fun rowToQuest(rs: java.sql.ResultSet): Quest? {
        val questId = rs.getString("quest_id") ?: return null
        val title = rs.getString("title") ?: return null
        val description = rs.getString("description") ?: ""
        val type = QuestType.valueOf(rs.getString("type") ?: "MAIN")

        val objectivesJson = rs.getString("objectives")
        val objectives: List<QuestObjective> =
            if (objectivesJson != null) {
                val objList: List<Map<String, Any>> =
                    gson.fromJson(
                        objectivesJson,
                        object : TypeToken<List<Map<String, Any>>>() {}.type,
                    )
                objList.map { obj ->
                    QuestObjective(
                        description = obj["description"] as? String ?: "",
                        type = ObjectiveType.valueOf(obj["type"] as? String ?: "KILL"),
                        target = obj["target"] as? String ?: "",
                        required = (obj["required"] as? Double)?.toInt() ?: 1,
                    )
                }
            } else {
                emptyList()
            }

        val rewardsJson = rs.getString("rewards")
        val rewards: List<QuestReward> =
            if (rewardsJson != null) {
                val rewList: List<Map<String, Any>> =
                    gson.fromJson(
                        rewardsJson,
                        object : TypeToken<List<Map<String, Any>>>() {}.type,
                    )
                rewList.map { rew ->
                    QuestReward(
                        type = RewardType.valueOf(rew["type"] as? String ?: "EXPERIENCE"),
                        amount = (rew["amount"] as? Double)?.toInt() ?: 0,
                    )
                }
            } else {
                emptyList()
            }

        val prerequisitesJson = rs.getString("prerequisites")
        val prerequisites: MutableList<String> =
            if (prerequisitesJson != null) {
                gson.fromJson(prerequisitesJson, object : TypeToken<MutableList<String>>() {}.type)
            } else {
                mutableListOf()
            }

        val nextQuestsJson = rs.getString("next_quests")
        val nextQuests: MutableList<String> =
            if (nextQuestsJson != null) {
                gson.fromJson(nextQuestsJson, object : TypeToken<MutableList<String>>() {}.type)
            } else {
                mutableListOf()
            }

        return Quest(questId, title, description, type, objectives, rewards, prerequisites, nextQuests)
    }
}
