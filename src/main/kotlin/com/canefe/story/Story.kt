package com.canefe.story

import com.canefe.story.api.StoryAPI
import com.canefe.story.api.character.CharacterMigration
import com.canefe.story.api.character.CharacterRegistry
import com.canefe.story.audio.AudioManager
import com.canefe.story.audio.VoiceManager
import com.canefe.story.bridge.*
import com.canefe.story.character.skill.SkillManager
import com.canefe.story.command.base.CommandManager
import com.canefe.story.config.ConfigService
import com.canefe.story.config.PromptService
import com.canefe.story.context.ContextExtractor
import com.canefe.story.conversation.ConversationManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.radiant.RadiantConversationService
import com.canefe.story.event.EventManager
import com.canefe.story.information.RumorManager
import com.canefe.story.information.WorldEventManager
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.intelligence.BridgeIntelligence
import com.canefe.story.intelligence.LocalIntelligence
import com.canefe.story.intelligence.StoryIntelligence
import com.canefe.story.location.LocationManager
import com.canefe.story.lore.LoreBookManager
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.NPCManager
import com.canefe.story.npc.behavior.NPCBehaviorManager
import com.canefe.story.npc.data.NPCDataManager
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
import com.canefe.story.npc.name.NPCNameManager
import com.canefe.story.npc.name.NPCNameResolver
import com.canefe.story.npc.relationship.RelationshipManager
import com.canefe.story.npc.schedule.ScheduleManager
import com.canefe.story.npc.service.NPCActionIntentRecognizer
import com.canefe.story.npc.service.NPCMessageService
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.npc.service.TypingSessionManager
import com.canefe.story.player.PlayerManager
import com.canefe.story.quest.QuestListener
import com.canefe.story.quest.QuestManager
import com.canefe.story.service.AIResponseService
import com.canefe.story.session.SessionManager
import com.canefe.story.storage.BridgeStorage
import com.canefe.story.storage.LocalStorage
import com.canefe.story.storage.StorageBackend
import com.canefe.story.storage.StorageFactory
import com.canefe.story.storage.StoryStorage
import com.canefe.story.storage.mongo.MongoCharacterStorage
import com.canefe.story.storage.mongo.MongoFrontendConfigStorage
import com.canefe.story.task.TaskManager
import com.canefe.story.util.DisguiseManager
import com.canefe.story.util.PluginUtils
import com.canefe.story.util.TimeService
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIBukkitConfig
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.CompletableFuture

open class Story :
    JavaPlugin(),
    Listener {
    // Singleton instance
    companion object {
        lateinit var instance: Story
            private set

        /**
         * Gets the API instance for other plugins to use
         * @return The StoryAPI instance
         */
        @JvmStatic
        fun getAPI(): StoryAPI = instance.api
    }

    // Plugin configuration
    val configService = ConfigService(this)
    lateinit var promptService: PromptService

    // gson
    val gson = com.google.gson.Gson()

    // Services and managers
    lateinit var audioManager: AudioManager

    lateinit var disguiseManager: DisguiseManager

    lateinit var typingSessionManager: TypingSessionManager

    lateinit var contextExtractor: ContextExtractor

    lateinit var npcDataManager: NPCDataManager

    lateinit var npcBehaviorManager: NPCBehaviorManager
        private set

    lateinit var questManager: QuestManager

    lateinit var conversationManager: ConversationManager
    lateinit var locationManager: LocationManager
    lateinit var npcManager: NPCManager
        private set
    lateinit var playerManager: PlayerManager
        private set
    lateinit var npcMessageService: NPCMessageService
    lateinit var radiantConversationService: RadiantConversationService
        private set

    lateinit var timeService: TimeService

    lateinit var npcResponseService: NPCResponseService
    lateinit var worldInformationManager: WorldInformationManager
    lateinit var worldEventManager: WorldEventManager
    lateinit var rumorManager: RumorManager

    lateinit var npcActionIntentRecognizer: NPCActionIntentRecognizer

    lateinit var scheduleManager: ScheduleManager
    lateinit var npcContextGenerator: NPCContextGenerator
    lateinit var lorebookManager: LoreBookManager
    lateinit var sessionManager: SessionManager
    lateinit var taskManager: TaskManager

    lateinit var commandManager: CommandManager

    private lateinit var eventManager: EventManager

    lateinit var aiResponseService: AIResponseService

    lateinit var relationshipManager: RelationshipManager

    lateinit var mythicMobConversation: MythicMobConversationIntegration

    lateinit var skillManager: SkillManager

    lateinit var storageFactory: StorageFactory
        private set

    lateinit var voiceManager: VoiceManager

    lateinit var skillCheckService: com.canefe.story.conversation.skillcheck.SkillCheckService
        private set
    lateinit var npcSkillGenerator: com.canefe.story.character.skill.NPCSkillGenerator
        private set

    // NPC Name Aliasing System
    lateinit var npcNameManager: com.canefe.story.npc.name.NPCNameManager
        private set
    lateinit var npcNameResolver: com.canefe.story.npc.name.NPCNameResolver
        private set

    lateinit var api: StoryAPI
        private set

    // Central event bus for all Story events
    val eventBus = StoryEventBus()

    // Perception — observes world events and emits them for nearby characters
    lateinit var perceptionService: PerceptionService
        private set
    private var perceptionListener: PerceptionListener? = null

    // Intelligence — abstraction for all LLM/thinking operations
    lateinit var intelligence: StoryIntelligence
        private set

    // Storage — abstraction for persistent write operations
    lateinit var storage: StoryStorage
        private set

    // Character registry — central lookup for character identity
    lateinit var characterRegistry: CharacterRegistry
        private set

    // Configuration and state
    val miniMessage = MiniMessage.miniMessage()
    var itemsAdderEnabled = false
        private set

    // Config Reference
    val configFile
        get() = super.getConfig()
    val config
        get() = configService

    override fun onLoad() {
        CommandAPI.onLoad(CommandAPIBukkitConfig(this).silentLogs(true))
        commandManager = CommandManager(this)
        commandManager.onLoad()
        if (System.getProperty("mockbukkit") == "true") {
            return // skip CommandAPI init in tests
        }
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        // On Bukkit, calling this here is essential, hence the name "load"
        PacketEvents.getAPI().load()
        PacketEvents
            .getAPI()
            .getEventManager()
            .registerListener(PacketEventsPacketListener(), PacketListenerPriority.NORMAL)
    }

    override fun onEnable() {
        instance = this
        CommandAPI.onEnable()

        // Plugin startup logic
        logger.info("Story has been enabled!")

        // Register events
        server.pluginManager.registerEvents(this, this)

        // Check required plugins
        checkRequiredPlugins()

        // Initialize managers and services
        initializeManagers()

        // Register commands
        commandManager.registerCommands()
        if (System.getProperty("mockbukkit") != "true") {
            PacketEvents.getAPI().init()
        }

        // Start radiant conversation service
        radiantConversationService.startProximityTask()

        server.pluginManager.registerEvents(QuestListener(this), this)
        // Load configuration
        // reload() also initializes the event bus
        configService.reload()
        StoryAPI.initialize(this)
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
        // Initialize the prompt service early since other services depend on it
        promptService = PromptService(this)

        // Initialize storage
        storageFactory =
            StorageFactory.create(
                dataFolder = dataFolder,
                logger = logger,
                backend = StorageBackend.fromString(configService.storageBackend),
                mongoUri = configService.mongoUri,
                mongoDatabase = configService.mongoDatabase,
                mongoMaxPoolSize = configService.mongoMaxPoolSize,
                mongoConnectTimeoutMs = configService.mongoConnectTimeoutMs,
            )

        // Initialize character registry (requires MongoDB)
        val mongoClient = storageFactory.mongoClient
        if (mongoClient != null) {
            val charStorage = MongoCharacterStorage(mongoClient, logger)
            val frontendStorage = MongoFrontendConfigStorage(mongoClient, logger)
            characterRegistry = CharacterRegistry(charStorage, frontendStorage, logger)
        }

        timeService = TimeService(this)
        sessionManager = SessionManager(this, storageFactory.sessionStorage)
        disguiseManager = DisguiseManager(this)
        typingSessionManager = TypingSessionManager(this)
        contextExtractor = ContextExtractor(this)
        audioManager = AudioManager(this)
        npcContextGenerator = NPCContextGenerator(this)
        npcDataManager = NPCDataManager(this, storageFactory.npcStorage)

        // Run character migration and load registry (after npcDataManager is available)
        if (::characterRegistry.isInitialized) {
            val charStorage = MongoCharacterStorage(storageFactory.mongoClient!!, logger)
            val frontendStorage = MongoFrontendConfigStorage(storageFactory.mongoClient!!, logger)
            CharacterMigration.migrateIfNeeded(this, charStorage, frontendStorage, logger)
            characterRegistry.loadAll()
        }

        locationManager = LocationManager(this, storageFactory.locationStorage)
        questManager = QuestManager(this, storageFactory.questStorage)
        npcManager = NPCManager(this)
        Bukkit.getPluginManager().registerEvents(npcManager, this)
        scheduleManager = ScheduleManager(this)
        playerManager = PlayerManager(this, storageFactory.playerStorage)
        npcMessageService = NPCMessageService(this)
        radiantConversationService = RadiantConversationService(this)
        npcResponseService = NPCResponseService(this)
        worldEventManager = WorldEventManager(this, storageFactory.worldEventStorage)
        rumorManager = RumorManager(this, storageFactory.rumorStorage)
        worldInformationManager = WorldInformationManager(this)
        npcActionIntentRecognizer = NPCActionIntentRecognizer(this)
        lorebookManager = LoreBookManager(this, storageFactory.loreStorage)
        taskManager = TaskManager(this)
        npcBehaviorManager = NPCBehaviorManager(this)

        conversationManager =
            ConversationManager(
                this,
                npcContextGenerator,
                npcResponseService,
                worldInformationManager,
            )

        aiResponseService = AIResponseService(this)
        relationshipManager = RelationshipManager(this, storageFactory.relationshipStorage)
        mythicMobConversation = MythicMobConversationIntegration(this)
        skillManager = SkillManager(this)
        voiceManager = VoiceManager(this)
        npcNameManager = NPCNameManager(this)
        npcNameResolver = NPCNameResolver(this)

        skillCheckService =
            com.canefe.story.conversation.skillcheck
                .SkillCheckService(this)
        npcSkillGenerator =
            com.canefe.story.character.skill
                .NPCSkillGenerator(this)

        eventManager = EventManager(this)
        eventManager.registerEvents()
    }

    fun tryReconnectStorage(sender: CommandSender? = null) {
        if (!::storageFactory.isInitialized) return

        val desired = StorageBackend.fromString(configService.storageBackend)
        val current = storageFactory.activeBackend

        // Switch if backend changed, or reconnect if MongoDB connection was lost
        val needsSwitch =
            desired != current ||
                (desired == StorageBackend.MONGODB && !storageFactory.isMongoConnected)

        if (!needsSwitch) return

        sender?.sendMessage(miniMessage.deserialize("<yellow>Switching storage backend to $desired...</yellow>"))

        if (storageFactory.switchBackend(
                newBackend = desired,
                newMongoUri = configService.mongoUri,
                newMongoDatabase = configService.mongoDatabase,
                newMongoMaxPoolSize = configService.mongoMaxPoolSize,
                newMongoConnectTimeoutMs = configService.mongoConnectTimeoutMs,
            )
        ) {
            // Push new storage implementations to all managers
            npcDataManager.updateStorage(storageFactory.npcStorage)
            locationManager.updateStorage(storageFactory.locationStorage)
            questManager.updateStorage(storageFactory.questStorage)
            sessionManager.updateStorage(storageFactory.sessionStorage)
            relationshipManager.updateStorage(storageFactory.relationshipStorage)
            lorebookManager.updateStorage(storageFactory.loreStorage)
            playerManager.updateStorage(storageFactory.playerStorage)
            worldEventManager.updateStorage(storageFactory.worldEventStorage)
            rumorManager.updateStorage(storageFactory.rumorStorage)
            sender?.sendMessage(
                miniMessage.deserialize(
                    "<green>Storage backend switched to ${storageFactory.activeBackend}. All managers updated.</green>",
                ),
            )
        } else {
            sender?.sendMessage(
                miniMessage.deserialize(
                    "<red>Failed to switch to $desired. Keeping current backend (${storageFactory.activeBackend}).</red>",
                ),
            )
        }
    }

    fun initializeEventBus() {
        // Always register Bukkit transport
        eventBus.registerTransport(BukkitTransport(this))

        // Register WebSocket transport if enabled
        if (configService.bridgeEnabled) {
            val wsTransport = WebSocketTransport(plugin = this, serverUri = configService.bridgeUri)
            wsTransport.connect()
            eventBus.registerTransport(wsTransport)
        }

        // Register intent handlers
        eventBus.on<NPCSpeakIntent> { IntentExecutor.executeSpeakIntent(this, it) }
        eventBus.on<NPCMoveIntent> { IntentExecutor.executeMoveIntent(this, it) }
        eventBus.on<NPCEmoteIntent> { IntentExecutor.executeEmoteIntent(this, it) }

        // Initialize intelligence provider
        val local = LocalIntelligence(this)
        intelligence =
            if (configService.bridgeEnabled) {
                val bridge = BridgeIntelligence(this, local, eventBus)
                Bukkit.getScheduler().runTaskLater(this, Runnable { bridge.requestCapabilities() }, 40L)
                bridge
            } else {
                local
            }

        // Initialize storage provider
        val localStorage = LocalStorage(this)
        storage =
            if (configService.bridgeEnabled) {
                val bridgeStorage = BridgeStorage(this, localStorage, eventBus)
                Bukkit.getScheduler().runTaskLater(this, Runnable { bridgeStorage.requestCapabilities() }, 40L)
                bridgeStorage
            } else {
                localStorage
            }

        // Initialize perception (unregister old listener on reload)
        if (::perceptionService.isInitialized) {
            perceptionService.stopProximityPublisher()
        }
        perceptionListener?.let {
            org.bukkit.event.HandlerList
                .unregisterAll(it)
        }
        perceptionService = PerceptionService(this)
        perceptionListener = PerceptionListener(this, perceptionService)
        server.pluginManager.registerEvents(perceptionListener!!, this)
        perceptionService.startProximityPublisher()

        // Initialize character sync from sim
        CharacterSyncService(this).register()

        val mode = if (configService.bridgeEnabled) "Bridge" else "Local"
        logger.info(
            "Event bus initialized — transports: Bukkit${if (configService.bridgeEnabled) ", WebSocket" else ""}" +
                ", intelligence: $mode, storage: $mode",
        )
    }

    override fun onDisable() {
        logger.info("Story has been disabled.")
        if (System.getProperty("mockbukkit") != "true") {
            PacketEvents.getAPI().terminate()
        }
        // Cancel all tasks first to prevent new ones from being registered
        Bukkit.getScheduler().cancelTasks(this)
        // Then shut down each manager in reverse order of initialization
        try {
            if (::conversationManager.isInitialized) conversationManager.cancelScheduledTasks()
            if (::typingSessionManager.isInitialized) typingSessionManager.shutdown()
            if (::scheduleManager.isInitialized) scheduleManager.shutdown()

            CommandAPI.onDisable()
            commandManager.onDisable()
            if (::eventManager.isInitialized) eventManager.unregisterAll()
            if (::sessionManager.isInitialized) sessionManager.shutdown()
            if (::aiResponseService.isInitialized) aiResponseService.shutdown()
            if (::voiceManager.isInitialized) voiceManager.shutdown()
            if (::storageFactory.isInitialized) storageFactory.shutdown()
            eventBus.shutdown()

            logger.info("Story plugin has been successfully disabled.")
        } catch (e: Exception) {
            logger.severe("Error during plugin shutdown: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Safely stops the plugin by properly ending all ongoing conversations first and then shutting
     * down all services.
     */
    fun safeStop(): CompletableFuture<Void> {
        logger.info("Starting safe shutdown process...")

        val futures = ArrayList<CompletableFuture<Void>>()

        // Get all active conversations
        val activeConversations = conversationManager.getAllActiveConversations()

        if (activeConversations.isEmpty()) {
            logger.info("No active conversations to summarize.")
        } else {
            logger.info("Ending ${activeConversations.size} active conversations...")

            // End each conversation and collect futures
            for (conversation in activeConversations) {
                val future = conversationManager.endConversation(conversation)
                futures.add(future)
            }
        }

        // Create an all-completed future
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            logger.info("All conversations have been safely ended and summarized.")
            logger.info("Shutting down plugin services...")

            // Cancel all scheduled tasks
            conversationManager.cancelScheduledTasks()

            // Shutdown scheduled tasks
            scheduleManager.shutdown()

            sessionManager.shutdown()

            // Shutdown player agent manager

            // Shutdown AI response service (virtual thread executor)
            aiResponseService.shutdown()

            // Shutdown voice manager (includes ElevenLabsAudioManager virtual thread executor)
            voiceManager.shutdown()

            // Unregister commands
            commandManager.onDisable()

            // Unregister events
            eventManager.unregisterAll()

            logger.info("Story plugin has been safely shut down.")
            null
        }
    }

    @Deprecated(
        message = "Use aiResponseService.getAIResponseAsync() directly",
        replaceWith = ReplaceWith("aiResponseService.getAIResponseAsync(prompts, lowCost = lowCost)"),
    )
    fun getAIResponse(
        prompts: List<ConversationMessage>,
        useStreaming: Boolean = false,
        streamHandler: ((String) -> Unit)? = null,
        lowCost: Boolean = false,
    ): CompletableFuture<String?> {
        if (useStreaming) {
            val future = CompletableFuture<String?>()
            if (streamHandler == null) {
                future.completeExceptionally(
                    IllegalArgumentException(
                        "streamingHandler cannot be null when useStreaming is true",
                    ),
                )
                return future
            }
            future.complete(
                aiResponseService.getAIResponseStreaming(prompts, streamHandler, lowCost),
            )
        }

        return aiResponseService.getAIResponseAsync(prompts, lowCost = lowCost)
    }
}
