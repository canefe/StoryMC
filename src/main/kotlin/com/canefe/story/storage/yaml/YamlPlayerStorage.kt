@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.storage.PlayerStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.logging.Logger

@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlPlayerStorage(
    private val dataFolder: File,
    private val logger: Logger,
) : PlayerStorage {
    override fun loadTeams(): Map<String, MutableSet<UUID>> {
        val teams = mutableMapOf<String, MutableSet<UUID>>()
        val teamsFile = File(dataFolder, "teams.yml")
        if (!teamsFile.exists()) return teams

        val teamsConfig = YamlConfiguration.loadConfiguration(teamsFile)
        val teamsSection = teamsConfig.getConfigurationSection("teams")

        teamsSection?.getKeys(false)?.forEach { teamName ->
            val membersList = teamsConfig.getStringList("teams.$teamName")
            val membersSet = HashSet<UUID>()

            membersList.forEach { uuidString ->
                try {
                    membersSet.add(UUID.fromString(uuidString))
                } catch (_: IllegalArgumentException) {
                    logger.warning("Invalid UUID in team $teamName: $uuidString")
                }
            }

            teams[teamName] = membersSet
        }

        return teams
    }

    override fun saveTeams(teams: Map<String, Set<UUID>>) {
        val teamsFile = File(dataFolder, "teams.yml")
        val teamsConfig = YamlConfiguration()

        for ((teamName, members) in teams) {
            teamsConfig.set("teams.$teamName", members.map { it.toString() })
        }

        teamsConfig.save(teamsFile)
    }

    override fun loadDisabledPlayers(): MutableList<String> {
        val disabledPlayersFile = File(dataFolder, "disabled-players.yml")
        if (!disabledPlayersFile.exists()) return mutableListOf()

        val config = YamlConfiguration.loadConfiguration(disabledPlayersFile)
        return config.getStringList("disabled-players").toMutableList()
    }

    override fun saveDisabledPlayers(players: List<String>) {
        val disabledPlayersFile = File(dataFolder, "disabled-players.yml")
        val config = YamlConfiguration()
        config.set("disabled-players", players)
        config.save(disabledPlayersFile)
    }

    override fun loadPlayerQuestDisplay(): Map<UUID, Pair<String, String>> {
        val result = mutableMapOf<UUID, Pair<String, String>>()
        val questsFile = File(dataFolder, "player-quests.yml")
        if (!questsFile.exists()) return result

        val config = YamlConfiguration.loadConfiguration(questsFile)
        val playersSection = config.getConfigurationSection("players")

        playersSection?.getKeys(false)?.forEach { uuidString ->
            try {
                val playerUUID = UUID.fromString(uuidString)
                val title = config.getString("players.$uuidString.title") ?: return@forEach
                val objective = config.getString("players.$uuidString.objective") ?: return@forEach
                result[playerUUID] = Pair(title, objective)
            } catch (_: IllegalArgumentException) {
                logger.warning("Invalid player UUID: $uuidString")
            }
        }

        return result
    }

    override fun savePlayerQuestDisplay(
        playerId: UUID,
        title: String,
        objective: String,
    ) {
        val questsFile = File(dataFolder, "player-quests.yml")
        val config =
            if (questsFile.exists()) {
                YamlConfiguration.loadConfiguration(questsFile)
            } else {
                YamlConfiguration()
            }

        config.set("players.$playerId.title", title)
        config.set("players.$playerId.objective", objective)
        config.save(questsFile)
    }

    override fun clearPlayerQuestDisplay(playerId: UUID) {
        val questsFile = File(dataFolder, "player-quests.yml")
        if (!questsFile.exists()) return

        val config = YamlConfiguration.loadConfiguration(questsFile)
        config.set("players.$playerId", null)
        config.save(questsFile)
    }
}
