package com.canefe.story.config

import com.canefe.story.Story
import org.bukkit.configuration.file.FileConfiguration

@Suppress("MagicNumber")
class ConfigService(private val plugin: Story) {
	private val config: FileConfiguration get() = plugin.configFile

	// OpenAI API settings
	var openAIKey: String = ""

	// Model to use important for AI context generation
	var aiModel: String = ""
	var maxTokens: Int = 500 // Default max tokens for AI responses

	// Model to use for conversations
	var aiConversationModel: String = "meta-llama/llama-3.3-70b-instruct"

	// NPC context generation lists
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
	var behavioralDirectivesEnabled: Boolean = true // Whether to generate behavioral directives for NPCs

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

	// NPC Voice settings
	var maxVoiceFiles: Int = 6
	var soundNameSpace: String = "iamusic:npc"

	// Misc
	var maxBookCharactersPerPage: Int = 180
	var broadcastSessionEntries: Boolean = true // Whether to broadcast session entries to players
	var debugMessages: Boolean = false // Enable debug messages for development

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
		} catch (e: Exception) {
			plugin.logger.severe("Failed to reload configuration: ${e.message}")
		} finally {
			plugin.logger.info("Configuration reloaded successfully.")
		}
	}

	private fun loadConfigValues() {
		openAIKey = config.getString("openrouter.apikey", "") ?: ""
		aiModel =
			config.getString("openrouter.aiModel", "meta-llama/llama-3.3-70b-instruct") ?: "meta-llama/llama-3.3-70b-instruct"

		maxTokens = config.getInt("openrouter.maxTokens", 500) // Default max tokens for AI responses

		aiConversationModel = config.getString("openrouter.aiConversationModel") ?: "meta-llama/llama-3.3-70b-instruct"

		// Conversation Settings
		chatEnabled = config.getBoolean("conversation.chatEnabled", true)
		radiantEnabled = config.getBoolean("conversation.radiantEnabled", true)

		radiantRadius = config.getDouble("conversation.radiantRadius", 5.0)
		radiantCooldown = config.getInt("conversation.radiantCooldown", 30) // 30 seconds per NPC
		chatRadius = config.getDouble("conversation.chatRadius", 5.0)
		responseDelay = config.getDouble("conversation.responseDelay", 2.0)
		mythicMobsEnabled =
			config.getBoolean("conversation.mythicMobsEnabled", true) // MythicMobs integration enabled
		streamMessages = config.getBoolean("conversation.streamMessages", true) // Stream messages to players
		behavioralDirectivesEnabled = config.getBoolean("conversation.behavioralDirectivesEnabled", true) // Whether to generate behavioral directives for NPCs

		// NPC Behavior Settings
		headRotationDelay = config.getInt("npc.headRotationDelay", 2)
		randomPathingEnabled = config.getBoolean("npc.randomPathingEnabled", true)
		randomPathingChance = config.getDouble("npc.randomPathingChance", 0.8)
		randomLocationOffset = config.getDouble("npc.randomLocationOffset", 3.0)
		scheduleEnabled = config.getBoolean("npc.scheduleEnabled", true)
		scheduleTaskPeriod = config.getInt("npc.scheduleTaskPeriod", 60)
		scheduleDestinationTolerance = config.getDouble("npc.scheduleDestinationTolerance", 1.0)
		rangeBeforeTeleport = config.getDouble("npc.rangeBeforeTeleport", 100.0)

		teleportOnFail = config.getBoolean("npc.teleportOnFail", true)

		// NPC Voice settings
		maxVoiceFiles = config.getInt("npc.maxVoiceFiles", 6)
		soundNameSpace = config.getString("npc.soundNameSpace", "iamusic:npc") ?: "iamusic:npc"

		// Miscellaneous settings
		maxBookCharactersPerPage = config.getInt("misc.maxBookCharactersPerPage", 180)
		broadcastSessionEntries = config.getBoolean("misc.broadcastSessionEntries", true)
		debugMessages = config.getBoolean("misc.debugMessages", false)

		// Skills
		skillProvider = config.getString("misc.skillProvider", "MMOCore") ?: "MMOCore"

		voiceGenerationEnabled = config.getBoolean("misc.voiceGenerationEnabled", true)
		playerVoiceGenerationEnabled = config.getBoolean("misc.playerVoiceGenerationEnabled", true) // New config for player voices

		elevenLabsApiKey = config.getString("misc.elevenLabsApiKey", "") ?: ""
		elevenLabsVoices = config.getConfigurationSection("misc.elevenLabsVoices")
			?.getKeys(false)
			?.associateWith { key ->
				config.getString("misc.elevenLabsVoices.$key") ?: ""
			}
			?: emptyMap()

		scheduleVoiceGenerationEnabled = config.getBoolean("misc.scheduleVoiceGenerationEnabled", true)

		traitList = config.getStringList("context.traits")
		quirkList = config.getStringList("context.quirks")
		motivationList = config.getStringList("context.motivations")
		flawList = config.getStringList("context.flaws")
		toneList = config.getStringList("context.tones")

		// Faction settings
		dailyEventsEnabled = config.getBoolean("faction.dailyEventsEnabled", true)
		dailyEventsChance = config.getDouble("faction.dailyEventsChance", 0.15)
		followedSettlements =
			config
				.getStringList("faction.followedSettlements")
				.map { it.toString() }
				.toList()
	}

	fun save() {
		// Update all in-memory values back to the config file
		config.set("openrouter.apikey", openAIKey)
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

		// NPC Behavior settings
		config.set("npc.headRotationDelay", headRotationDelay)
		config.set("npc.randomPathingEnabled", randomPathingEnabled)
		config.set("npc.randomPathingChance", randomPathingChance)
		config.set("npc.randomLocationOffset", randomLocationOffset)
		config.set("npc.scheduleEnabled", scheduleEnabled)
		config.set("npc.scheduleTaskPeriod", scheduleTaskPeriod)
		config.set("npc.scheduleDestinationTolerance", scheduleDestinationTolerance)
		config.set("npc.rangeBeforeTeleport", rangeBeforeTeleport)
		config.set("npc.teleportOnFail", teleportOnFail)

		// NPC Voice settings
		config.set("npc.maxVoiceFiles", maxVoiceFiles)
		config.set("npc.soundNameSpace", soundNameSpace)

		// Miscellaneous settings
		config.set("misc.maxBookCharactersPerPage", maxBookCharactersPerPage)
		config.set("misc.broadcastSessionEntries", broadcastSessionEntries)
		config.set("misc.debugMessages", debugMessages)
		config.set("misc.skillProvider", skillProvider)

		config.set("misc.voiceGenerationEnabled", voiceGenerationEnabled)
		config.set("misc.playerVoiceGenerationEnabled", playerVoiceGenerationEnabled)
		config.set("misc.elevenLabsApiKey", elevenLabsApiKey)

		// Save elevenLabsVoices map
		config.set("misc.elevenLabsVoices", null) // Clear existing
		for ((key, value) in elevenLabsVoices) {
			config.set("misc.elevenLabsVoices.$key", value)
		}

		config.set("misc.scheduleVoiceGenerationEnabled", scheduleVoiceGenerationEnabled)

		plugin.saveConfig()
	}
}
