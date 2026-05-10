package com.canefe.story

import com.canefe.story.api.StoryAPI
import com.canefe.story.api.character.AppearanceTemplateCache
import com.canefe.story.api.character.CharacterRegistry
import com.canefe.story.api.squad.SquadRegistry
import com.canefe.story.storage.mongo.MongoSquadStorage
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
import com.canefe.story.npc.ChunkLoadReconciler
import com.canefe.story.npc.NPCFollowTracker
import com.canefe.story.npc.NPCManager
import com.canefe.story.npc.NearbyNPCBroadcaster
import com.canefe.story.npc.PositionBroadcaster
import com.canefe.story.npc.RecognitionBroadcaster
import com.canefe.story.npc.PuppetCommandListener
import com.canefe.story.npc.PuppetGroupBroadcaster
import com.canefe.story.npc.PuppetManager
import com.canefe.story.npc.ReconciliationService
import com.canefe.story.npc.squad.SquadListBroadcaster
import com.canefe.story.npc.squad.SquadOrderListener
import com.canefe.story.npc.squad.SquadOrderTracker
import com.canefe.story.npc.behavior.NPCBehaviorManager
import com.canefe.story.npc.registry.StoryNPCRegistry
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
import com.canefe.story.npc.mythicmobs.MythicMobNPCFactory
import com.canefe.story.npc.relationship.RelationshipManager
import com.canefe.story.npc.schedule.ScheduleManager
import com.canefe.story.npc.service.NPCMessageService
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.npc.service.TypingSessionManager
import com.canefe.story.player.PlayerManager
import com.canefe.story.quest.QuestListener
import com.canefe.story.quest.QuestManager
import com.canefe.story.service.AIResponseService
import com.canefe.story.session.SessionManager
import com.canefe.story.storage.StorageBackend
import com.canefe.story.storage.StorageFactory
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
    lateinit var appearanceTemplateCache: AppearanceTemplateCache
    val isAppearanceTemplateCacheReady: Boolean get() = ::appearanceTemplateCache.isInitialized

    // gson
    val gson = com.google.gson.Gson()

    // Services and managers
    lateinit var audioManager: AudioManager

    lateinit var disguiseManager: DisguiseManager

    lateinit var typingSessionManager: TypingSessionManager

    lateinit var contextExtractor: ContextExtractor

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

    lateinit var scheduleManager: ScheduleManager
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
    lateinit var api: StoryAPI
        private set

    // Central event bus for all Story events
    val eventBus = StoryEventBus()

    // Perception — observes world events and emits them for nearby characters
    lateinit var perceptionService: PerceptionService
        private set
    val isPerceptionServiceReady: Boolean get() = ::perceptionService.isInitialized
    private var perceptionListener: PerceptionListener? = null

    // Intelligence — abstraction for all LLM/thinking operations
    lateinit var intelligence: StoryIntelligence
        private set
    val isIntelligenceReady: Boolean get() = ::intelligence.isInitialized

    // WebSocket transport to Go orchestrator (null when bridge.enabled=false)
    var wsTransport: WebSocketTransport? = null
        private set

    // Domain events — emits intents to Go orchestrator instead of direct storage writes
    lateinit var domainEvents: DomainEventEmitter
        private set

    // Decision relay — bridges Go decision events to Fabric/plugin-message clients and back
    lateinit var decisionRelay: DecisionRelay
        private set

    /**
     * True while the Bevy sim is active and owning NPC simulation.
     * When true, the plugin suppresses its own autonomous NPC behaviours
     * (proximity percepts, NPC behavior ticks, etc.) to avoid double-driving.
     * Becomes false automatically 12 seconds after the last sim.status heartbeat.
     */
    @Volatile
    var simActive: Boolean = false
        private set

    /**
     * True while the operator has paused the sim via `/story sim pause`.
     * While paused, MC suppresses outbound perception emission (the sim is
     * frozen, so feeding it more stimuli would queue garbage and the
     * fading-text rendered on the client would imply a live world).
     * Cleared by `/story sim resume`.
     */
    @Volatile
    var simPaused: Boolean = false

    private var simWatchdogTaskId: Int = -1

    /** Called on each sim.status heartbeat — refreshes the watchdog. */
    internal fun resendAffordances() {
        val mongo = storageFactory.mongoClient
        if (mongo == null) {
            logger.warning("[Affordances] skipping resend — mongoClient is null")
            return
        }
        val storage = com.canefe.story.affordance.AffordanceStorage(mongo)
        val records = try { storage.findAll() } catch (e: Exception) {
            logger.warning("[Affordances] findAll failed: ${e.message}")
            return
        }
        if (records.isEmpty()) {
            logger.info("[Affordances] no registered affordances to reseed")
            return
        }
        logger.info("[Affordances] reseeding ${records.size} registered affordances to sim")
        for (record in records) {
            val typeDef = affordanceTypeRegistry.getById(record.affordanceTypeId)
            eventBus.emit(com.canefe.story.bridge.SpawnAffordanceEvent(
                affordanceId = record.id,
                affordanceTypeId = record.affordanceTypeId,
                name = record.name,
                x = record.x,
                y = record.y,
                z = record.z,
                world = record.world,
                capacity = typeDef?.capacity ?: 0,
            ))
        }
    }

    internal fun onSimHeartbeat() {
        simActive = true
        if (simWatchdogTaskId != -1) Bukkit.getScheduler().cancelTask(simWatchdogTaskId)
        // 12 s timeout — the sim heartbeats every 5 s, so two missed beats = inactive
        simWatchdogTaskId = Bukkit.getScheduler().runTaskLater(this, Runnable {
            simActive = false
            simWatchdogTaskId = -1
            logger.info("[Story] Sim went offline — resuming autonomous NPC simulation")
        }, 240L).taskId
    }

    // Character registry — central lookup for character identity
    lateinit var characterRegistry: CharacterRegistry

    /** True if [characterRegistry] has been initialized (MongoDB available). */
    val isCharacterRegistryReady: Boolean get() = ::characterRegistry.isInitialized

    /** Squad registry — in-memory cache backed by Mongo. */
    lateinit var squadRegistry: SquadRegistry

    val isSquadRegistryReady: Boolean get() = ::squadRegistry.isInitialized

    // StoryNPC registry — single source of truth for in-world NPCs (Citizens + MythicMobs)
    lateinit var npcRegistry: StoryNPCRegistry

    /** True if [npcRegistry] has been initialized. */
    val isNpcRegistryReady: Boolean get() = ::npcRegistry.isInitialized

    // Factory for spawning MythicMob-backed StoryNPCs
    lateinit var mythicMobNpcFactory: MythicMobNPCFactory

    /** Null-safe accessor — returns null if MythicMobs plugin is disabled and the factory was never initialized. */
    val mythicMobNpcFactoryOrNull: MythicMobNPCFactory?
        get() = if (::mythicMobNpcFactory.isInitialized) mythicMobNpcFactory else null

    // Pushes nearby-NPC info bundles to clients for the action wheel
    lateinit var nearbyNpcBroadcaster: NearbyNPCBroadcaster
    lateinit var positionBroadcaster: PositionBroadcaster
    lateinit var reconciliationService: ReconciliationService
    lateinit var frontendReadinessTracker: FrontendReadinessTracker
    lateinit var recognitionBroadcaster: RecognitionBroadcaster
    lateinit var perceptionBroadcaster: com.canefe.story.perception.PerceptionBroadcaster
    lateinit var gazeBroadcaster: com.canefe.story.perception.GazeBroadcaster

    val affordanceTypeRegistry = com.canefe.story.affordance.AffordanceTypeRegistry()
    val characterStatsCache = com.canefe.story.perception.CharacterStatsCache()

    // Per-NPC follow loop for entity targets (NPCs / players)
    lateinit var npcFollowTracker: NPCFollowTracker

    // Puppet mode: per-player NPC group, commands via right-click in StoryClient
    lateinit var puppetManager: PuppetManager
    lateinit var puppetGroupBroadcaster: PuppetGroupBroadcaster

    // Squad command system — per-squad order state + broadcast to commanders
    lateinit var squadOrderTracker: SquadOrderTracker
    lateinit var squadListBroadcaster: SquadListBroadcaster

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

        // Initialize storage (force SQLite in test mode)
        val forceSqlite = System.getProperty("mockbukkit") == "true"
        storageFactory =
            StorageFactory.create(
                dataFolder = dataFolder,
                logger = logger,
                backend =
                    if (forceSqlite) {
                        StorageBackend.SQLITE
                    } else {
                        StorageBackend.fromString(
                            configService.storageBackend,
                        )
                    },
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
            characterRegistry = CharacterRegistry(charStorage, frontendStorage, logger, mongoClient)

            val squadStorage = MongoSquadStorage(mongoClient, logger)
            squadRegistry = SquadRegistry(squadStorage, logger)
        }

        timeService = TimeService(this)
        sessionManager = SessionManager(this, storageFactory.sessionStorage)
        disguiseManager = DisguiseManager(this)
        typingSessionManager = TypingSessionManager(this)
        contextExtractor = ContextExtractor(this)
        audioManager = AudioManager(this)
        // Run character migration and load registry
        if (::characterRegistry.isInitialized) {
            characterRegistry.loadAll()
        }
        if (::squadRegistry.isInitialized) {
            squadRegistry.loadAll()
        }

        locationManager = LocationManager(this, storageFactory.locationStorage)
        questManager = QuestManager(this, storageFactory.questStorage)
        npcManager = NPCManager(this)
        Bukkit.getPluginManager().registerEvents(npcManager, this)
        npcRegistry = StoryNPCRegistry(this)
        Bukkit.getPluginManager().registerEvents(npcRegistry, this)
        // Citizens NPCs aren't fully registered until after all plugins enable —
        // load on next tick so the scan sees them.
        Bukkit.getScheduler().runTaskLater(this, Runnable { npcRegistry.loadExistingCitizens() }, 1L)

        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            mythicMobNpcFactory = MythicMobNPCFactory(this)
            // Rehydrate any tagged MythicMob StoryNPCs in loaded chunks once
            // MythicMobs has finished its own boot. Tick +1 is enough since
            // plugin enable order resolves before the first tick.
            Bukkit.getScheduler().runTaskLater(
                this,
                Runnable { mythicMobNpcFactory.rehydrateAllLoaded() },
                1L,
            )
            mythicMobNpcFactory.startPeriodicRehydrate()
        }

        nearbyNpcBroadcaster = NearbyNPCBroadcaster(this)
        nearbyNpcBroadcaster.start()

        positionBroadcaster = PositionBroadcaster(this)
        positionBroadcaster.start()

        reconciliationService = ReconciliationService(this)
        // start() is called from initializeEventBus() after transports are registered
        server.pluginManager.registerEvents(ChunkLoadReconciler(this), this)

        frontendReadinessTracker = FrontendReadinessTracker(this)
        frontendReadinessTracker.start()

        recognitionBroadcaster = RecognitionBroadcaster(this)

        perceptionBroadcaster = com.canefe.story.perception.PerceptionBroadcaster(this)
        perceptionBroadcaster.start()
        gazeBroadcaster = com.canefe.story.perception.GazeBroadcaster(this)
        gazeBroadcaster.start()
        server.pluginManager.registerEvents(com.canefe.story.perception.CombatPerceptionListener(this), this)

        npcFollowTracker = NPCFollowTracker(this)

        puppetGroupBroadcaster = PuppetGroupBroadcaster(this)
        puppetManager = PuppetManager(this)
        // Register the c2s puppet command packet listener
        PacketEvents.getAPI().eventManager.registerListener(
            PuppetCommandListener(this),
            PacketListenerPriority.NORMAL,
        )

        squadOrderTracker = SquadOrderTracker(this)
        squadListBroadcaster = SquadListBroadcaster(this)
        squadListBroadcaster.start()
        PacketEvents.getAPI().eventManager.registerListener(
            SquadOrderListener(this),
            PacketListenerPriority.NORMAL,
        )
        scheduleManager = ScheduleManager(this)
        playerManager = PlayerManager(this, storageFactory.playerStorage)
        npcMessageService = NPCMessageService(this)
        radiantConversationService = RadiantConversationService(this)
        npcResponseService = NPCResponseService(this)
        worldEventManager = WorldEventManager(this, storageFactory.worldEventStorage)
        rumorManager = RumorManager(this, storageFactory.rumorStorage)
        worldInformationManager = WorldInformationManager(this)
        lorebookManager = LoreBookManager(this, storageFactory.loreStorage)
        taskManager = TaskManager(this)
        npcBehaviorManager = NPCBehaviorManager(this)

        conversationManager =
            ConversationManager(
                this,
                npcResponseService,
                worldInformationManager,
            )

        aiResponseService = AIResponseService(this)
        relationshipManager = RelationshipManager(this, storageFactory.relationshipStorage)
        mythicMobConversation = MythicMobConversationIntegration(this)
        skillManager = SkillManager(this)
        voiceManager = VoiceManager(this)
        skillCheckService =
            com.canefe.story.conversation.skillcheck
                .SkillCheckService(this)

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

        // Initialize character registry if not yet initialized and MongoDB is available
        if (!::characterRegistry.isInitialized) {
            val mongoClient = storageFactory.mongoClient
            if (mongoClient != null) {
                characterRegistry =
                    CharacterRegistry(
                        MongoCharacterStorage(mongoClient, logger),
                        MongoFrontendConfigStorage(mongoClient, logger),
                        logger,
                        mongoClient,
                    )
                characterRegistry.loadAll()
            }
        }
        // Same for squad registry
        if (!::squadRegistry.isInitialized) {
            val mongoClient = storageFactory.mongoClient
            if (mongoClient != null) {
                squadRegistry = SquadRegistry(MongoSquadStorage(mongoClient, logger), logger)
                squadRegistry.loadAll()
            }
        }

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
            val transport = WebSocketTransport(plugin = this, serverUri = configService.bridgeUri)
            transport.connect()
            eventBus.registerTransport(transport)
            wsTransport = transport
        }

        // Register intent handlers
        eventBus.on<NPCSpeakIntent> { IntentExecutor.executeSpeakIntent(this, it) }
        eventBus.on<NPCMoveIntent> { IntentExecutor.executeMoveIntent(this, it) }
        eventBus.on<NPCEmoteIntent> { IntentExecutor.executeEmoteIntent(this, it) }
        eventBus.on<NPCSignalIntent> { IntentExecutor.executeSignalIntent(this, it) }
        eventBus.on<QuestAssignIntent> { IntentExecutor.executeQuestAssignIntent(this, it) }
        eventBus.on<QuestUpdateIntent> { IntentExecutor.executeQuestUpdateIntent(this, it) }
        eventBus.on<QuestCompleteIntent> { IntentExecutor.executeQuestCompleteIntent(this, it) }
        eventBus.on<CharacterUpdateIntent> { IntentExecutor.executeCharacterUpdateIntent(this, it) }
        eventBus.on<SimStatusEvent> { if (it.running) onSimHeartbeat() }
        eventBus.on<SimAffordanceRegistryEvent> {
            affordanceTypeRegistry.update(it.types)
            logger.info("[AffordanceRegistry] received ${it.types.size} affordance types from sim")
            resendAffordances()
        }
        eventBus.on<NpcSpawnIntent> { IntentExecutor.executeNpcSpawnIntent(this, it) }
        eventBus.on<NpcStateIntent> { IntentExecutor.executeNpcStateIntent(this, it) }
        eventBus.on<FrontendIntentEvent> { IntentExecutor.executeFrontendIntent(this, it) }
        reconciliationService.start()

        // Initialize query handler for MCP/orchestrator queries
        QueryHandler(this).initialize()

        // Initialize intelligence provider
        val local = LocalIntelligence(this)
        appearanceTemplateCache = AppearanceTemplateCache(logger)
        intelligence =
            if (configService.bridgeEnabled) {
                val bridge = BridgeIntelligence(this, local, eventBus)
                Bukkit.getScheduler().runTaskLater(this, Runnable { bridge.requestCapabilities() }, 40L)
                bridge
            } else {
                local
            }

        // Initialize domain event emitter (replaces storage layer)
        domainEvents = DomainEventEmitter(eventBus, logger, configService.bridgeEnabled)

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

        // Initialize decision relay
        decisionRelay = DecisionRelay(this)
        decisionRelay.register()

        val mode = if (configService.bridgeEnabled) "Bridge" else "Local"
        logger.info(
            "Event bus initialized — transports: Bukkit${if (configService.bridgeEnabled) ", WebSocket" else ""}" +
                ", intelligence: $mode",
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
            if (::decisionRelay.isInitialized) decisionRelay.unregister()
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
