@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.quest.*
import com.canefe.story.storage.QuestStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger

@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlQuestStorage(
    private val questFolder: File,
    private val playerQuestFolder: File,
    private val logger: Logger,
) : QuestStorage {
    init {
        if (!questFolder.exists()) questFolder.mkdirs()
        if (!playerQuestFolder.exists()) playerQuestFolder.mkdirs()
    }

    override fun loadAllQuests(): Map<String, Quest> {
        val quests = mutableMapOf<String, Quest>()
        val files = questFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return quests

        for (file in files) {
            try {
                val questId = file.name.replace(".yml", "")
                val quest = loadQuest(questId)
                if (quest != null) {
                    quests[questId] = quest
                }
            } catch (e: Exception) {
                logger.warning("Error loading quest from file: ${file.name}")
            }
        }
        return quests
    }

    override fun loadQuest(questId: String): Quest? {
        val questFile = File(questFolder, "$questId.yml")
        if (!questFile.exists()) return null

        val config = YamlConfiguration.loadConfiguration(questFile)

        val title = config.getString("title") ?: return null
        val description = config.getString("description") ?: ""
        val type = QuestType.valueOf(config.getString("type", "MAIN") ?: "MAIN")
        val prerequisites = config.getStringList("prerequisites").toMutableList()
        val nextQuests = config.getStringList("nextQuests").toMutableList()

        val objectives = mutableListOf<QuestObjective>()
        val objectivesSection = config.getConfigurationSection("objectives") ?: return null

        for (key in objectivesSection.getKeys(false)) {
            val objDesc = objectivesSection.getString("$key.description") ?: continue
            val objType = ObjectiveType.valueOf(objectivesSection.getString("$key.type") ?: continue)
            val objTarget = objectivesSection.getString("$key.target") ?: ""
            val objRequired = objectivesSection.getInt("$key.required", 1)
            objectives.add(QuestObjective(objDesc, objType, objTarget, 0, objRequired))
        }

        val rewards = mutableListOf<QuestReward>()
        val rewardsSection = config.getConfigurationSection("rewards")
        if (rewardsSection != null) {
            for (key in rewardsSection.getKeys(false)) {
                val rewardType = RewardType.valueOf(rewardsSection.getString("$key.type") ?: continue)
                val amount = rewardsSection.getInt("$key.amount", 0)
                rewards.add(QuestReward(rewardType, amount))
            }
        }

        return Quest(questId, title, description, type, objectives, rewards, prerequisites, nextQuests)
    }

    override fun saveQuest(quest: Quest) {
        val questFile = File(questFolder, "${quest.id}.yml")
        val config = YamlConfiguration()

        config.set("title", quest.title)
        config.set("description", quest.description)
        config.set("type", quest.type.name)
        config.set("prerequisites", quest.prerequisites)
        config.set("nextQuests", quest.nextQuests)

        for (i in quest.objectives.indices) {
            val obj = quest.objectives[i]
            config.set("objectives.$i.description", obj.description)
            config.set("objectives.$i.type", obj.type.name)
            config.set("objectives.$i.target", obj.target)
            config.set("objectives.$i.required", obj.required)
        }

        for (i in quest.rewards.indices) {
            val reward = quest.rewards[i]
            config.set("rewards.$i.type", reward.type.name)
            config.set("rewards.$i.amount", reward.amount)
        }

        try {
            config.save(questFile)
        } catch (e: IOException) {
            logger.severe("Could not save quest: ${quest.id}")
        }
    }

    override fun deleteQuest(questId: String) {
        val questFile = File(questFolder, "$questId.yml")
        if (questFile.exists()) questFile.delete()
    }

    override fun loadPlayerQuests(playerId: UUID): Map<String, PlayerQuest> {
        val playerQuestsMap = mutableMapOf<String, PlayerQuest>()
        val playerFile = File(playerQuestFolder, "$playerId.yml")

        if (!playerFile.exists()) return playerQuestsMap

        val config = YamlConfiguration.loadConfiguration(playerFile)

        for (questId in config.getKeys(false)) {
            val statusStr = config.getString("$questId.status") ?: continue
            val status = QuestStatus.valueOf(statusStr)
            val completionDate = config.getLong("$questId.completionDate", 0)

            val playerQuest = PlayerQuest(questId, playerId, status, completionDate)

            config.getConfigurationSection("$questId.progress")?.let { progressSection ->
                for (key in progressSection.getKeys(false)) {
                    val index = key.toIntOrNull() ?: continue
                    val progress = progressSection.getInt(key)
                    playerQuest.objectiveProgress[index] = progress
                }
            }

            playerQuestsMap[questId] = playerQuest
        }

        return playerQuestsMap
    }

    override fun savePlayerQuest(
        playerId: UUID,
        playerQuest: PlayerQuest,
    ) {
        val playerFile = File(playerQuestFolder, "$playerId.yml")
        val config =
            if (playerFile.exists()) {
                YamlConfiguration.loadConfiguration(playerFile)
            } else {
                YamlConfiguration()
            }

        val questId = playerQuest.questId
        config.set("$questId.status", playerQuest.status.name)
        config.set("$questId.completionDate", playerQuest.completionDate)

        for ((index, progress) in playerQuest.objectiveProgress) {
            config.set("$questId.progress.$index", progress)
        }

        try {
            config.save(playerFile)
        } catch (e: IOException) {
            logger.severe("Could not save player quest for player $playerId, quest $questId")
        }
    }

    override fun deletePlayerQuests(playerId: UUID) {
        val playerFile = File(playerQuestFolder, "$playerId.yml")
        if (playerFile.exists()) playerFile.delete()
    }
}
