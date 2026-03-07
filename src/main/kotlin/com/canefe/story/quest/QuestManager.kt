package com.canefe.story.quest

import com.canefe.story.Story
import com.canefe.story.api.event.QuestCompleteEvent
import com.canefe.story.command.story.quest.QuestCommand.ObjectiveInfo
import com.canefe.story.storage.QuestStorage
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.getOrDefault
import kotlin.text.get

class QuestManager private constructor(
    private val plugin: Story,
    private var questStorage: QuestStorage,
) {
    fun updateStorage(storage: QuestStorage) {
        questStorage = storage
    }

    private val quests = ConcurrentHashMap<String, Quest>()
    private val playerQuests = ConcurrentHashMap<UUID, MutableMap<String, PlayerQuest>>()

    private var questReference: YamlConfiguration? = null

    // Load the reference data
    private fun loadQuestReference() {
        val referenceFile = File(plugin.dataFolder, "quest_reference.yml")

        // Copy default if doesn't exist
        if (!referenceFile.exists()) {
            plugin.saveResource("quest_reference.yml", false)
        }

        questReference = YamlConfiguration.loadConfiguration(referenceFile)
        plugin.logger.info("Loaded quest reference data")
    }

    // Get valid collectible items
    fun getValidCollectibles(): List<String> = questReference?.getStringList("collectibleItems") ?: emptyList()

    // Get valid killable entities
    fun getValidKillTargets(): List<String> = questReference?.getStringList("killableEntities") ?: emptyList()

    // Get valid locations
    fun getValidLocations(): List<String> = plugin.locationManager.getAllLocations().map { it.name }

    fun getValidTalkTargets(npc: NPC): List<String> {
        // First get the location of npc (Get his npcData)
        val npcData = plugin.npcDataManager.getNPCData(npc.name)
        val location = npcData?.storyLocation

        // get all npc names from that StoryLocation
        for (npcName in plugin.npcDataManager.getAllNPCNames()) {
            val npcFile = File(plugin.npcDataManager.npcDirectory, "$npcName.yml")
            val npcConfig = YamlConfiguration.loadConfiguration(npcFile)
            val npcLocation = npcConfig.getString("location") ?: continue

            if (npcLocation == location?.name) {
                return listOf(npcName)
            }
        }

        return listOf(npc.name)
    }

    private val questFolder: File =
        File(plugin.dataFolder, "quests").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    private val playerQuestFolder: File =
        File(plugin.dataFolder, "playerquests").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    init {
        loadAllQuests()
        loadAllPlayerQuests()
    }

    fun loadAllQuests() {
        quests.clear()
        val loaded = questStorage.loadAllQuests()
        quests.putAll(loaded)
        plugin.logger.info("Loaded ${quests.size} quests")
    }

    fun loadQuest(questId: String): Quest? = questStorage.loadQuest(questId)

    fun loadAllPlayerQuests() {
        // Player quests are loaded lazily when requested via getPlayerQuests()
        playerQuests.clear()
        plugin.logger.info("Player quests cache cleared (will load on demand)")
    }

    fun resetQuest(
        player: Player,
        questId: String,
    ) {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[questId] ?: return

        playerQuest.status = QuestStatus.NOT_STARTED
        playerQuest.objectiveProgress.clear()
        player.sendSuccess("Quest reset: <yellow>$questId")
        savePlayerQuest(player.uniqueId, playerQuest)
    }

    fun saveQuest(quest: Quest) {
        questStorage.saveQuest(quest)
    }

    fun getQuest(questId: String): Quest? = quests[questId]

    fun getAllQuests(): Collection<Quest> = quests.values

    // A function to return all quests with associated players
    fun getAllQuestsWithPlayers(): Map<String, List<OfflinePlayer>> {
        val questPlayerMap = mutableMapOf<String, MutableList<OfflinePlayer>>()

        for ((playerId, playerQuestMap) in playerQuests) {
            for (questId in playerQuestMap.keys) {
                val player = plugin.server.getOfflinePlayer(playerId)
                questPlayerMap.getOrPut(questId) { mutableListOf() }.add(player)
            }
        }

        return questPlayerMap
    }

    // A function to get all quests of single player
    fun getAllQuestsOfPlayer(playerId: UUID): Map<Quest, QuestStatus> {
        val playerQuestMap = mutableMapOf<Quest, QuestStatus>()

        val quests = getAllQuestsWithPlayers()
        for ((questId, players) in quests) {
            if (players.any { it.uniqueId == playerId }) {
                val quest = getQuest(questId)
                if (quest != null) {
                    playerQuestMap[quest] = getPlayerQuestStatus(plugin.server.getOfflinePlayer(playerId), questId)
                }
            }
        }

        return playerQuestMap
    }

    // Player quest management
    fun assignQuestToPlayer(
        player: Player,
        questId: String,
    ): Boolean {
        val quest = getQuest(questId) ?: return false

        // Check prerequisites
        for (prerequisiteId in quest.prerequisites) {
            val prereqStatus = getPlayerQuestStatus(player, prerequisiteId)
            if (prereqStatus != QuestStatus.COMPLETED) {
                return false
            }
        }

        val playerQuest = PlayerQuest(questId, player.uniqueId, QuestStatus.IN_PROGRESS)

        val playerQuestMap = playerQuests.getOrPut(player.uniqueId) { mutableMapOf() }
        playerQuestMap[questId] = playerQuest

        savePlayerQuest(player.uniqueId, playerQuest)
        plugin.playerManager.setPlayerQuest(player, quest.title, quest.objectives[0].description)

        newQuest(player, quest)

        return true
    }

    fun getPlayerQuests(playerId: UUID): Map<String, PlayerQuest> {
        loadPlayerQuests(playerId)
        return playerQuests.getOrDefault(playerId, mutableMapOf())
    }

    fun getPlayerQuestStatus(
        player: OfflinePlayer,
        questId: String,
    ): QuestStatus {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        return playerQuestMap[questId]?.status ?: QuestStatus.NOT_STARTED
    }

    /**
     * Registers a dynamically generated quest into the quests collection and saves it to disk
     */
    fun registerQuest(
        quest: Quest,
        npc: NPC? = null,
    ) {
        val turnInObjective =
            npc?.let {
                QuestObjective(
                    description = "Return to ${it.name}",
                    type = ObjectiveType.TALK,
                    target = it.name,
                    required = 1,
                )
            }

        val newQuest =
            Quest(
                id = quest.id,
                title = quest.title,
                description = quest.description,
                type = quest.type,
                objectives = quest.objectives + (turnInObjective?.let { listOf(it) } ?: emptyList()),
                rewards = quest.rewards,
                prerequisites = quest.prerequisites,
                nextQuests = quest.nextQuests,
            )

        quests[newQuest.id] = newQuest
        saveQuest(newQuest)
        plugin.logger.info("Registered new quest: ${newQuest.title} (ID: ${newQuest.id})")
    }

    fun updateObjectiveProgress(
        player: Player,
        questId: String,
        objectiveType: ObjectiveType? = null,
        target: String? = null,
        progress: Int = 1,
    ) {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[questId] ?: return

        val quest = getQuest(questId) ?: return

        if (playerQuest.status != QuestStatus.IN_PROGRESS) return

        var finalTarget = target
        var finalObjectiveType = objectiveType

        // Find the current objective (first incomplete objective)
        val currentObjectiveMap = getCurrentObjective(player, questId)
        val currentObjectiveIndex = currentObjectiveMap?.keys?.firstOrNull()
        val currentObjective = currentObjectiveIndex?.let { currentObjectiveMap[it] }

        if (currentObjective == null) return
        if (target == null) finalTarget = currentObjective.target
        if (objectiveType == null) finalObjectiveType = currentObjective.type
        // Only update if the current objective matches the type and target
        if (currentObjective.type == finalObjectiveType &&
            (currentObjective.target.isEmpty() || currentObjective.target.lowercase() == finalTarget.lowercase())
        ) {
            val newProgress =
                minOf(
                    playerQuest.objectiveProgress.getOrDefault(currentObjectiveIndex, 0) + progress,
                    currentObjective.required,
                )
            playerQuest.objectiveProgress[currentObjectiveIndex] = newProgress

            player.sendRaw(
                "<gray>Quest progress: <yellow>${currentObjective.description}</yellow> (<green>$newProgress</green>/" +
                    "<gold>${currentObjective.required}</gold>)",
            )
            val mm = plugin.miniMessage
            if (newProgress >= currentObjective.required) {
                player.sendRaw("<green>Objective completed: <yellow>${currentObjective.description}")
                val audience = Audience.audience(player)
                val title =
                    Title.title(
                        mm.deserialize("<gold><b>${currentObjective.description}</b>"),
                        mm.deserialize("<green>Objective Completed"),
                        Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1)),
                    )

                player.playSound(player, "entity.player.levelup", 1f, 1f)

                audience.showTitle(title)

                // Check if all objectives are complete
                val allObjectivesComplete =
                    quest.objectives.indices.all { index ->
                        playerQuest.objectiveProgress.getOrDefault(index, 0) >= quest.objectives[index].required
                    }

                if (allObjectivesComplete) {
                    completeQuest(player, questId)
                } else {
                    val nextObjective = getCurrentObjective(player, questId)!!.values.first()
                    plugin.playerManager.setPlayerQuest(player, quest.title, nextObjective?.description ?: "")
                    savePlayerQuest(player.uniqueId, playerQuest)
                    player.sendSuccess(
                        "<gray>Next objective: <yellow>${nextObjective?.description} <gray>(<green>0<gray>/<gold>${nextObjective?.required}<gray>)",
                    )
                }
            } else {
                savePlayerQuest(player.uniqueId, playerQuest)
            }
        }
    }

    // Helper method to get the next objective after the current one
    private fun getNextObjective(
        player: Player,
        questId: String,
    ): QuestObjective? {
        val currentObjectiveMap = getCurrentObjective(player, questId) ?: return null
        val currentObjectiveIndex = currentObjectiveMap.keys.firstOrNull() ?: return null
        val quest = getQuest(questId) ?: return null

        // Return the next objective if it exists
        return if (currentObjectiveIndex + 1 < quest.objectives.size) {
            quest.objectives[currentObjectiveIndex + 1]
        } else {
            null
        }
    }

    fun updatePlaceholders(
        player: Player,
        quest: Quest,
    ) {
        val currentObjectiveMap = quest.id.let { plugin.questManager.getCurrentObjective(player, it) }
        val currentObjectiveIndex = currentObjectiveMap?.keys?.firstOrNull()
        val currentObjective = currentObjectiveIndex?.let { currentObjectiveMap[it] }
        if (currentObjective != null) {
            plugin.playerManager.setPlayerQuest(player, quest.title, currentObjective.description)
        } else {
            plugin.playerManager.clearPlayerQuest(player)
        }
    }

    fun completeQuest(
        player: Player,
        questId: String,
    ) {
        val mm = plugin.miniMessage
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[questId] ?: return

        val quest = getQuest(questId) ?: return

        // Run QuestCompleteEvent and check if it is cancelled
        val event = QuestCompleteEvent(player, quest)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) {
            plugin.logger.info("Quest completion cancelled for player ${player.name} on quest ${quest.title}")
            return
        }

        playerQuest.status = QuestStatus.COMPLETED
        playerQuest.completionDate = System.currentTimeMillis()

        // Give rewards
        giveRewards(player, quest)

        player.sendSuccess("Quest completed: <yellow>${quest.title}")
        val audience = Audience.audience(player)
        val title =
            Title.title(
                mm.deserialize("<gold><b>Quest Completed</b>"),
                mm.deserialize("<gray>${quest.title}"),
                Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1)),
            )

        player.playSound(player, "ui.toast.challenge_complete", 1f, 1f)

        audience.showTitle(title)
        plugin.logger.info("Player ${player.name} completed quest: ${quest.title}")
        plugin.playerManager.clearPlayerQuest(player)

        // Make next quests available
        for (nextQuestId in quest.nextQuests) {
            val nextQuest = getQuest(nextQuestId)
            if (nextQuest != null) {
                player.sendInfo("New quest available: <yellow>${nextQuest.title}")
            }
        }

        savePlayerQuest(player.uniqueId, playerQuest)

        // Get Questgiver NPC (npc id stored in quest id) ex: npc_[npcId]_[questUniqueId]
        val parts = quest.id.split("_")
        val npcId = if (parts.size >= 2) parts[1] else return
        val npcName =
            if (npcId.matches(Regex("\\d+"))) {
                CitizensAPI.getNPCRegistry().getById(npcId.toInt())?.name
            } else {
                // If not numeric, we don't care and will simply return
                null
            } ?: return
        val contextPrompt =
            """
            You have heard that ${EssentialsUtils.getNickname(
                player.name,
            )} has completed the quest you gave: ${quest.title}.
            """.trimIndent()
        plugin.npcResponseService.generateNPCMemory(npcName, "event", contextPrompt)
    }

    // OfflinePlayer completeQuest
    fun completeQuest(
        player: OfflinePlayer,
        questId: String,
    ) {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[questId] ?: return

        val quest = getQuest(questId) ?: return

        playerQuest.status = QuestStatus.COMPLETED
        playerQuest.completionDate = System.currentTimeMillis()

        savePlayerQuest(player.uniqueId, playerQuest)
    }

    /**
     * Fails a quest for a player, removing it from their active quests
     * @param player The player receiving the quest
     * @param quest The quest being assigned
     */
    fun failQuest(
        player: Player,
        quest: Quest,
    ) {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[quest.id] ?: return

        playerQuest.status = QuestStatus.FAILED
        playerQuest.completionDate = System.currentTimeMillis()

        savePlayerQuest(player.uniqueId, playerQuest)

        val audience = Audience.audience(player)
        val mm = plugin.miniMessage
        val title =
            Title.title(
                mm.deserialize("<red><b>Quest Failed</b>"),
                mm.deserialize("<gray>${quest.title}"),
                Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1)),
            )

        audience.showTitle(title)
        plugin.playerManager.clearPlayerQuest(player)
        player.playSound(player, "entity.ender_dragon.growl", 1f, 1f)
        player.sendRaw("<red>Quest <yellow>${quest.title} <red>has been failed.")
    }

    fun failQuest(
        player: OfflinePlayer,
        quest: Quest,
    ) {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[quest.id] ?: return

        playerQuest.status = QuestStatus.FAILED
        playerQuest.completionDate = System.currentTimeMillis()

        savePlayerQuest(player.uniqueId, playerQuest)
    }

    // info message
    fun newQuest(
        player: Player,
        quest: Quest,
    ) {
        val audience = Audience.audience(player)
        val title =
            Title.title(
                plugin.miniMessage.deserialize("<gold><b>New Quest</b>"),
                plugin.miniMessage.deserialize("<gray>${quest.title}"),
                Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1)),
            )

        player.playSound(player, "entity.player.levelup", 1f, 1f)
        player.sendSuccess("New quest received: <yellow>${quest.title}")
        audience.showTitle(title)
    }

    private fun giveRewards(
        player: Player,
        quest: Quest,
    ) {
        for (reward in quest.rewards) {
            when (reward.type) {
                RewardType.ITEM -> {
                    // Handle item rewards when implemented
                    // player.inventory.addItem(reward.getItemStack())
                }
                RewardType.EXPERIENCE -> {
                    player.giveExp(reward.amount, true)
                    player.sendMessage("§7Received §a${reward.amount} §7experience")
                }
                RewardType.REPUTATION -> {
                    // Integrate with your reputation system when implemented
                    player.sendMessage("§7Received §a${reward.amount} §7reputation")
                }
                RewardType.MONEY -> {
                    // Integrate with economy plugin if needed
                    player.sendMessage("§7Received §a${reward.amount} §7coins")
                }
            }
        }
    }

    private fun loadPlayerQuests(playerId: UUID) {
        if (playerQuests.containsKey(playerId)) return

        val playerQuestsMap = questStorage.loadPlayerQuests(playerId).toMutableMap()
        playerQuests[playerId] = playerQuestsMap

        val currentQuest = playerQuestsMap.values.lastOrNull { it.status == QuestStatus.IN_PROGRESS }
        if (currentQuest != null) {
            val quest = getQuest(currentQuest.questId)
            if (quest != null) {
                Bukkit.getPlayer(playerId)?.let { updatePlaceholders(it, quest) }
            }
        }
    }

    fun getCurrentQuest(player: Player): Quest? {
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val currentQuest = playerQuestMap.values.lastOrNull { it.status == QuestStatus.IN_PROGRESS }
        return if (currentQuest != null) {
            getQuest(currentQuest.questId)
        } else {
            null
        }
    }

    fun printQuest(
        quest: Quest,
        player: Player,
        status: Pair<String, String> = Pair("<gold>", "In Progress"),
        isAdmin: Boolean = false,
        objectiveInfo: ObjectiveInfo? = null,
    ) {
        // deconstruct status pair
        val statusColor = status.first
        val statusText = status.second

        val objectiveInfo =
            quest?.id?.let { questId ->
                val objectiveMap = plugin.questManager.getCurrentObjective(player, questId)
                val currentIndex = objectiveMap?.keys?.firstOrNull()
                val currentObjective = currentIndex?.let { objectiveMap[it] }

                ObjectiveInfo(objectiveMap, currentIndex, currentObjective)
            } ?: ObjectiveInfo(null, null, null)

        val map = objectiveInfo?.objectiveMap
        val currentIndex = objectiveInfo?.currentIndex
        val objective = objectiveInfo?.currentObjective

        player.sendRaw(
            "<yellow>===== <gold><green>${quest?.title}</green></gold> =====</yellow>",
        )
        if (isAdmin) {
            player.sendRaw("<gray>ID:</gray> <white>${quest?.id}</white>")
            player.sendRaw("")
        }
        player.sendRaw("<white>${quest?.description}")
        player.sendRaw("${statusColor}$statusText")
        player.sendRaw("<aqua>Tasks:")

        quest?.objectives?.forEachIndexed { index, obj ->
            val done =
                when {
                    // Completed objectives (index less than current objective index)
                    currentIndex != null && index < currentIndex -> "<st><green>✔"
                    // Current objective
                    currentIndex != null && index == currentIndex -> "<yellow>⟳"
                    // Not yet reached objectives
                    else -> "<red>✘"
                }

            player.sendRaw("$done - ${obj.description} (${obj.required} ${obj.type} <gold>${obj.target}</gold>)")
        }
    }

    /**
     * Returns the current objective that a player should be working on for a quest
     * @param player The player
     * @param questId The quest ID
     * @return The current objective, or null if the quest is completed or not found
     */
    fun getCurrentObjective(
        player: OfflinePlayer,
        questId: String,
    ): Map<Int, QuestObjective>? {
        // Get player quest data
        val playerQuestMap = getPlayerQuests(player.uniqueId)
        val playerQuest = playerQuestMap[questId] ?: return null

        // If quest is completed, there's no current objective
        if (playerQuest.status != QuestStatus.IN_PROGRESS) {
            return null
        }

        // Get the quest data
        val quest = getQuest(questId) ?: return null

        // Look through objectives to find the first incomplete one
        for (i in quest.objectives.indices) {
            val objective = quest.objectives[i]
            val currentProgress = playerQuest.objectiveProgress.getOrDefault(i, 0)

            // If this objective isn't complete, it's the current one
            if (currentProgress < objective.required) {
                return mapOf(i to objective)
            }
        }

        // If no incomplete objectives were found but the quest is in progress,
        // the player should complete the quest
        return null
    }

    private fun savePlayerQuest(
        playerId: UUID,
        playerQuest: PlayerQuest,
    ) {
        questStorage.savePlayerQuest(playerId, playerQuest)
        playerQuests.getOrPut(playerId) { mutableMapOf() }[playerQuest.questId] = playerQuest
    }

    fun clearPlayerQuests(playerId: UUID) {
        playerQuests.remove(playerId)
        questStorage.deletePlayerQuests(playerId)
    }

    fun loadConfig() {
        // reset cache
        quests.clear()
        playerQuests.clear()
        loadQuestReference()
        loadAllQuests()
        loadAllPlayerQuests()
    }

    companion object {
        private var instance: QuestManager? = null

        @JvmStatic
        fun getInstance(
            plugin: Story,
            questStorage: QuestStorage,
        ): QuestManager = instance ?: QuestManager(plugin, questStorage).also { instance = it }

        @JvmStatic
        fun getInstance(plugin: Story): QuestManager =
            instance
                ?: throw IllegalStateException(
                    "QuestManager not initialized. Call getInstance(plugin, questStorage) first.",
                )
    }
}
