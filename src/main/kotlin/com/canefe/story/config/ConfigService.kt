package com.canefe.story.config

import com.canefe.story.Story
import org.bukkit.configuration.file.FileConfiguration

@Suppress("MagicNumber")
class ConfigService(
    private val plugin: Story,
) {
    private val config: FileConfiguration
        get() = plugin.configFile

    // OpenAI API settings
    var openAIUrl: String = ""
    var openAIKey: String = ""

    // Model to use important for AI context generation
    var aiModel: String = ""
    var maxTokens: Int = 500 // Default max tokens for AI responses

    // Model to use for conversations
    var aiConversationModel: String = "meta-llama/llama-3.3-70b-instruct"

    // NPC context generation settings
    var defaultLocationName: String = "Outlands"
    var traitList: List<String> = listOf()
    var quirkList: List<String> = listOf()
    var motivationList: List<String> = listOf()
    var flawList: List<String> = listOf()
    var toneList: List<String> = listOf()

    // Conversation settings
    var chatEnabled: Boolean = true
    var radiantEnabled: Boolean = true
    var radiantRadius: Double = 5.0
    var radiantCooldown: Int = 30 // 30 seconds per NPC
    var chatRadius: Double = 5.0
    var responseDelay: Double = 2.0
    var mythicMobsEnabled: Boolean = true
    var streamMessages: Boolean = true
    var behavioralDirectivesEnabled: Boolean =
        true // Whether to generate behavioral directives for NPCs
    var dialoguePathSelectionEnabled: Boolean =
        false // Whether to enable dialogue path selection for DMs
    var delayedPlayerMessageProcessing: Boolean =
        false // Whether to delay player message processing like /g command

    /*
    NPC Behavior settings
     */

    // Delay in seconds before NPCs start rotating their heads
    var headRotationDelay: Int = 2

    // Whether to enable random pathing
    var randomPathingEnabled: Boolean = true

    // Chance to path to a random location
    var randomPathingChance: Double = 0.8
    var randomLocationOffset: Double = 3.0

    // Whether to enable NPC scheduled tasks
    var scheduleEnabled: Boolean = true

    // How often to check for scheduled tasks (in seconds)
    var scheduleTaskPeriod: Int = 60

    // If NPC is already in pathing range, don't move for schedules
    var scheduleDestinationTolerance = 1.0

    var teleportOnFail: Boolean = true

    // If no players are nearby within set range, NPCs will teleport to their location
    var rangeBeforeTeleport: Double = 100.0

    // Maximum number of NPCs to process per tick for random pathing
    var maxProcessPerTick: Int = 3

    // Cooldown period in seconds before an NPC can be selected for random pathing again
    var randomPathingCooldown: Int = 300

    // NPC Voice settings
    var maxVoiceFiles: Int = 6
    var soundNameSpace: String = "iamusic:npc"

    // Misc
    var maxBookCharactersPerPage: Int = 180
    var maxLineWidth: Int = 50 // Maximum characters per line for NPC messages
    var broadcastSessionEntries: Boolean = true // Whether to broadcast session entries to players
    var debugMessages: Boolean = false // Enable debug messages for development
    var messagePrefix: String = "<dark_gray>[<gold>Story</gold>]</dark_gray> "

    // Skills
    var skillProvider: String = "MMOCore" // Default skill provider

    // Faction settings
    var dailyEventsEnabled: Boolean = true
    var dailyEventsChance: Double = 0.15
    var followedSettlements: List<String> = listOf()

    var voiceGenerationEnabled: Boolean = true
    var playerVoiceGenerationEnabled: Boolean = true
    var elevenLabsApiKey: String = ""
    var elevenLabsVoices: Map<String, String> = mapOf()
    var scheduleVoiceGenerationEnabled: Boolean = true

    // Cooldown period in seconds before an NPC can say schedule dialogue again
    var scheduleDialogueCooldown: Int = 60 // 1 minutes default

    init {
        plugin.saveDefaultConfig()
        loadConfigValues()
    }

    fun reload() {
        plugin.reloadConfig()
        loadConfigValues()

        try {
            plugin.promptService.reload()
            plugin.npcContextGenerator.loadConfig()
            plugin.lorebookManager.loadConfig()
            plugin.scheduleManager.reloadSchedules()
            plugin.npcDataManager.loadConfig()
            plugin.locationManager.loadAllLocations()
            plugin.questManager.loadConfig()
            plugin.npcMessageService.load()
            plugin.factionManager.load()
            plugin.playerManager.load()
            plugin.npcManager.loadConfig()
            plugin.relationshipManager.load()
            plugin.sessionManager.load()
            plugin.voiceManager.load()
            plugin.npcNameManager.reloadNameBanks()
        } catch (e: Exception) {
            plugin.logger.severe("Failed to reload configuration: ${e.message}")
        } finally {
            plugin.logger.info("Configuration reloaded successfully.")
        }
    }

    private fun loadConfigValues() {
        openAIKey = config.getString("openrouter.apikey", "") ?: ""
        openAIUrl = config.getString("openrouter.apiUrl", "") ?: ""
        aiModel =
            config.getString("openrouter.aiModel", "meta-llama/llama-3.3-70b-instruct")
                ?: "meta-llama/llama-3.3-70b-instruct"

        maxTokens =
            config.getInt("openrouter.maxTokens", 500) // Default max tokens for AI responses

        aiConversationModel =
            config.getString("openrouter.aiConversationModel")
                ?: "meta-llama/llama-3.3-70b-instruct"

        // Conversation Settings
        chatEnabled = config.getBoolean("conversation.chatEnabled", true)
        radiantEnabled = config.getBoolean("conversation.radiantEnabled", true)

        radiantRadius = config.getDouble("conversation.radiantRadius", 5.0)
        radiantCooldown = config.getInt("conversation.radiantCooldown", 30) // 30 seconds per NPC
        chatRadius = config.getDouble("conversation.chatRadius", 5.0)
        responseDelay = config.getDouble("conversation.responseDelay", 2.0)
        mythicMobsEnabled =
            config.getBoolean(
                "conversation.mythicMobsEnabled",
                true,
            ) // MythicMobs integration enabled
        streamMessages =
            config.getBoolean("conversation.streamMessages", true) // Stream messages to players
        behavioralDirectivesEnabled =
            config.getBoolean(
                "conversation.behavioralDirectivesEnabled",
                true,
            ) // Whether to generate behavioral directives for NPCs
        dialoguePathSelectionEnabled =
            config.getBoolean(
                "conversation.dialoguePathSelectionEnabled",
                false,
            ) // Whether to enable dialogue path selection for DMs
        delayedPlayerMessageProcessing =
            config.getBoolean(
                "conversation.delayedPlayerMessageProcessing",
                false,
            ) // Whether to delay player message processing like /g command

        // NPC Behavior Settings
        headRotationDelay = config.getInt("npc.headRotationDelay", 2)
        randomPathingEnabled = config.getBoolean("npc.randomPathingEnabled", true)
        randomPathingChance = config.getDouble("npc.randomPathingChance", 0.8)
        randomLocationOffset = config.getDouble("npc.randomLocationOffset", 3.0)
        scheduleEnabled = config.getBoolean("npc.scheduleEnabled", true)
        scheduleTaskPeriod = config.getInt("npc.scheduleTaskPeriod", 60)
        scheduleDestinationTolerance = config.getDouble("npc.scheduleDestinationTolerance", 1.0)
        rangeBeforeTeleport = config.getDouble("npc.rangeBeforeTeleport", 100.0)

        maxProcessPerTick = config.getInt("npc.maxProcessPerTick", 3)
        randomPathingCooldown = config.getInt("npc.randomPathingCooldown", 300)

        teleportOnFail = config.getBoolean("npc.teleportOnFail", true)

        // NPC Voice settings
        maxVoiceFiles = config.getInt("npc.maxVoiceFiles", 6)
        soundNameSpace = config.getString("npc.soundNameSpace", "iamusic:npc") ?: "iamusic:npc"

        // Miscellaneous settings
        maxBookCharactersPerPage = config.getInt("misc.maxBookCharactersPerPage", 180)
        maxLineWidth = config.getInt("misc.maxLineWidth", 50) // New config for maximum line width
        broadcastSessionEntries = config.getBoolean("misc.broadcastSessionEntries", true)
        debugMessages = config.getBoolean("misc.debugMessages", false)
        messagePrefix =
            config.getString(
                "misc.messagePrefix",
                "<dark_gray>[<gold>Story</gold>]</dark_gray>",
            )
                ?: "<dark_gray>[<gold>Story</gold>]</dark_gray>"

        // Skills
        skillProvider = config.getString("misc.skillProvider", "MMOCore") ?: "MMOCore"

        voiceGenerationEnabled = config.getBoolean("misc.voiceGenerationEnabled", true)
        playerVoiceGenerationEnabled =
            config.getBoolean(
                "misc.playerVoiceGenerationEnabled",
                true,
            ) // New config for player voices

        elevenLabsApiKey = config.getString("misc.elevenLabsApiKey", "") ?: ""
        elevenLabsVoices =
            config
                .getConfigurationSection("misc.elevenLabsVoices")
                ?.getKeys(false)
                ?.associateWith { key ->
                    config.getString("misc.elevenLabsVoices.$key") ?: ""
                }
                ?: emptyMap()

        scheduleVoiceGenerationEnabled =
            config.getBoolean("misc.scheduleVoiceGenerationEnabled", true)
        scheduleDialogueCooldown =
            config.getInt("misc.scheduleDialogueCooldown", 60) // 1 minutes default

        // NPC Context Generation settings
        defaultLocationName =
            config.getString("context.defaultLocationName", "Outlands") ?: "Outlands"
        // Load lists from config, defaulting to empty lists if not found
        traitList = config.getStringList("context.traits")
        quirkList = config.getStringList("context.quirks")
        motivationList = config.getStringList("context.motivations")
        flawList = config.getStringList("context.flaws")
        toneList = config.getStringList("context.tones")

        // Faction settings
        dailyEventsEnabled = config.getBoolean("faction.dailyEventsEnabled", true)
        dailyEventsChance = config.getDouble("faction.dailyEventsChance", 0.15)
        followedSettlements =
            config.getStringList("faction.followedSettlements").map { it.toString() }.toList()
    }

    fun save() {
        // Update all in-memory values back to the config file
        config.set("openrouter.apikey", openAIKey)
        config.set("openrouter.apiUrl", openAIUrl)
        config.set("openrouter.aiModel", aiModel)
        config.set("openrouter.aiConversationModel", aiConversationModel)
        config.set("openrouter.maxTokens", maxTokens)

        // Conversation settings
        config.set("conversation.chatEnabled", chatEnabled)
        config.set("conversation.radiantEnabled", radiantEnabled)
        config.set("conversation.radiantRadius", radiantRadius)
        config.set("conversation.radiantCooldown", radiantCooldown)
        config.set("conversation.chatRadius", chatRadius)
        config.set("conversation.responseDelay", responseDelay)
        config.set("conversation.mythicMobsEnabled", mythicMobsEnabled)
        config.set("conversation.streamMessages", streamMessages)
        config.set("conversation.behavioralDirectivesEnabled", behavioralDirectivesEnabled)
        config.set("conversation.dialoguePathSelectionEnabled", dialoguePathSelectionEnabled)
        config.set("conversation.delayedPlayerMessageProcessing", delayedPlayerMessageProcessing)

        // NPC Behavior settings
        config.set("npc.headRotationDelay", headRotationDelay)
        config.set("npc.randomPathingEnabled", randomPathingEnabled)
        config.set("npc.randomPathingChance", randomPathingChance)
        config.set("npc.randomLocationOffset", randomLocationOffset)
        config.set("npc.scheduleEnabled", scheduleEnabled)
        config.set("npc.scheduleTaskPeriod", scheduleTaskPeriod)
        config.set("npc.scheduleDestinationTolerance", scheduleDestinationTolerance)
        config.set("npc.rangeBeforeTeleport", rangeBeforeTeleport)
        config.set("npc.maxProcessPerTick", maxProcessPerTick)
        config.set("npc.randomPathingCooldown", randomPathingCooldown)
        config.set("npc.teleportOnFail", teleportOnFail)

        // Context
        config.set("context.defaultLocationName", defaultLocationName)
        config.set("context.traits", traitList)
        config.set("context.quirks", quirkList)
        config.set("context.motivations", motivationList)
        config.set("context.flaws", flawList)
        config.set("context.tones", toneList)

        // NPC Voice settings
        config.set("npc.maxVoiceFiles", maxVoiceFiles)
        config.set("npc.soundNameSpace", soundNameSpace)

        // Miscellaneous settings
        config.set("misc.maxBookCharactersPerPage", maxBookCharactersPerPage)
        config.set("misc.maxLineWidth", maxLineWidth) // Save maxLineWidth config
        config.set("misc.broadcastSessionEntries", broadcastSessionEntries)
        config.set("misc.debugMessages", debugMessages)
        config.set("misc.messagePrefix", messagePrefix)
        config.set("misc.skillProvider", skillProvider)

        config.set("misc.voiceGenerationEnabled", voiceGenerationEnabled)
        config.set("misc.playerVoiceGenerationEnabled", playerVoiceGenerationEnabled)
        config.set("misc.elevenLabsApiKey", elevenLabsApiKey)
        config.set("misc.scheduleDialogueCooldown", scheduleDialogueCooldown)

        // Save elevenLabsVoices map
        config.set("misc.elevenLabsVoices", null) // Clear existing
        for ((key, value) in elevenLabsVoices) {
            config.set("misc.elevenLabsVoices.$key", value)
        }

        config.set("misc.scheduleVoiceGenerationEnabled", scheduleVoiceGenerationEnabled)

        plugin.saveConfig()
    }
}
