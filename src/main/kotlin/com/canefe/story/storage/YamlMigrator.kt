@file:Suppress("DEPRECATION")

package com.canefe.story.storage

import com.canefe.story.storage.yaml.*
import java.io.File
import java.util.logging.Logger

class YamlMigrator(
    private val dataFolder: File,
    private val storageFactory: StorageFactory,
    private val logger: Logger,
) {
    data class MigrationResult(
        val npcs: Int = 0,
        val locations: Int = 0,
        val quests: Int = 0,
        val playerQuests: Int = 0,
        val sessions: Int = 0,
        val relationships: Int = 0,
        val loreBooks: Int = 0,
        val teams: Int = 0,
        val disabledPlayers: Int = 0,
        val playerQuestDisplays: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
    )

    fun migrate(): MigrationResult {
        if (storageFactory.activeBackend == StorageBackend.YAML) {
            return MigrationResult(
                errors = mutableListOf("Current storage backend is YAML. Cannot migrate YAML to YAML."),
            )
        }

        val result = MigrationResult()
        var npcs = 0
        var locations = 0
        var quests = 0
        var playerQuests = 0
        var sessions = 0
        var relationshipSources = 0
        var loreBooks = 0
        var teams = 0
        var disabledPlayers = 0
        var playerQuestDisplays = 0

        // Create YAML readers pointing at the correct folders
        val yamlNpc = YamlNpcStorage(File(dataFolder, "npcs"), logger)
        val yamlLocation = YamlLocationStorage(File(dataFolder, "locations"), logger)
        val yamlQuest = YamlQuestStorage(File(dataFolder, "quests"), File(dataFolder, "playerquests"), logger)
        val yamlSession = YamlSessionStorage(File(dataFolder, "sessions"), logger)
        val yamlRelationship = YamlRelationshipStorage(File(dataFolder, "relationships"), logger)
        val yamlLore = YamlLoreStorage(File(dataFolder, "lore"), logger)
        val yamlPlayer = YamlPlayerStorage(dataFolder, logger)

        // Target = current storage from factory
        val targetNpc = storageFactory.npcStorage
        val targetLocation = storageFactory.locationStorage
        val targetQuest = storageFactory.questStorage
        val targetSession = storageFactory.sessionStorage
        val targetRelationship = storageFactory.relationshipStorage
        val targetLore = storageFactory.loreStorage
        val targetPlayer = storageFactory.playerStorage

        // Migrate NPCs
        logger.info("[Migration] Migrating NPCs...")
        for (npcName in yamlNpc.getAllNpcNames()) {
            try {
                val npcData = yamlNpc.loadNpcData(npcName) ?: continue
                // Also load memories
                val memories = yamlNpc.loadNpcMemories(npcName)
                npcData.memory = memories
                targetNpc.saveNpcData(npcName, npcData)
                npcs++
            } catch (e: Exception) {
                result.errors.add("NPC '$npcName': ${e.message}")
                logger.warning("[Migration] Failed to migrate NPC '$npcName': ${e.message}")
            }
        }

        // Migrate Locations
        logger.info("[Migration] Migrating locations...")
        for ((name, doc) in yamlLocation.loadAllLocations()) {
            try {
                targetLocation.saveLocation(doc)
                locations++
            } catch (e: Exception) {
                result.errors.add("Location '$name': ${e.message}")
                logger.warning("[Migration] Failed to migrate location '$name': ${e.message}")
            }
        }

        // Migrate Quests
        logger.info("[Migration] Migrating quests...")
        for ((questId, quest) in yamlQuest.loadAllQuests()) {
            try {
                targetQuest.saveQuest(quest)
                quests++
            } catch (e: Exception) {
                result.errors.add("Quest '$questId': ${e.message}")
                logger.warning("[Migration] Failed to migrate quest '$questId': ${e.message}")
            }
        }

        // Migrate Player Quests
        logger.info("[Migration] Migrating player quests...")
        val playerQuestFolder = File(dataFolder, "playerquests")
        if (playerQuestFolder.exists()) {
            val playerFiles = playerQuestFolder.listFiles { _, name -> name.endsWith(".yml") }
            playerFiles?.forEach { file ->
                try {
                    val playerId = java.util.UUID.fromString(file.nameWithoutExtension)
                    val pQuests = yamlQuest.loadPlayerQuests(playerId)
                    for ((_, pq) in pQuests) {
                        targetQuest.savePlayerQuest(playerId, pq)
                        playerQuests++
                    }
                } catch (e: Exception) {
                    result.errors.add("Player quests '${file.name}': ${e.message}")
                    logger.warning("[Migration] Failed to migrate player quests '${file.name}': ${e.message}")
                }
            }
        }

        // Migrate Sessions
        logger.info("[Migration] Migrating sessions...")
        val sessionFolder = File(dataFolder, "sessions")
        if (sessionFolder.exists()) {
            val sessionFiles = sessionFolder.listFiles { _, name -> name.endsWith(".yml") }
            sessionFiles?.forEach { file ->
                try {
                    val sessionId = file.nameWithoutExtension
                    val doc = yamlSession.loadSession(sessionId) ?: return@forEach
                    targetSession.saveSession(sessionId, doc)
                    sessions++
                } catch (e: Exception) {
                    result.errors.add("Session '${file.name}': ${e.message}")
                    logger.warning("[Migration] Failed to migrate session '${file.name}': ${e.message}")
                }
            }
        }

        // Migrate Relationships
        logger.info("[Migration] Migrating relationships...")
        try {
            val allRelationships = yamlRelationship.loadAllRelationships()
            for ((sourceId, rels) in allRelationships) {
                targetRelationship.saveRelationship(sourceId, rels)
                relationshipSources++
            }
        } catch (e: Exception) {
            result.errors.add("Relationships: ${e.message}")
            logger.warning("[Migration] Failed to migrate relationships: ${e.message}")
        }

        // Migrate Lore Books
        logger.info("[Migration] Migrating lore books...")
        for ((_, loreBook) in yamlLore.loadAllLoreBooks()) {
            try {
                targetLore.saveLoreBook(loreBook)
                loreBooks++
            } catch (e: Exception) {
                result.errors.add("LoreBook '${loreBook.name}': ${e.message}")
                logger.warning("[Migration] Failed to migrate lore book '${loreBook.name}': ${e.message}")
            }
        }

        // Migrate Teams
        logger.info("[Migration] Migrating player data...")
        try {
            val yamlTeams = yamlPlayer.loadTeams()
            if (yamlTeams.isNotEmpty()) {
                targetPlayer.saveTeams(yamlTeams)
                teams = yamlTeams.size
            }
        } catch (e: Exception) {
            result.errors.add("Teams: ${e.message}")
            logger.warning("[Migration] Failed to migrate teams: ${e.message}")
        }

        // Migrate Disabled Players
        try {
            val disabled = yamlPlayer.loadDisabledPlayers()
            if (disabled.isNotEmpty()) {
                targetPlayer.saveDisabledPlayers(disabled)
                disabledPlayers = disabled.size
            }
        } catch (e: Exception) {
            result.errors.add("Disabled players: ${e.message}")
            logger.warning("[Migration] Failed to migrate disabled players: ${e.message}")
        }

        // Migrate Player Quest Displays
        try {
            val displays = yamlPlayer.loadPlayerQuestDisplay()
            for ((playerId, pair) in displays) {
                targetPlayer.savePlayerQuestDisplay(playerId, pair.first, pair.second)
                playerQuestDisplays++
            }
        } catch (e: Exception) {
            result.errors.add("Player quest displays: ${e.message}")
            logger.warning("[Migration] Failed to migrate player quest displays: ${e.message}")
        }

        logger.info("[Migration] Migration complete!")
        logger.info("[Migration] NPCs: $npcs, Locations: $locations, Quests: $quests, Player Quests: $playerQuests")
        logger.info("[Migration] Sessions: $sessions, Relationships: $relationshipSources, Lore: $loreBooks")
        logger.info(
            "[Migration] Teams: $teams, Disabled Players: $disabledPlayers, Quest Displays: $playerQuestDisplays",
        )

        if (result.errors.isNotEmpty()) {
            logger.warning("[Migration] ${result.errors.size} error(s) occurred during migration.")
        }

        return result.copy(
            npcs = npcs,
            locations = locations,
            quests = quests,
            playerQuests = playerQuests,
            sessions = sessions,
            relationships = relationshipSources,
            loreBooks = loreBooks,
            teams = teams,
            disabledPlayers = disabledPlayers,
            playerQuestDisplays = playerQuestDisplays,
        )
    }
}
