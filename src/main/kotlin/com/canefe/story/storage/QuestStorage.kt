package com.canefe.story.storage

import com.canefe.story.quest.PlayerQuest
import com.canefe.story.quest.Quest
import java.util.UUID

interface QuestStorage {
    fun loadAllQuests(): Map<String, Quest>

    fun loadQuest(questId: String): Quest?

    fun saveQuest(quest: Quest)

    fun deleteQuest(questId: String)

    fun loadPlayerQuests(playerId: UUID): Map<String, PlayerQuest>

    fun savePlayerQuest(
        playerId: UUID,
        playerQuest: PlayerQuest,
    )

    fun deletePlayerQuests(playerId: UUID)
}
