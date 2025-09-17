package com.canefe.story.quest

import org.bukkit.inventory.ItemStack
import java.util.*

enum class QuestType {
    MAIN, // Main storyline quests
    SIDE, // Optional side quests
    DAILY, // Repeatable daily quests
    EVENT, // Special event quests
}

enum class QuestStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

enum class ObjectiveType {
    KILL, // Kill specific entities
    COLLECT, // Collect items
    TALK, // Talk to NPCs
    EXPLORE, // Visit locations
    CRAFT, // Craft items
    USE, // Use specific items or abilities
}

enum class RewardType {
    ITEM, // Item rewards
    EXPERIENCE, // XP rewards
    REPUTATION, // Reputation with factions
    MONEY, // Currency rewards
}

class Quest(
    val id: String,
    val title: String,
    val description: String,
    val type: QuestType = QuestType.MAIN,
    val objectives: List<QuestObjective>,
    val rewards: List<QuestReward>,
    val prerequisites: MutableList<String> = mutableListOf(),
    val nextQuests: MutableList<String> = mutableListOf(),
)

class QuestObjective(
    val description: String,
    val type: ObjectiveType,
    val target: String,
    var progress: Int = 0,
    val required: Int = 1,
)

class QuestReward(
    val type: RewardType,
    val amount: Int = 0,
    private val itemStack: ItemStack? = null,
) {
    fun getItemStack(): ItemStack? = itemStack
}

class PlayerQuest(
    val questId: String,
    val playerId: UUID,
    var status: QuestStatus,
    var completionDate: Long = 0,
    val objectiveProgress: MutableMap<Int, Int> = mutableMapOf(),
)
