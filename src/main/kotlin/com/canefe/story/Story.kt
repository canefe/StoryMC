package com.canefe.story

import ConversationManager
import com.canefe.story.audio.AudioManager
import com.canefe.story.command.base.CommandManager
import com.canefe.story.config.ConfigService
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.radiant.RadiantConversationService
import com.canefe.story.event.EventManager
import com.canefe.story.information.WorldInformationManager
import com.canefe.story.location.LocationManager
import com.canefe.story.lore.LoreBookManager
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.NPCScheduleManager
import com.canefe.story.npc.behavior.NPCBehaviorManager
import com.canefe.story.npc.data.NPCDataManager
import com.canefe.story.npc.mythicmobs.MythicMobConversationIntegration
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
import com.canefe.story.util.PluginUtils
import com.canefe.story.util.TimeService
import dev.jorel.commandapi.CommandAPI
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture

class Story :
	JavaPlugin(),
	Listener {
	// Singleton instance
	companion object {
		lateinit var instance: Story
			private set
	}

	// Plugin configuration
	val configService = ConfigService(this)

	// gson
	val gson = com.google.gson.Gson()

	// Services and managers
	lateinit var audioManager: AudioManager

	lateinit var typingSessionManager: TypingSessionManager

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

	private lateinit var commandManager: CommandManager

	private lateinit var eventManager: EventManager

	lateinit var aiResponseService: AIResponseService

	lateinit var relationshipManager: RelationshipManager

	lateinit var mythicMobConversation: MythicMobConversationIntegration

	// Configuration and state
	val miniMessage = MiniMessage.miniMessage()
	var itemsAdderEnabled = false
		private set

	// Config Reference
	val configFile get() = super.getConfig()
	val config get() = configService

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

		// Initialize managers and services
		initializeManagers()

		// Register commands
		commandManager.registerCommands()

		// Start radiant conversation service
		radiantConversationService.startProximityTask()

		server.pluginManager.registerEvents(QuestListener(this), this)

		// Load configuration
		configService.reload()
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
		// Initialize the time service
		timeService = TimeService(this)

		typingSessionManager = TypingSessionManager(this)
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

		aiResponseService = AIResponseService(this)

		relationshipManager = RelationshipManager(this)

		mythicMobConversation = MythicMobConversationIntegration(this)
	}

	override fun onDisable() {
		logger.info("Story has been disabled.")
		CommandAPI.onDisable()
		scheduleManager.shutdown()
		commandManager.onDisable()
		eventManager.unregisterAll()
	}

	/**
	 * Safely stops the plugin by properly ending all ongoing conversations first
	 * and then shutting down all services.
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
		CitizensAPI
			.getNPCRegistry()
			.filter { npc ->
				npc.isSpawned &&
					npc.entity.location.world == player.location.world &&
					npc.entity.location.distanceSquared(player.location) <= radius
			}

	// TODO: This method should be moved to a dedicated NPC service
	fun getNearbyNPCs(
		npc: NPC,
		radius: Double,
	): List<NPC> {
		if (!npc.isSpawned) return emptyList()

		return CitizensAPI
			.getNPCRegistry()
			.filter { otherNpc ->
				otherNpc.isSpawned &&
					otherNpc != npc &&
					otherNpc.entity.location.world == npc.entity.location.world &&
					otherNpc.entity.location.distanceSquared(npc.entity.location) <= radius
			}
	}

	// TODO: This method should be moved to a dedicated Player service
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

	fun getAIResponse(
		prompts: List<ConversationMessage>,
		useStreaming: Boolean = false,
		streamHandler: (
			(String) -> Unit
		)? = null,
	): CompletableFuture<String?> {
		if (useStreaming) {
			val future = CompletableFuture<String?>()
			// null check streamingHandler
			if (streamHandler == null) {
				future.completeExceptionally(IllegalArgumentException("streamingHandler cannot be null when useStreaming is true"))
				return future
			}
			future.complete(aiResponseService.getAIResponseStreaming(prompts, streamHandler))
		}

		return aiResponseService.getAIResponseAsync(prompts)
	}
}
