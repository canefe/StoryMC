package com.canefe.story.storage.mongo

import com.canefe.story.quest.*
import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.QuestStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.UUID

class MongoQuestStorage(
    private val mongoClient: MongoClientManager,
) : QuestStorage {
    private val questCollection get() = mongoClient.getCollection("quests")
    private val playerQuestCollection get() = mongoClient.getCollection("player_quests")

    override fun loadAllQuests(): Map<String, Quest> {
        val quests = mutableMapOf<String, Quest>()
        for (doc in questCollection.find()) {
            val quest = documentToQuest(doc) ?: continue
            quests[quest.id] = quest
        }
        return quests
    }

    override fun loadQuest(questId: String): Quest? {
        val doc = questCollection.find(Filters.eq("questId", questId)).first() ?: return null
        return documentToQuest(doc)
    }

    override fun saveQuest(quest: Quest) {
        val doc = questToDocument(quest)
        questCollection.replaceOne(
            Filters.eq("questId", quest.id),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteQuest(questId: String) {
        questCollection.deleteOne(Filters.eq("questId", questId))
    }

    override fun loadPlayerQuests(playerId: UUID): Map<String, PlayerQuest> {
        val result = mutableMapOf<String, PlayerQuest>()
        for (doc in playerQuestCollection.find(Filters.eq("playerId", playerId.toString()))) {
            val questId = doc.getString("questId") ?: continue
            val status = QuestStatus.valueOf(doc.getString("status") ?: "NOT_STARTED")
            val completionDate = doc.getLong("completionDate") ?: 0L
            val playerQuest = PlayerQuest(questId, playerId, status, completionDate)

            val progressDoc = doc.get("objectiveProgress", Document::class.java)
            progressDoc?.keys?.forEach { key ->
                val index = key.toIntOrNull() ?: return@forEach
                playerQuest.objectiveProgress[index] = progressDoc.getInteger(key)
            }

            result[questId] = playerQuest
        }
        return result
    }

    override fun savePlayerQuest(
        playerId: UUID,
        playerQuest: PlayerQuest,
    ) {
        val progressDoc = Document()
        for ((index, progress) in playerQuest.objectiveProgress) {
            progressDoc.append(index.toString(), progress)
        }

        val doc =
            Document()
                .append("playerId", playerId.toString())
                .append("questId", playerQuest.questId)
                .append("status", playerQuest.status.name)
                .append("completionDate", playerQuest.completionDate)
                .append("objectiveProgress", progressDoc)

        playerQuestCollection.replaceOne(
            Filters.and(
                Filters.eq("playerId", playerId.toString()),
                Filters.eq("questId", playerQuest.questId),
            ),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deletePlayerQuests(playerId: UUID) {
        playerQuestCollection.deleteMany(Filters.eq("playerId", playerId.toString()))
    }

    private fun questToDocument(quest: Quest): Document {
        val objectives =
            quest.objectives.map { obj ->
                Document()
                    .append("description", obj.description)
                    .append("type", obj.type.name)
                    .append("target", obj.target)
                    .append("required", obj.required)
            }

        val rewards =
            quest.rewards.map { reward ->
                Document()
                    .append("type", reward.type.name)
                    .append("amount", reward.amount)
            }

        return Document()
            .append("questId", quest.id)
            .append("title", quest.title)
            .append("description", quest.description)
            .append("type", quest.type.name)
            .append("objectives", objectives)
            .append("rewards", rewards)
            .append("prerequisites", quest.prerequisites)
            .append("nextQuests", quest.nextQuests)
    }

    private fun documentToQuest(doc: Document): Quest? {
        val questId = doc.getString("questId") ?: return null
        val title = doc.getString("title") ?: return null
        val description = doc.getString("description") ?: ""
        val type = QuestType.valueOf(doc.getString("type") ?: "MAIN")

        val objectives =
            (doc.getList("objectives", Document::class.java) ?: emptyList()).map { objDoc ->
                QuestObjective(
                    description = objDoc.getString("description") ?: "",
                    type = ObjectiveType.valueOf(objDoc.getString("type") ?: "KILL"),
                    target = objDoc.getString("target") ?: "",
                    required = objDoc.getInteger("required", 1),
                )
            }

        val rewards =
            (doc.getList("rewards", Document::class.java) ?: emptyList()).map { rewDoc ->
                QuestReward(
                    type = RewardType.valueOf(rewDoc.getString("type") ?: "EXPERIENCE"),
                    amount = rewDoc.getInteger("amount", 0),
                )
            }

        val prerequisites = doc.getList("prerequisites", String::class.java)?.toMutableList() ?: mutableListOf()
        val nextQuests = doc.getList("nextQuests", String::class.java)?.toMutableList() ?: mutableListOf()

        return Quest(questId, title, description, type, objectives, rewards, prerequisites, nextQuests)
    }
}
