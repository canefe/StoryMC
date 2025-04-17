package com.canefe.story

import ConversationManager
import com.canefe.story.config.ConfigService
import com.canefe.story.location.LocationManager
import com.canefe.story.npc.NPCScheduleManager
import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.radiant.RadiantConversationService
import com.canefe.story.event.EventManager
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.lore.LoreBookManager
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.data.NPCDataManager
import com.canefe.story.npc.service.NPCMessageService
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.player.NPCManager
import com.canefe.story.player.PlayerManager
import com.canefe.story.service.AIResponseService
import com.canefe.story.util.PluginUtils
import dev.jorel.commandapi.CommandAPI
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture

class Story : JavaPlugin(), Listener {
    // Singleton instance
    companion object {
        lateinit var instance: Story
            private set
    }

    // Plugin configuration
    private val configService = ConfigService(this)

    // Services and managers
    lateinit var npcDataManager: NPCDataManager
        private set
    lateinit var conversationManager: ConversationManager
        private set
    lateinit var locationManager: LocationManager
        private set
    lateinit var npcUtils: NPCUtils
        private set
    lateinit var npcManager: NPCManager
        private set
    lateinit var playerManager: PlayerManager
        private set
    lateinit var npcMessageService: NPCMessageService
        private set
    lateinit var radiantConversationService: RadiantConversationService
        private set
    lateinit var npcResponseService: NPCResponseService
    lateinit var worldInformationManager: WorldInformationManager

    private lateinit var scheduleManager: NPCScheduleManager
    lateinit var npcContextGenerator: NPCContextGenerator
    lateinit var lorebookManager: LoreBookManager

    private lateinit var commandManager: CommandManager

    private lateinit var eventManager: EventManager

    private lateinit var aiResponseService: AIResponseService

    // Configuration and state
    val miniMessage = MiniMessage.miniMessage()
    var itemsAdderEnabled = false
        private set

    // Config Reference
    val configFile get() = super.getConfig()
    val config get() = configService

    // Contexts
    val generalContexts = mutableListOf<String>()

    override fun onLoad() {
        commandManager = CommandManager(this)
        commandManager.onLoad()
        configService.reload()
    }

    override fun onEnable() {
        instance = this

        // Plugin startup logic
        logger.info("Story has been enabled!")

        // Register events
        server.pluginManager.registerEvents(this, this)

        // Check required plugins
        checkRequiredPlugins()

        // Load configuration
        configService.reload()

        // Initialize managers and services
        initializeManagers()

        // Register commands
        commandManager.registerCommands()

        // Start radiant conversation service
        radiantConversationService.startProximityTask()
    }

    private fun checkRequiredPlugins() {
        if (Bukkit.getPluginManager().getPlugin("Sentinel") == null) {
            logger.warning("Sentinel plugin not found! NPC commands will not work.")
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            logger.warning("Citizens plugin not found! NPC interactions will not work.")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            itemsAdderEnabled = true
            logger.info("ItemsAdder detected, avatar features enabled.")
        } else {
            logger.warning("ItemsAdder not found, avatar features will be disabled.")
        }

        if (PluginUtils.isPluginEnabled("PlaceholderAPI")) {
            StoryPlaceholderExpansion(this).register()
        }
    }

    private fun initializeManagers() {
        // Initialize in order of dependencies
        npcContextGenerator = NPCContextGenerator(this)
        npcDataManager = NPCDataManager.getInstance(this)
        locationManager = LocationManager.getInstance(this)
        npcUtils = NPCUtils.getInstance(this)
        npcManager = NPCManager.getInstance(this)
        scheduleManager = NPCScheduleManager.getInstance(this)
        playerManager = PlayerManager.getInstance(this)

        // Initialize services that depend on managers
        npcMessageService = NPCMessageService.getInstance(this)
        radiantConversationService = RadiantConversationService(this)
        npcResponseService = NPCResponseService(this)
        worldInformationManager = WorldInformationManager(this)

        lorebookManager = LoreBookManager.getInstance(this)

        conversationManager = ConversationManager.getInstance(
            this,
            npcContextGenerator,
            npcResponseService,
            worldInformationManager
        )

        eventManager = EventManager.getInstance(this)
        eventManager.registerEvents()

        aiResponseService = AIResponseService(this)


    }

    override fun onDisable() {
        logger.info("Story has been disabled.")
        CommandAPI.onDisable()
        scheduleManager.shutdown()
        commandManager.onDisable()
        eventManager.unregisterAll()
    }

    // Utility methods that would be better placed in appropriate services

    // This method should be moved to a dedicated NPC service
    fun getNearbyNPCs(player: Player, radius: Double): List<net.citizensnpcs.api.npc.NPC> {
        return net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
            .filter { npc ->
                npc.isSpawned &&
                        npc.entity.location.world == player.location.world &&
                        npc.entity.location.distance(player.location) <= radius
            }
    }

    // This method should be moved to a dedicated NPC service
    fun getNearbyNPCs(npc: net.citizensnpcs.api.npc.NPC, radius: Double): List<net.citizensnpcs.api.npc.NPC> {
        if (!npc.isSpawned) return emptyList()

        return net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
            .filter { otherNpc ->
                otherNpc.isSpawned &&
                        otherNpc != npc &&
                        otherNpc.entity.location.world == npc.entity.location.world &&
                        otherNpc.entity.location.distance(npc.entity.location) <= radius
            }
    }

    fun getNearbyPlayers(npc: net.citizensnpcs.api.npc.NPC, radius: Double): List<Player> {
        if (!npc.isSpawned) return emptyList()

        return Bukkit.getOnlinePlayers().filter { player ->
            player.location.world == npc.entity.location.world &&
                    player.location.distance(npc.entity.location) <= radius
        }
    }

    // AI response methods should be moved to an AI service
    fun getAIResponse(prompts: List<ConversationMessage>): String? {
        val response = aiResponseService.getAIResponseAsync(prompts).get()
        return response
    }

    // NPC data methods should be in NPCDataManager
    fun addSystemMessage(npcName: String, message: String) {
        // Implementation would go here
    }

    fun saveNPCRelationValue(npcName: String, target: String, value: String) {
        // Implementation would go here
    }
}