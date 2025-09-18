package com.canefe.story

import com.canefe.story.api.StoryAPI
import com.canefe.story.audio.AudioManager
import com.canefe.story.audio.VoiceManager
import com.canefe.story.character.skill.SkillManager
import com.canefe.story.command.base.CommandManager
import com.canefe.story.command.story.quest.QuestCommandUtils
import com.canefe.story.config.ConfigService
import com.canefe.story.config.PromptService
import com.canefe.story.context.ContextExtractor
import com.canefe.story.conversation.ConversationManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.radiant.RadiantConversationService
import com.canefe.story.dm.AIDungeonMaster
import com.canefe.story.event.EventManager
import com.canefe.story.faction.FactionManager
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.location.LocationManager
import com.canefe.story.lore.LoreBookManager
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.NPCScheduleManager
import com.canefe.story.npc.behavior.NPCBehaviorManager
import com.canefe.story.npc.data.NPCDataManager
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
import com.canefe.story.npc.name.NPCNameManager
import com.canefe.story.npc.name.NPCNameResolver
import com.canefe.story.npc.relationship.RelationshipManager
import com.canefe.story.npc.service.NPCActionIntentRecognizer
import com.canefe.story.npc.service.NPCMessageService
import com.canefe.story.npc.service.NPCResponseService
import com.canefe.story.npc.service.TypingSessionManager
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.player.NPCManager
import com.canefe.story.player.PlayerManager
import com.canefe.story.quest.QuestListener
import com.canefe.story.quest.QuestManager
import com.canefe.story.service.AIResponseService
import com.canefe.story.session.SessionManager
import com.canefe.story.task.TaskManager
import com.canefe.story.util.DisguiseManager
import com.canefe.story.util.PluginUtils
import com.canefe.story.util.TimeService
import com.canefe.story.webui.WebUIServer
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import dev.jorel.commandapi.CommandAPI
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import kr.toxicity.healthbar.api.placeholder.PlaceholderContainer
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.Collections.emptyList
import java.util.UUID
import java.util.concurrent.CompletableFuture

class Story :
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
        private set

    // gson
    val gson = com.google.gson.Gson()

    // Services and managers
    lateinit var audioManager: AudioManager

    lateinit var factionManager: FactionManager

    lateinit var disguiseManager: DisguiseManager

    lateinit var typingSessionManager: TypingSessionManager

    lateinit var contextExtractor: ContextExtractor

    lateinit var npcDataManager: NPCDataManager
        private set

    lateinit var npcBehaviorManager: NPCBehaviorManager
        private set

    lateinit var questManager: QuestManager

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

    lateinit var timeService: TimeService

    lateinit var npcResponseService: NPCResponseService
    lateinit var worldInformationManager: WorldInformationManager

    lateinit var npcActionIntentRecognizer: NPCActionIntentRecognizer

    lateinit var scheduleManager: NPCScheduleManager
    lateinit var npcContextGenerator: NPCContextGenerator
    lateinit var lorebookManager: LoreBookManager
    lateinit var sessionManager: SessionManager
    lateinit var taskManager: TaskManager

    private lateinit var commandManager: CommandManager

    private lateinit var eventManager: EventManager

    lateinit var aiResponseService: AIResponseService

    lateinit var relationshipManager: RelationshipManager

    lateinit var mythicMobConversation: MythicMobConversationIntegration

    lateinit var aiDungeonMaster: AIDungeonMaster

    lateinit var skillManager: SkillManager

    private var webUIServer: WebUIServer? = null

    lateinit var voiceManager: VoiceManager

    // NPC Name Aliasing System
    lateinit var npcNameManager: com.canefe.story.npc.name.NPCNameManager
        private set
    lateinit var npcNameResolver: com.canefe.story.npc.name.NPCNameResolver
        private set

    // lateinit var aiDungeonMaster: AIDungeonMaster

    lateinit var api: StoryAPI
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
        commandManager = CommandManager(this)
        commandManager.onLoad()
        configService.reload()
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

        // Start radiant conversation service
        radiantConversationService.startProximityTask()

        server.pluginManager.registerEvents(QuestListener(this), this)
        // initializeWebUIServer()
        // Load configuration
        configService.reload()
        PlaceholderContainer.STRING.addPlaceholder("disguise_name") { e ->
            val bukkitEntity = e.entity.entity()

            if (bukkitEntity !is Player) {
                return@addPlaceholder bukkitEntity.name
            }

            if (DisguiseAPI.isDisguised(bukkitEntity)) {
                var disguise = DisguiseAPI.getDisguise(bukkitEntity)

                if (disguise.isPlayerDisguise) {
                    disguise = disguise as PlayerDisguise
                } else {
                    val name = disguise.disguiseName
                    return@addPlaceholder name
                }

                disguise.name ?: bukkitEntity.name
            } else {
                bukkitEntity.name
            }
        }
        PacketEvents.getAPI().init()
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

        // Initialize the time service
        timeService = TimeService(this)

        sessionManager = SessionManager.getInstance(this)

        factionManager = FactionManager(this)
        disguiseManager = DisguiseManager(this)
        typingSessionManager = TypingSessionManager(this)
        contextExtractor = ContextExtractor(this)
        // Initialize the audio
        audioManager = AudioManager(this)
        // Initialize in order of dependencies
        npcContextGenerator = NPCContextGenerator(this)
        npcDataManager = NPCDataManager.getInstance(this)
        locationManager = LocationManager.getInstance(this)
        questManager = QuestManager.getInstance(this)
        npcUtils = NPCUtils.getInstance(this)
        npcManager = NPCManager.getInstance(this)
        scheduleManager = NPCScheduleManager.getInstance(this)
        playerManager = PlayerManager.getInstance(this)
        // Initialize services that depend on managers
        npcMessageService = NPCMessageService.getInstance(this)
        radiantConversationService = RadiantConversationService(this)
        npcResponseService = NPCResponseService(this)
        worldInformationManager = WorldInformationManager(this)

        npcActionIntentRecognizer = NPCActionIntentRecognizer(this)

        lorebookManager = LoreBookManager.getInstance(this)

        taskManager = TaskManager.getInstance(this)

        npcBehaviorManager = NPCBehaviorManager(this)

        conversationManager =
            ConversationManager.getInstance(
                this,
                npcContextGenerator,
                npcResponseService,
                worldInformationManager,
            )

        eventManager = EventManager.getInstance(this)
        eventManager.registerEvents()

        registerQuestBookListener()

        aiResponseService = AIResponseService(this)

        relationshipManager = RelationshipManager(this)

        mythicMobConversation = MythicMobConversationIntegration(this)

        aiDungeonMaster = AIDungeonMaster(this)
        // aiDungeonMaster.initialize()

        skillManager = SkillManager(this)

        voiceManager = VoiceManager(this)

        npcNameManager = NPCNameManager(this)
        npcNameResolver = NPCNameResolver(this)
    }

    private fun registerQuestBookListener() {
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onBookInteract(event: PlayerInteractEvent) {
                    if (event.action != Action.RIGHT_CLICK_AIR &&
                        event.action != Action.RIGHT_CLICK_BLOCK
                    ) {
                        return
                    }
                    if (event.item?.type != Material.WRITTEN_BOOK) return

                    val meta = event.item?.itemMeta as? BookMeta ?: return
                    val targetKey = NamespacedKey(this@Story, "quest_book_target")
                    val targetUuidString =
                        meta.persistentDataContainer.get(
                            targetKey,
                            PersistentDataType.STRING,
                        )
                            ?: return

                    try {
                        val targetUuid = UUID.fromString(targetUuidString)
                        val targetPlayer = Bukkit.getOfflinePlayer(targetUuid)

                        // Cancel the default book opening
                        event.isCancelled = true

                        // Open custom quest book interface
                        val commandUtils = QuestCommandUtils()
                        if (targetPlayer.isOnline) {
                            commandUtils.openJournalBook(event.player, targetPlayer.player)
                        } else {
                            // If target is offline but we have their UUID, we can still try to
                            // open their quest data
                            commandUtils.openJournalBook(event.player, targetPlayer)
                        }
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Invalid UUID in quest book: $targetUuidString")
                    }
                }
            },
            this,
        )
    }

    private fun initializeWebUIServer() {
        val port = 7777
        webUIServer = WebUIServer(this, port)
        logger.info("WebUI server started on port $port")
    }

    override fun onDisable() {
        logger.info("Story has been disabled.")
        PacketEvents.getAPI().terminate()
        // Cancel all tasks first to prevent new ones from being registered
        Bukkit.getScheduler().cancelTasks(this)
        webUIServer?.shutdown()
        // Then shut down each manager in reverse order of initialization
        try {
            // Conversation-related systems first
            conversationManager.cancelScheduledTasks()
            typingSessionManager.shutdown()

            // NPC-related systems
            scheduleManager.shutdown()

            // Data-related systems
            factionManager.shutdown()

            // Generic systems
            CommandAPI.onDisable()
            commandManager.onDisable()
            eventManager.unregisterAll()
            sessionManager.shutdown()

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

            // Unregister commands
            commandManager.onDisable()

            // Unregister events
            eventManager.unregisterAll()

            logger.info("Story plugin has been safely shut down.")
            null
        }
    }

    // TODO: This method should be moved to a dedicated NPC service
    fun getNearbyNPCs(
        player: Player,
        radius: Double,
    ): List<NPC> =
        CitizensAPI.getNPCRegistry().filter { npc ->
            npc.isSpawned &&
                npc.entity.location.world == player.location.world &&
                npc.entity.location.distanceSquared(player.location) <= radius * radius
        }

    // TODO: This method should be moved to a dedicated NPC service
    fun getNearbyNPCs(
        npc: NPC,
        radius: Double,
    ): List<NPC> {
        if (!npc.isSpawned) return emptyList()

        return CitizensAPI.getNPCRegistry().filter { otherNpc ->
            otherNpc.isSpawned &&
                otherNpc != npc &&
                otherNpc.entity.location.world == npc.entity.location.world &&
                otherNpc.entity.location.distanceSquared(npc.entity.location) <= radius * radius
        }
    }

    // TODO: This method should be moved to a dedicated Player service
    fun getNearbyPlayers(
        player: Player,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        val radiusSquared = radius * radius
        val playerLoc = player.location

        return Bukkit.getOnlinePlayers().filter { otherPlayer ->
            val loc = otherPlayer.location
            if (loc.world != playerLoc.world) return@filter false

            if (ignoreY) {
                val dx = loc.x - playerLoc.x
                val dz = loc.z - playerLoc.z
                (dx * dx + dz * dz) <= radiusSquared
            } else {
                loc.distanceSquared(playerLoc) <= radiusSquared
            }
        }
    }

    fun getNearbyPlayers(
        npc: NPC,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        if (!npc.isSpawned) return emptyList()

        val radiusSquared = radius * radius
        val npcLoc = npc.entity.location

        return Bukkit.getOnlinePlayers().filter { player ->
            val loc = player.location
            if (loc.world != npcLoc.world) return@filter false

            if (ignoreY) {
                val dx = loc.x - npcLoc.x
                val dz = loc.z - npcLoc.z
                (dx * dx + dz * dz) <= radiusSquared
            } else {
                loc.distanceSquared(npcLoc) <= radiusSquared
            }
        }
    }

    fun getNearbyPlayers(
        location: Location,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        val radiusSquared = radius * radius

        return Bukkit.getOnlinePlayers().filter { player ->
            val loc = player.location
            if (loc.world != location.world) return@filter false

            if (ignoreY) {
                val dx = loc.x - location.x
                val dz = loc.z - location.z
                (dx * dx + dz * dz) <= radiusSquared
            } else {
                loc.distanceSquared(location) <= radiusSquared
            }
        }
    }

    fun getAIResponse(
        prompts: List<ConversationMessage>,
        useStreaming: Boolean = false,
        streamHandler: ((String) -> Unit)? = null,
        lowCost: Boolean = false,
    ): CompletableFuture<String?> {
        if (useStreaming) {
            val future = CompletableFuture<String?>()
            // null check streamingHandler
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

    /**
     * Ask for permission to perform an action from players with the specified permission. This will
     * send a prompt with Accept/Refuse buttons.
     *
     * @param description Description of the action needing permission
     * @param permission Permission required to respond (defaults to story.task.respond)
     * @param onAccept Runnable to execute when the task is accepted
     * @param onRefuse Runnable to execute when the task is refused
     * @param timeoutSeconds Time in seconds before the request times out (-1 for no timeout)
     * @param limitToSender If true, only sends to the provided sender
     * @param sender Optional sender to limit the task to
     * @return The ID of the created task
     */
    fun askForPermission(
        description: String,
        permission: String = "story.task.respond",
        onAccept: Runnable,
        onRefuse: Runnable,
        timeoutSeconds: Int = 300,
        limitToSender: Boolean = false,
        sender: CommandSender? = null,
    ): Int =
        taskManager.createTask(
            description = description,
            permission = permission,
            onAccept = onAccept,
            onRefuse = onRefuse,
            timeoutSeconds = timeoutSeconds,
            limitToSender = limitToSender,
            sender = sender,
        )

    /** Simplified version that only requires description and callbacks. */
    fun askForPermission(
        description: String,
        onAccept: Runnable,
        onRefuse: Runnable,
    ): Int =
        askForPermission(
            description = description,
            permission = "story.task.respond",
            onAccept = onAccept,
            onRefuse = onRefuse,
        )

    /**
     * Creates a dialogue path selection task with three options for DMs to choose from. This is
     * used when dialogue path selection is enabled to allow DMs to select the best NPC response
     * from three AI-generated options.
     *
     * @param description Description of the dialogue selection context
     * @param option1 First dialogue option
     * @param option2 Second dialogue option
     * @param option3 Third dialogue option
     * @param onOption1 Runnable to execute when option 1 is selected
     * @param onOption2 Runnable to execute when option 2 is selected
     * @param onOption3 Runnable to execute when option 3 is selected
     * @param permission Permission required to respond (defaults to story.dm)
     * @param timeoutSeconds Time in seconds before auto-selecting option 1 (defaults to 120)
     * @return The ID of the created dialogue path task
     */
    fun askForDialoguePathSelection(
        description: String,
        option1: String,
        option2: String,
        option3: String,
        onOption1: Runnable,
        onOption2: Runnable,
        onOption3: Runnable,
        permission: String = "story.dm",
        timeoutSeconds: Int = 120,
    ): Int =
        taskManager.createDialoguePathTask(
            description = description,
            option1 = option1,
            option2 = option2,
            option3 = option3,
            onOption1 = onOption1,
            onOption2 = onOption2,
            onOption3 = onOption3,
            permission = permission,
            timeoutSeconds = timeoutSeconds,
        )
}
