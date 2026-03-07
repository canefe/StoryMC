package com.canefe.story.player

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.storage.PlayerStorage
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.logging.Level

class PlayerManager(
    private val plugin: Story,
    private var playerStorage: PlayerStorage,
) {
    fun updateStorage(storage: PlayerStorage) {
        playerStorage = storage
    }

    // Player to NPC mapping
    val playerCurrentNPC = HashMap<UUID, UUID>()

    // Player to conversation ID mapping for spying
    private val playerSpyingConversation = HashMap<UUID, Int>()

    // Players who disabled right-click interactions
    private val disabledPlayers = mutableListOf<String>()

    // Admins who disabled global hearing
    val disabledHearing = mutableListOf<UUID>()

    // Quest data
    private val playerQuestTitles = HashMap<UUID, String>()
    private val playerQuestObjectives = HashMap<UUID, String>()

    // Teams
    private val teams = HashMap<String, MutableSet<UUID>>()

    // Stores last known StoryLocation per player
    val lastLocation = mutableMapOf<UUID, StoryLocation?>()
    val titleCooldowns = mutableMapOf<UUID, Long>()
    private val TITLE_COOLDOWN_MS = 1000L // 3 seconds

    init {
        load()
    }

    fun canShowTitle(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = titleCooldowns[playerId] ?: 0L

        return if (now - last >= TITLE_COOLDOWN_MS) {
            titleCooldowns[playerId] = now
            true
        } else {
            false
        }
    }

    // Player-NPC interaction methods

    fun setCurrentNPC(
        player: UUID,
        npc: UUID,
    ) {
        playerCurrentNPC[player] = npc
    }

    fun getCurrentNPC(player: UUID): UUID? = playerCurrentNPC[player]

    fun removeCurrentNPC(player: UUID) {
        playerCurrentNPC.remove(player)
    }

    // Spying methods

    fun setSpyingConversation(
        player: UUID,
        conversationId: Int,
    ) {
        playerSpyingConversation[player] = conversationId
    }

    fun getSpyingConversation(player: Player): Conversation? =
        plugin.conversationManager.getConversationById(playerSpyingConversation[player.uniqueId] ?: -1)

    fun stopSpying(player: UUID) {
        playerSpyingConversation.remove(player)
    }

    // Chat toggle methods

    fun togglePlayerInteractions(
        executor: Player?,
        target: Player?,
    ) {
        val targetName = target?.name ?: return

        if (disabledPlayers.contains(targetName)) {
            disabledPlayers.remove(targetName)
            executor?.sendSuccess("NPC interactions enabled for $targetName")
        } else {
            disabledPlayers.add(targetName)
            executor?.sendError("NPC interactions disabled for $targetName")
        }
        saveDisabledPlayers()
    }

    fun isPlayerDisabled(player: Player): Boolean = disabledPlayers.contains(player.name)

    fun getDisabledPlayers(): List<String> = disabledPlayers.toList()

    // Quest methods

    fun setPlayerQuest(
        player: Player,
        title: String,
        objective: String,
    ) {
        val playerUUID = player.uniqueId
        playerQuestTitles[playerUUID] = title
        playerQuestObjectives[playerUUID] = objective

        saveData()
    }

    fun clearPlayerQuest(player: Player) {
        val playerUUID = player.uniqueId
        playerQuestTitles.remove(playerUUID)
        playerQuestObjectives.remove(playerUUID)
        playerStorage.clearPlayerQuestDisplay(playerUUID)
        saveData()
    }

    fun showPlayerQuest(
        viewer: Player,
        target: Player,
    ) {
        val targetUUID = target.uniqueId
        val title = playerQuestTitles[targetUUID] ?: ""
        val objective = playerQuestObjectives[targetUUID] ?: ""

        if (title.isEmpty() || objective.isEmpty()) {
            viewer.sendInfo("$target.name has no active quest.")
        } else {
            viewer.sendInfo("<gold>${target.name}'s Quest:</gold> <yellow>$title</yellow>")
            viewer.sendInfo("Objective: <white>$objective</white>")
        }
    }

    fun getQuestTitle(player: Player): String = playerQuestTitles[player.uniqueId] ?: ""

    fun getQuestObjective(player: Player): String = playerQuestObjectives[player.uniqueId] ?: "> No quests active."

    // Team methods

    fun createTeam(
        teamName: String,
        sender: Player,
    ) {
        if (teams.containsKey(teamName)) {
            sender.sendError("Team '$teamName' already exists!")
            return
        }

        teams[teamName] = HashSet()
        sender.sendSuccess("Created team: <gold>$teamName</gold>")
        saveData()
    }

    fun addPlayerToTeam(
        teamName: String,
        target: Player,
        sender: Player,
    ) {
        if (!teams.containsKey(teamName)) {
            sender.sendError("Team '$teamName' does not exist!")
            return
        }

        // Remove player from any other teams first
        val currentTeam = getPlayerTeam(target.uniqueId)
        if (currentTeam != null && currentTeam != teamName) {
            teams[currentTeam]?.remove(target.uniqueId)
        }

        // Add to new team
        teams[teamName]?.add(target.uniqueId)
        sender.sendSuccess("Added ${target.name} to team: <gold>$teamName</gold>")
        target.sendSuccess("You have been added to team: <gold>$teamName</gold>")
        saveData()
    }

    fun removePlayerFromTeam(
        teamName: String,
        target: Player,
        sender: Player,
    ) {
        if (!teams.containsKey(teamName)) {
            sender.sendError("Team '$teamName' does not exist!")
            return
        }

        val removed = teams[teamName]?.remove(target.uniqueId) ?: false
        if (removed) {
            sender.sendSuccess("Removed ${target.name} from team: <gold>$teamName</gold>")
            target.sendSuccess("You have been removed from team: <gold>$teamName</gold>")
        } else {
            sender.sendError("${target.name} is not a member of team '$teamName'.")
        }
        saveData()
    }

    fun listTeams(
        player: Player,
        teamName: String?,
    ) {
        if (teamName != null) {
            if (!teams.containsKey(teamName)) {
                player.sendError("Team '$teamName' does not exist!")
                return
            }

            val members = teams[teamName]
            player.sendInfo("Team <gold>'$teamName'</gold> (${members?.size ?: 0} members):")
            members?.forEach { uuid ->
                val memberName = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                player.sendInfo("<yellow>$memberName</yellow>")
            }
        } else {
            player.sendInfo("Teams <gold>(${teams.size})</gold>:")
            teams.forEach { (name, members) ->
                player.sendInfo("<yellow>$name</yellow>: <gray>${members.size} members</gray>")
            }
        }
    }

    fun deleteTeam(
        teamName: String,
        sender: Player,
    ) {
        if (!teams.containsKey(teamName)) {
            sender.sendError("Team '$teamName' does not exist!")
            return
        }

        teams.remove(teamName)
        sender.sendSuccess("Deleted team: <gold>$teamName</gold>")
        saveData()
    }

    fun getPlayerTeam(playerUUID: UUID): String? {
        for ((teamName, members) in teams) {
            if (members.contains(playerUUID)) {
                return teamName
            }
        }
        return null
    }

    // Data saving/loading methods

    fun saveData() {
        saveTeamsAndQuests()
        saveDisabledPlayers()
    }

    private fun saveTeamsAndQuests() {
        try {
            playerStorage.saveTeams(teams)

            // Save each player's quest display
            for (playerUUID in playerQuestTitles.keys) {
                val title = playerQuestTitles[playerUUID] ?: continue
                val objective = playerQuestObjectives[playerUUID] ?: continue
                playerStorage.savePlayerQuestDisplay(playerUUID, title, objective)
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save teams and quests data", e)
        }
    }

    private fun saveDisabledPlayers() {
        try {
            playerStorage.saveDisabledPlayers(disabledPlayers)
        } catch (e: Exception) {
            plugin.logger.severe("Could not save disabled players: ${e.message}")
        }
    }

    fun load() {
        loadTeamsAndQuests()
        loadDisabledPlayers()
    }

    private fun loadTeamsAndQuests() {
        try {
            // Load teams
            teams.putAll(playerStorage.loadTeams())

            // Load player quest displays
            for ((playerId, questData) in playerStorage.loadPlayerQuestDisplay()) {
                playerQuestTitles[playerId] = questData.first
                playerQuestObjectives[playerId] = questData.second
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to load teams and quests data", e)
        }
    }

    private fun loadDisabledPlayers() {
        try {
            disabledPlayers.clear()
            disabledPlayers.addAll(playerStorage.loadDisabledPlayers())
        } catch (e: Exception) {
            plugin.logger.severe("Could not load disabled players: ${e.message}")
        }
    }
}
