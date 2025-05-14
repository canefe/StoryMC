package com.canefe.story.config

import com.canefe.story.Story
import org.bukkit.configuration.file.FileConfiguration

@Suppress("MagicNumber")
class ConfigService(
	private val plugin: Story,
) {
	private val config: FileConfiguration get() = plugin.configFile

	// OpenAI API settings
	var openAIKey: String = ""
	var aiModel: String = ""

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
	var chatRadius: Double = 5.0
	var responseDelay: Double = 2.0
	var mythicMobsEnabled: Boolean = true
	var streamMessages: Boolean = true

	/*
	NPC Behavior settings
	 */

	// Delay in seconds before NPCs start rotating their heads
	var headRotationDelay: Int = 2

	// Whether to enable random pathing
	var randomPathingEnabled: Boolean = true

	// Chance to path to a random location
	var randomPathingChance: Double = 0.8

	// Whether to enable NPC scheduled tasks
	var scheduleEnabled: Boolean = true
	var scheduleTaskPeriod: Int = 60 // In seconds
	var rangeBeforeTeleport: Double = 100.0 // Distance before teleporting

	// NPC Voice settings
	var maxVoiceFiles: Int = 6
	var soundNameSpace: String = "iamusic:npc"

	init {
		plugin.saveDefaultConfig()
		loadConfigValues()
	}

	fun reload() {
		plugin.reloadConfig()
		loadConfigValues()

		try {
			plugin.npcContextGenerator.loadConfig()
			plugin.lorebookManager.loadConfig()
			plugin.scheduleManager.reloadSchedules()
			plugin.npcDataManager.loadConfig()
			plugin.locationManager.loadAllLocations()
			plugin.questManager.loadConfig()
			plugin.npcMessageService.load()
		} catch (e: Exception) {
			plugin.logger.severe("Failed to reload configuration: ${e.message}")
		} finally {
			plugin.logger.info("Configuration reloaded successfully.")
		}
	}

	private fun loadConfigValues() {
		openAIKey = config.getString("openrouter.apikey", "") ?: ""
		aiModel =
			config.getString("openrouter.aiModel", "meta-llama/llama-3.1-70b-instruct") ?: "meta-llama/llama-3.1-70b-instruct"

		// Conversation Settings
		chatEnabled = config.getBoolean("conversation.chatEnabled", true)
		radiantEnabled = config.getBoolean("conversation.radiantEnabled", true)

		radiantRadius = config.getDouble("conversation.radiantRadius", 5.0)
		chatRadius = config.getDouble("conversation.chatRadius", 5.0)
		responseDelay = config.getDouble("conversation.responseDelay", 2.0)
		mythicMobsEnabled =
			config.getBoolean("conversation.mythicMobsEnabled", true) // MythicMobs integration enabled
		streamMessages = config.getBoolean("conversation.streamMessages", true) // Stream messages to players

		// NPC Behavior Settings
		headRotationDelay = config.getInt("npc.headRotationDelay", 2)
		randomPathingEnabled = config.getBoolean("npc.randomPathingEnabled", true)
		randomPathingChance = config.getDouble("npc.randomPathingChance", 0.8)
		scheduleEnabled = config.getBoolean("npc.scheduleEnabled", true)
		scheduleTaskPeriod = config.getInt("npc.scheduleTaskPeriod", 60)
		rangeBeforeTeleport = config.getDouble("npc.rangeBeforeTeleport", 100.0) // Distance before teleporting

		// NPC Voice settings
		maxVoiceFiles = config.getInt("npc.maxVoiceFiles", 6)
		soundNameSpace = config.getString("npc.soundNameSpace", "iamusic:npc") ?: "iamusic:npc"

		traitList = config.getStringList("context.traits")
		quirkList = config.getStringList("context.quirks")
		motivationList = config.getStringList("context.motivations")
		flawList = config.getStringList("context.flaws")
		toneList = config.getStringList("context.tones")
	}

	fun save() {
		// Update all in-memory values back to the config file
		config.set("openrouter.apikey", openAIKey)
		config.set("openrouter.aiModel", aiModel)

		// Conversation settings
		config.set("conversation.chatEnabled", chatEnabled)
		config.set("conversation.radiantEnabled", radiantEnabled)
		config.set("conversation.radiantRadius", radiantRadius)
		config.set("conversation.chatRadius", chatRadius)
		config.set("conversation.responseDelay", responseDelay)
		config.set("conversation.mythicMobsEnabled", mythicMobsEnabled)
		config.set("conversation.streamMessages", streamMessages)

		// NPC Behavior settings
		config.set("npc.headRotationDelay", headRotationDelay)
		config.set("npc.randomPathingEnabled", randomPathingEnabled)
		config.set("npc.randomPathingChance", randomPathingChance)
		config.set("npc.scheduleEnabled", scheduleEnabled)
		config.set("npc.scheduleTaskPeriod", scheduleTaskPeriod)
		config.set("npc.rangeBeforeTeleport", rangeBeforeTeleport)

		// NPC Voice settings
		config.set("npc.maxVoiceFiles", maxVoiceFiles)
		config.set("npc.soundNameSpace", soundNameSpace)

		// Context lists
		config.set("context.traits", traitList)
		config.set("context.quirks", quirkList)
		config.set("context.motivations", motivationList)
		config.set("context.flaws", flawList)
		config.set("context.tones", toneList)

		// Save the updated configuration to disk
		plugin.saveConfig()
	}
}
