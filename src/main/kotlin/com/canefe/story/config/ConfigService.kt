package com.canefe.story.config

import com.canefe.story.Story
import org.bukkit.configuration.file.FileConfiguration

class ConfigService(private val plugin: Story) {
    private val config: FileConfiguration get() = plugin.configFile

    // OpenAI API settings
    var openAIKey: String = ""
    var aiModel: String = ""

    // Format settings
    var chatFormat: String = ""
    var emoteFormat: String = ""

    // NPC context generation lists
    var traitList: List<String> = listOf()
    var quirkList: List<String> = listOf()
    var motivationList: List<String> = listOf()
    var flawList: List<String> = listOf()
    var toneList: List<String> = listOf()


    // Chat enabled
    var chatEnabled: Boolean = true
    var radiantEnabled: Boolean = true

    // Distance settings
    var radiantRadius: Double = 5.0
    var chatRadius: Double = 5.0
    var responseDelay: Double = 2.0

    // Default values
    var defaultContext: String = ""

    init {
        plugin.saveDefaultConfig()
        loadConfigValues()
    }

    fun reload() {
        plugin.reloadConfig()
        loadConfigValues()

        try{
            plugin.npcContextGenerator.loadConfig()
            plugin.lorebookManager.loadConfig()
            plugin.scheduleManager.reloadSchedules()
            plugin.npcDataManager.loadConfig()
        } catch (e: Exception) {
            // We can ignore this exception
        }
    }

    private fun loadConfigValues() {
        openAIKey = config.getString("openai.apikey", "") ?: ""
        aiModel = config.getString("openai.aiModel", "meta-llama/llama-3.1-70b-instruct") ?: "meta-llama/llama-3.1-70b-instruct"
        chatFormat = config.getString("ai.chatFormat", "\\n<%color%>%npc_name%</%color%> <gray><italic>:</italic></gray> <white>%message%</white>\\n") ?: "\\n<%color%>%npc_name%</%color%> <gray><italic>:</italic></gray> <white>%message%</white>\\n"
        emoteFormat = config.getString("ai.emoteFormat", "<gray><italic>\$1</italic></gray>") ?: "<gray><italic>\$1</italic></gray>"

        traitList = config.getStringList("ai.traits")
        quirkList = config.getStringList("ai.quirks")
        motivationList = config.getStringList("ai.motivations")
        flawList = config.getStringList("ai.flaws")
        toneList = config.getStringList("ai.tones")

        chatEnabled = config.getBoolean("ai.chatEnabled", true)
        radiantEnabled = config.getBoolean("ai.radiantEnabled", true)

        radiantRadius = config.getDouble("ai.radiantRadius", 5.0)
        chatRadius = config.getDouble("ai.chatRadius", 5.0)
        responseDelay = config.getDouble("ai.responseDelay", 2.0)

        defaultContext = config.getString("ai.defaultContext", "Default context") ?: "Default context"
    }

    fun save() {
        plugin.saveConfig()
    }
}