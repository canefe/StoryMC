package com.canefe.story.dm

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.Msg.sendRaw
import com.google.gson.Gson
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Primary narrative tracking data class
 */
data class WorldNarrative(
    var currentChapter: String = "Introduction",
    val majorPlotPoints: MutableList<StoryBeat> = mutableListOf(),
    val playerJourneys: MutableMap<UUID, PlayerJourney> = mutableMapOf(),
    val worldEvents: MutableList<WorldEvent> = mutableListOf(),
)

data class StoryBeat(
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val involved: List<String> = emptyList(), // Player/NPC names
    val location: String = "", // Location name
    val significance: Int = 1, // 1-10 scale of importance
)

data class PlayerJourney(
    val playerName: String,
    val playerUUID: UUID,
    val personalStoryBeats: MutableList<StoryBeat> = mutableListOf(),
    val discoveredLocations: MutableSet<String> = mutableSetOf(),
    val npcRelationships: MutableMap<String, Int> = mutableMapOf(), // NPC name to relationship value (-100 to 100)
)

data class WorldEvent(
    val description: String,
    val commands: List<String> = emptyList(),
    val soundEffect: SoundEffect? = null,
    val targetedPlayers: List<UUID> = emptyList(), // Empty means broadcast to all
    val importance: Int = 5, // 1-10 scale
    val timestamp: Long = System.currentTimeMillis(),
)

data class SoundEffect(
    val sound: String, // Minecraft sound key
    val volume: Float = 1.0f,
    val pitch: Float = 1.0f,
    val location: SoundLocation? = null,
)

data class SoundLocation(
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

class AIDungeonMaster(
    private val plugin: Story,
) : Listener {
    private val gson = Gson()
    private val narrativeFile = File(plugin.dataFolder, "world_narrative.json")

    private var worldNarrative: WorldNarrative = WorldNarrative()
    private val locationVisitTracking = ConcurrentHashMap<UUID, String>() // Player UUID to last significant location

    // Event scheduling
    private var ambientEventTaskId: Int? = null
    private var narrativeProgressionTaskId: Int? = null
    private var pendingEvents = ConcurrentHashMap<UUID, Long>() // Event ID to scheduled time

    /**
     * Initializes the Dungeon Master system
     */
    fun initialize() {
        loadNarrative()
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Start ambient event generation (every 5-8 minutes)
        startAmbientEventGeneration()

        // Start narrative progression check (every 30 minutes)
        startNarrativeProgressionCheck()

        plugin.logger.info("AI Dungeon Master initialized with current chapter: ${worldNarrative.currentChapter}")
    }

    /**
     * Shuts down the Dungeon Master system
     */
    fun shutdown() {
        ambientEventTaskId?.let { plugin.server.scheduler.cancelTask(it) }
        narrativeProgressionTaskId?.let { plugin.server.scheduler.cancelTask(it) }
        saveNarrative()
        HandlerList.unregisterAll(this)
        plugin.logger.info("AI Dungeon Master shutdown. Narrative saved.")
    }

    /**
     * Load the current narrative state from file
     */
    private fun loadNarrative() {
        if (narrativeFile.exists()) {
            try {
                worldNarrative = gson.fromJson(narrativeFile.reader(), WorldNarrative::class.java)
                plugin.logger.info("Narrative loaded with ${worldNarrative.majorPlotPoints.size} major plot points")
            } catch (e: Exception) {
                plugin.logger.warning("Error loading narrative: ${e.message}")
                worldNarrative = WorldNarrative()
                saveNarrative() // Create new file with default state
            }
        } else {
            plugin.logger.info("No narrative file found. Creating new narrative.")
            worldNarrative = WorldNarrative()
            saveNarrative()
        }
    }

    /**
     * Save the current narrative state to file
     */
    private fun saveNarrative() {
        try {
            narrativeFile.writer().use { writer ->
                gson.toJson(worldNarrative, writer)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error saving narrative: ${e.message}")
        }
    }

    /**
     * Start ambient event generation task
     */
    private fun startAmbientEventGeneration() {
        val initialDelayTicks = 20L * 5 // 3 minutes
        val periodTicks = 20L * 8 // 6 minutes

        ambientEventTaskId =
            plugin.server.scheduler
                .runTaskTimerAsynchronously(
                    plugin,
                    Runnable {
                        if (plugin.server.onlinePlayers.isEmpty()) return@Runnable

                        // Choose ambient event type based on time and online players
                        val eventType = chooseAmbientEventType()
                        generateAmbientEvent(eventType)
                    },
                    initialDelayTicks,
                    periodTicks,
                ).taskId
    }

    /**
     * Choose what type of ambient event to generate
     */
    private fun chooseAmbientEventType(): String {
        val options =
            listOf(
                "weather",
                "wildlife",
                "distant_sounds",
                "atmosphere",
                "npc_activity",
                "world_lore",
                "environment",
            )

        // Check world time to influence event type
        val worldTime =
            plugin.server.worlds
                .firstOrNull()
                ?.time ?: 0

        return when {
            worldTime in 13000..23000 -> { // Night
                val nightOptions = options + listOf("mysterious", "danger", "creatures")
                nightOptions.random()
            }

            worldTime in 0..1000 || worldTime in 11000..13000 -> { // Dawn/Dusk
                val transitionOptions = options + listOf("transition", "beauty")
                transitionOptions.random()
            }

            else -> options.random() // Day
        }
    }

    /**
     * Start narrative progression check task
     */
    private fun startNarrativeProgressionCheck() {
        val initialDelayTicks = 20L * 10 // 15 minutes
        val periodTicks = 20L * 30 // 30 minutes

        narrativeProgressionTaskId =
            plugin.server.scheduler
                .runTaskTimerAsynchronously(
                    plugin,
                    Runnable {
                        if (plugin.server.onlinePlayers.isEmpty()) return@Runnable

                        evaluateStoryProgression()
                    },
                    initialDelayTicks,
                    periodTicks,
                ).taskId
    }

    /**
     * Evaluates if it's time to advance the story
     */
    private fun evaluateStoryProgression() {
        // Count recent significant events
        val recentEvents =
            worldNarrative.worldEvents
                .filter { System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000 } // Last 24 hours
                .filter { it.importance >= 7 } // Only significant events

        // If enough significant events, consider advancing the narrative
        if (recentEvents.size >= 3) {
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable {
                    val narrativeContext = buildNarrativeContext()
                    val prompt = "Based on recent significant events and the current chapter '${worldNarrative.currentChapter}', should the story advance to a new chapter? If yes, what should the new chapter be called and what major event should mark this transition?"

                    requestNarrativeGuidance(prompt, narrativeContext).thenAccept { response ->
                        if (response != null && response.contains("YES")) {
                            // Extract new chapter name
                            val chapterRegex = "NEW CHAPTER: ([^\n]+)".toRegex()
                            val eventRegex = "EVENT: ([^\n]+)".toRegex()

                            val chapterMatch = chapterRegex.find(response)
                            val eventMatch = eventRegex.find(response)

                            if (chapterMatch != null && eventMatch != null) {
                                val newChapter = chapterMatch.groupValues[1]
                                val chapterEvent = eventMatch.groupValues[1]

                                // Add major plot point
                                val plotPoint =
                                    StoryBeat(
                                        description = chapterEvent,
                                        significance = 10,
                                        involved = plugin.server.onlinePlayers.map { it.name },
                                    )

                                worldNarrative.currentChapter = newChapter
                                worldNarrative.majorPlotPoints.add(plotPoint)

                                // Create a major world event for this
                                generateChapterTransitionEvent(newChapter, chapterEvent)

                                saveNarrative()
                            }
                        }
                    }
                },
            )
        }
    }

    /**
     * Generate a major event for chapter transition
     */
    private fun generateChapterTransitionEvent(
        newChapter: String,
        eventDescription: String,
    ) {
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val context = buildFullWorldContext()
                val prompt = "Generate a dramatic, server-wide event announcement for the transition to a new chapter in our story. The new chapter is called '$newChapter' and involves: $eventDescription. Include suggested commands for visual/sound effects and create an immersive scene."

                generateWorldEvent(prompt, context, true).thenAccept { event ->
                    if (event != null) {
                        worldNarrative.worldEvents.add(event)
                        executeEvent(event)
                        saveNarrative()
                    }
                }
            },
        )
    }

    /**
     * Generate ambient world event
     */
    private fun generateAmbientEvent(eventType: String) {
        // Select target area based on player locations
        val targets = selectEventTargets()
        if (targets.isEmpty()) return

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                // Build MORE SPECIFIC context with actual lore references
                val context = buildEnhancedEventContext(targets)

                // Updated prompt that demands lore integration
                val prompt =
                    "Create a narrative moment that incorporates specific world lore elements " +
                        "while adding $eventType atmosphere. Reference characters, history, or locations " +
                        "from the context. Write in an evocative, literary style with deeper meaning."

                // Increase importance for better quality
                generateWorldEvent(prompt, context, true).thenAccept { event ->
                    if (event != null) {
                        worldNarrative.worldEvents.add(event)
                        executeEvent(event)
                    }
                }
            },
        )
    }

    // Create an enhanced context method that prioritizes story elements
    private fun buildEnhancedEventContext(targets: List<Player>): String {
        val sb = StringBuilder()

        // First add critical lore and story elements
        sb.append("CRITICAL STORY ELEMENTS TO REFERENCE:\n")
        plugin.lorebookManager.getAllLoreBooks().take(3).forEach { lore ->
            sb.append("- ${lore.name}: ${lore.context}\n")
        }

        // Then add current chapter and plot points
        sb.append("CURRENT NARRATIVE:\n")
        sb.append("Chapter: ${worldNarrative.currentChapter}\n")
        worldNarrative.majorPlotPoints.takeLast(2).forEach { point ->
            sb.append("- ${point.description}\n")
        }

        // Then add standard context
        sb.append(buildEventContext(targets))

        return sb.toString()
    }

    /**
     * Execute a world event (display text, run commands, play sounds)
     */
    private fun executeEvent(event: WorldEvent) {
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                // Send description to appropriate players
                if (event.targetedPlayers.isEmpty()) {
                    // Broadcast to all players
                    val formattedMessage = "<dark_gray>[<gold>World</gold>]</dark_gray> ${event.description}"
                    plugin.server.onlinePlayers.forEach { it.sendRaw(formattedMessage) }
                } else {
                    // Send only to targeted players
                    val formattedMessage = "<dark_gray>[<gold>Nearby</gold>]</dark_gray> ${event.description}"
                    event.targetedPlayers.forEach { uuid ->
                        plugin.server.getPlayer(uuid)?.sendRaw(formattedMessage)
                    }
                }

                // Execute commands
                event.commands.forEach { cmd ->
                    try {
                        plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to execute command from event: $cmd")
                    }
                }

                // Play sound effects
                event.soundEffect?.let { soundEffect ->
                    try {
                        val sound =
                            try {
                                Sound.valueOf(
                                    soundEffect.sound
                                        .replace("minecraft:", "")
                                        .replace(".", "_")
                                        .uppercase(),
                                )
                            } catch (e: IllegalArgumentException) {
                                null
                            }

                        // Play sound at specific location or for specific players
                        if (soundEffect.location != null) {
                            val world = plugin.server.getWorld(soundEffect.location.worldName)
                            if (world != null) {
                                val loc =
                                    Location(
                                        world,
                                        soundEffect.location.x,
                                        soundEffect.location.y,
                                        soundEffect.location.z,
                                    )
                                if (sound != null) {
                                    world.playSound(loc, sound, soundEffect.volume, soundEffect.pitch)
                                } else {
                                    world.playSound(loc, soundEffect.sound, soundEffect.volume, soundEffect.pitch)
                                }
                            }
                        } else if (event.targetedPlayers.isNotEmpty()) {
                            // Play for targeted players
                            event.targetedPlayers.forEach { uuid ->
                                val player = plugin.server.getPlayer(uuid)
                                if (player != null) {
                                    if (sound != null) {
                                        player.playSound(player.location, sound, soundEffect.volume, soundEffect.pitch)
                                    } else {
                                        player.playSound(
                                            player.location,
                                            soundEffect.sound,
                                            soundEffect.volume,
                                            soundEffect.pitch,
                                        )
                                    }
                                }
                            }
                        } else {
                            // Play for all players
                            plugin.server.onlinePlayers.forEach { player ->
                                if (sound != null) {
                                    player.playSound(player.location, sound, soundEffect.volume, soundEffect.pitch)
                                } else {
                                    player.playSound(
                                        player.location,
                                        soundEffect.sound,
                                        soundEffect.volume,
                                        soundEffect.pitch,
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to play sound effect: ${e.message}")
                    }
                }
            },
        )
    }

    /**
     * Select target players for an event based on proximity and activity
     */
    private fun selectEventTargets(): List<Player> {
        val onlinePlayers = plugin.server.onlinePlayers.toList()
        if (onlinePlayers.isEmpty()) return emptyList()

        // For ambient events, choose all players or a cluster of nearby players
        return if (Math.random() < 0.7) {
            // Target all players
            onlinePlayers
        } else {
            // Target a random cluster of nearby players
            val seed = onlinePlayers.random()
            onlinePlayers.filter { player ->
                player.world === seed.world && player.location.distance(seed.location) < 50
            }
        }
    }

    /**
     * Generate a world event using AI
     */
    private fun generateWorldEvent(
        prompt: String,
        context: String,
        isSignificant: Boolean,
    ): CompletableFuture<WorldEvent?> {
        val result = CompletableFuture<WorldEvent?>()

        // Create system prompt
        val systemPrompt =
            """
            You are an AI Dungeon Master generating immersive events for a Minecraft RPG server.

            Generate a realistic, immersive event description that fits the medieval fantasy setting.
            For significant events: Create dramatic, impactful descriptions that feel important.
            For ambient events: Create subtle, atmospheric descriptions that add flavor.

            Respond in strict JSON format:
            {
                "description": "Your detailed event description that will be shown to players",
                "commands": ["command1", "command2"],
                "soundEffect": {"sound": "minecraft:ambient.cave", "volume": 1.0, "pitch": 1.0}
            }

            Available commands:
            - "weather thunder" - Changes weather to thunder
            - "time set <time>" - Sets world time
            - "playsound <sound> <player> <location> <volume> <pitch>"

            Keep descriptions concise (1-3 sentences) and immersive.
            ${if (isSignificant) "This is a SIGNIFICANT event - make it dramatic and memorable!" else ""}
            """.trimIndent()

        val promptWithContext = "CONTEXT: $context\n\nEVENT REQUEST: $prompt"

        val messages =
            mutableListOf(
                ConversationMessage("system", systemPrompt),
                ConversationMessage("user", promptWithContext),
            )

        plugin
            .getAIResponse(messages)
            .thenAccept { response ->
                if (response.isNullOrBlank()) {
                    plugin.logger.warning("Received empty response for event generation")
                    result.complete(null)
                    return@thenAccept
                }

                try {
                    // Extract JSON portion if there's any extra text
                    val jsonStartIndex = response.indexOf("{")
                    val jsonEndIndex = response.lastIndexOf("}") + 1

                    if (jsonStartIndex >= 0 && jsonEndIndex > jsonStartIndex) {
                        val jsonStr = response.substring(jsonStartIndex, jsonEndIndex)
                        val jsonEvent = gson.fromJson(jsonStr, Map::class.java)

                        // Create event with safe handling of fields
                        val event =
                            WorldEvent(
                                description = (jsonEvent["description"] as? String) ?: "Something happens...",
                                commands =
                                    (jsonEvent["commands"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                                soundEffect =
                                    if (jsonEvent.containsKey("soundEffect")) {
                                        gson.fromJson(gson.toJson(jsonEvent["soundEffect"]), SoundEffect::class.java)
                                    } else {
                                        null
                                    },
                                targetedPlayers = emptyList(), // Always provide an empty list, never null
                                importance = if (isSignificant) 8 else 3,
                            )
                        result.complete(event)
                    } else {
                        // Fallback if no valid JSON found
                        result.complete(
                            WorldEvent(
                                description = response.take(200),
                                importance = if (isSignificant) 8 else 3,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to parse event response: ${e.message}")
                    result.complete(
                        WorldEvent(
                            description = "Something stirs in the world...",
                            importance = if (isSignificant) 7 else 2,
                        ),
                    )
                }
            }.exceptionally { e ->
                plugin.logger.warning("Error generating event: ${e.message}")
                result.complete(null)
                null
            }

        return result
    }

    /**
     * Request narrative guidance from AI
     */
    private fun requestNarrativeGuidance(
        prompt: String,
        context: String,
    ): CompletableFuture<String?> {
        val result = CompletableFuture<String?>()

        val systemPrompt =
            """
            You are a master storyteller helping to guide the narrative of a Minecraft RPG server.
            Analyze the current state of the story and the context provided.

            If you believe the narrative should advance to a new chapter, respond with:
            YES
            NEW CHAPTER: [name of the new chapter]
            EVENT: [description of the major event that marks this transition]

            If you believe the narrative should continue in the current chapter, respond with:
            NO
            REASON: [brief explanation of why the story should continue in its current chapter]

            Consider pacing, player engagement, and dramatic structure in your recommendation.
            """.trimIndent()

        val messages =
            mutableListOf(
                ConversationMessage("system", systemPrompt),
                ConversationMessage("user", "CONTEXT:\n$context\n\nQUESTION: $prompt"),
            )

        plugin
            .getAIResponse(messages)
            .thenAccept { response ->
                result.complete(response)
            }.exceptionally { e ->
                plugin.logger.warning("Error getting narrative guidance: ${e.message}")
                result.complete(null)
                null
            }

        return result
    }

    /**
     * Build comprehensive narrative context
     */
    private fun buildNarrativeContext(): String {
        val sb = StringBuilder()

        sb.append("CURRENT CHAPTER: ${worldNarrative.currentChapter}\n\n")

        // Add recent major plot points
        val recentPlotPoints = worldNarrative.majorPlotPoints.takeLast(5)
        if (recentPlotPoints.isNotEmpty()) {
            sb.append("RECENT MAJOR PLOT POINTS:\n")
            recentPlotPoints.forEach { plotPoint ->
                val timeAgo = (System.currentTimeMillis() - plotPoint.timestamp) / (1000 * 60 * 60)
                sb.append("- ${plotPoint.description} (${timeAgo}h ago, significance: ${plotPoint.significance}/10)\n")
            }
            sb.append("\n")
        }

        // Add recent world events
        val recentEvents =
            worldNarrative.worldEvents
                .filter { System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000 } // Last 24 hours
                .filter { it.importance >= 5 }
                .takeLast(10)

        if (recentEvents.isNotEmpty()) {
            sb.append("RECENT SIGNIFICANT EVENTS:\n")
            recentEvents.forEach { event ->
                val timeAgo = (System.currentTimeMillis() - event.timestamp) / (1000 * 60)
                sb.append("- ${event.description} (${timeAgo}m ago, importance: ${event.importance}/10)\n")
            }
            sb.append("\n")
        }

        // Current active players
        val activePlayers = plugin.server.onlinePlayers
        if (activePlayers.isNotEmpty()) {
            sb.append("ACTIVE PLAYERS: ${activePlayers.map { it.name }.joinToString(", ")}\n\n")
        }

        return sb.toString()
    }

    /**
     * Build context for a specific event targeting specific players
     */
    private fun buildEventContext(targets: List<Player>): String {
        val sb = StringBuilder()

        // Add general world info
        val worldTime =
            plugin.server.worlds
                .firstOrNull()
                ?.time ?: 0
        val worldWeather =
            plugin.server.worlds
                .firstOrNull()
                ?.hasStorm() ?: false

        sb.append("WORLD: Time is ${formatMinecraftTime(worldTime)}, ")
        sb.append("Weather is ${if (worldWeather) "stormy" else "clear"}\n")
        sb.append("CHAPTER: ${worldNarrative.currentChapter}\n\n")

        // If targeting specific players, add their context
        if (targets.size <= 5) {
            targets.forEach { player ->
                // Add player location context
                val locationName = getPlayerLocationName(player)
                sb.append("PLAYER ${player.name} is at location: $locationName\n")

                // Add nearby NPCs
                val nearbyNpcs = plugin.npcUtils.getNearbyNPCs(player, 30.0)
                if (nearbyNpcs.isNotEmpty()) {
                    sb.append("Nearby NPCs: ${nearbyNpcs.joinToString(", ") { it.name }}\n")

                    // Add detailed context for up to 2 closest NPCs
                    nearbyNpcs.take(2).forEach { npc ->
                        addNPCDetails(sb, npc.name)
                    }
                }

                // Add location details
                addLocationDetails(sb, locationName)

                sb.append("\n")
            }
        } else {
            // For many targets, just summarize
            val locations = targets.mapNotNull { getPlayerLocationName(it) }.distinct()
            sb.append("PLAYERS are at these locations: ${locations.joinToString(", ")}\n")

            // Add brief context for the locations
            locations.take(3).forEach { locationName ->
                addLocationDetails(sb, locationName)
            }
        }

        return sb.toString()
    }

    /**
     * Build full world context including all relevant information
     */
    private fun buildFullWorldContext(): String {
        val sb = StringBuilder()

        sb.append("NARRATIVE CONTEXT:\n")
        sb.append(buildNarrativeContext())
        sb.append("\n")

        // Add details about key locations
        sb.append("KEY LOCATIONS:\n")
        plugin.locationManager.getAllLocations().take(5).forEach { location ->
            sb.append("- ${location.name}: ${location.context.firstOrNull() ?: "No context"}\n")
        }
        sb.append("\n")

        // Add details about key NPCs
        sb.append("KEY NPCS:\n")
        plugin.npcDataManager.getAllNPCNames().take(5).forEach { npcName ->
            val npc = plugin.npcDataManager.getNPCData(npcName)
            if (npc != null) {
                sb.append("- $npcName: ${npc.context.take(100)}...\n")
            }
        }
        sb.append("\n")

        // Add Lore Information
        sb.append("WORLD LORE HIGHLIGHTS:\n")
        plugin.lorebookManager.getAllLoreBooks().take(3).forEach { lore ->
            sb.append("- ${lore.name}: ${lore.context.take(100)}...\n")
        }

        return sb.toString()
    }

    /**
     * Add NPC details to a StringBuilder
     */
    private fun addNPCDetails(
        sb: StringBuilder,
        npcName: String,
    ) {
        val npcData = plugin.npcDataManager.getNPCData(npcName) ?: return

        sb.append("NPC $npcName: ${npcData.context.take(150)}...\n")

        // Add recent memories if available
        if (npcData.memory.isNotEmpty()) {
            val recentMemories = npcData.memory.takeLast(2)
            sb.append("Recent memories: ${recentMemories.joinToString("; ") { it.content.take(50) }}\n")
        }
    }

    /**
     * Add location details to a StringBuilder
     */
    private fun addLocationDetails(
        sb: StringBuilder,
        locationName: String,
    ) {
        if (locationName.isBlank()) return

        val location = plugin.locationManager.getLocation(locationName) ?: return

        sb.append("LOCATION $locationName: ${location.context.joinToString(". ")}\n")

        // Add related lore if available
        val relatedLore = plugin.lorebookManager.findLoresByKeywords(locationName)
        if (relatedLore.isNotEmpty()) {
            sb.append("Related lore: ${relatedLore.first().context.take(100)}...\n")
        }
    }

    /**
     * Get the name of a player's current location
     */
    private fun getPlayerLocationName(player: Player): String {
        val loc = player.location
        return plugin.locationManager.getLocationByPosition(loc)?.name ?: "unknown area"
    }

    /**
     * Format Minecraft time to a readable string
     */
    private fun formatMinecraftTime(time: Long): String =
        when (time) {
            in 0..1000 -> "dawn/sunrise"
            in 1000..6000 -> "morning"
            in 6000..9000 -> "mid-day"
            in 9000..12000 -> "afternoon"
            in 12000..13000 -> "dusk/sunset"
            in 13000..18000 -> "evening"
            in 18000..22000 -> "night"
            else -> "late night"
        }

    /**
     * Event handlers for player movement to track location changes
     */
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Only process every 20 blocks or so to avoid constant checking
        if (event.from.distance(event.to!!) < 20) return

        val player = event.player
        val currentLocation = getPlayerLocationName(player)
        val lastLocation = locationVisitTracking.get(player.uniqueId)

        // If player entered a new named location
        if (currentLocation != lastLocation && currentLocation != "unknown area") {
            locationVisitTracking.put(player.uniqueId, currentLocation)

            // Track this in player journey
            val playerJourney =
                worldNarrative.playerJourneys.getOrPut(player.uniqueId) {
                    PlayerJourney(player.name, player.uniqueId)
                }

            // If this is a new discovery for this player
            if (playerJourney.discoveredLocations.add(currentLocation)) {
                // Schedule location discovery event
                scheduleLocationDiscoveryEvent(player, currentLocation)
            }
        }
    }

    /**
     * Schedule a location discovery event for a player
     */
    private fun scheduleLocationDiscoveryEvent(
        player: Player,
        locationName: String,
    ) {
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val location = plugin.locationManager.getLocation(locationName) ?: return@Runnable

                val context = StringBuilder()
                context.append("Player ${player.name} has discovered location $locationName.\n")
                context.append("Location context: ${location.context.joinToString(". ")}\n")

                // Get related lore
                val relatedLore = plugin.lorebookManager.findLoresByKeywords(locationName)
                if (relatedLore.isNotEmpty()) {
                    context.append("Related lore: ${relatedLore.map { it.context.take(100) }.joinToString(". ")}\n")
                }

                val prompt = "Generate a location discovery event for player ${player.name} who just discovered $locationName for the first time. Create a brief, immersive description of what they see, feel, or notice about this location. This should be a personal discovery moment."

                generateWorldEvent(prompt, context.toString(), false).thenAccept { event ->
                    if (event != null) {
                        // Make this a targeted event just for this player
                        val playerEvent =
                            event.copy(
                                targetedPlayers = listOf(player.uniqueId),
                                importance = 6, // Location discoveries are somewhat important
                            )

                        worldNarrative.worldEvents.add(playerEvent)
                        executeEvent(playerEvent)

                        // Add this as a story beat in player's journey
                        val playerJourney = worldNarrative.playerJourneys[player.uniqueId]
                        playerJourney?.personalStoryBeats?.add(
                            StoryBeat(
                                description = "Discovered $locationName: ${event.description}",
                                location = locationName,
                                significance = 6,
                            ),
                        )

                        saveNarrative()
                    }
                }
            },
        )
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Create or get player journey
        val playerJourney =
            worldNarrative.playerJourneys.getOrPut(player.uniqueId) {
                PlayerJourney(player.name, player.uniqueId)
            }

        // Send current chapter info
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                player.sendRaw(
                    "<dark_gray>[<gold>Chapter</gold>]</dark_gray> <italic>Current chapter: ${worldNarrative.currentChapter}</italic>",
                )
            },
            60L, // 3 second delay
        )

        // Generate welcome back event
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                // Don't generate for brand new players
                if (playerJourney.personalStoryBeats.isEmpty()) return@Runnable

                val context = StringBuilder()
                context.append("Player ${player.name} has just logged in.\n")

                // Add some recent personal story beats
                if (playerJourney.personalStoryBeats.isNotEmpty()) {
                    val recentBeats = playerJourney.personalStoryBeats.takeLast(3)
                    context.append("Recent activities: ${recentBeats.joinToString(", ") { it.description }}\n")
                }

                val locationName = getPlayerLocationName(player)
                context.append("Current location: $locationName\n")

                val prompt = "Generate a subtle welcome back message for player ${player.name} who has just logged into the server. This should connect to their past activities or the current state of the world, but be concise and atmospheric."

                generateWorldEvent(prompt, context.toString(), false).thenAccept { event ->
                    if (event != null) {
                        // Make this a targeted event just for this player
                        val playerEvent =
                            event.copy(
                                targetedPlayers = listOf(player.uniqueId),
                                importance = 2, // Low importance welcome event
                            )

                        // Delay this slightly so it doesn't get lost in login messages
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable { executeEvent(playerEvent) },
                            100L, // 5 second delay
                        )
                    }
                }
            },
        )
    }

    /**
     * Create a major quest-related event
     */
    fun createQuestEvent(
        questName: String,
        eventType: String,
        players: List<Player>,
        locationName: String? = null,
    ): CompletableFuture<WorldEvent?> =
        plugin.server.scheduler
            .callSyncMethod(plugin) {
                val context = StringBuilder()
                context.append("QUEST EVENT: $questName - $eventType\n")
                context.append("PLAYERS INVOLVED: ${players.joinToString(", ") { it.name }}\n")

                if (locationName != null) {
                    context.append("LOCATION: $locationName\n")
                    // Add location context
                    val location = plugin.locationManager.getLocation(locationName)
                    if (location != null) {
                        context.append("Location context: ${location.context.joinToString(". ")}\n")
                    }

                    // Add related lore
                    val relatedLore = plugin.lorebookManager.findLoresByKeywords(locationName)
                    if (relatedLore.isNotEmpty()) {
                        context.append("Related lore: ${relatedLore.first().context.take(100)}...\n")
                    }
                }

                val prompt =
                    when (eventType) {
                        "start" -> "Generate a dramatic quest beginning announcement for the quest '$questName'. This should feel like the start of an adventure."
                        "progress" -> "Generate a quest progression update for the quest '$questName'. This should acknowledge progress and hint at what lies ahead."
                        "complete" -> "Generate a satisfying quest completion announcement for the quest '$questName'. This should feel rewarding and significant."
                        "fail" -> "Generate a quest failure announcement for the quest '$questName'. This should be dramatic but not overly negative."
                        else -> "Generate a quest-related announcement for the quest '$questName'."
                    }

                val playerUUIDs = players.map { it.uniqueId }

                generateWorldEvent(
                    prompt,
                    context.toString(),
                    eventType in listOf("start", "complete"),
                ).thenApply { event ->
                    if (event != null) {
                        val questEvent =
                            event.copy(
                                targetedPlayers = playerUUIDs,
                                importance =
                                    when (eventType) {
                                        "start" -> 7
                                        "complete" -> 8
                                        "fail" -> 7
                                        else -> 5
                                    },
                            )

                        worldNarrative.worldEvents.add(questEvent)

                        // Add this as a major plot point if it's a completion
                        if (eventType == "complete") {
                            worldNarrative.majorPlotPoints.add(
                                StoryBeat(
                                    description = "Quest '$questName' completed: ${event.description}",
                                    involved = players.map { it.name },
                                    location = locationName ?: "",
                                    significance = 8,
                                ),
                            )
                        }

                        // Add to player journeys
                        players.forEach { player ->
                            val journey =
                                worldNarrative.playerJourneys.getOrPut(player.uniqueId) {
                                    PlayerJourney(player.name, player.uniqueId)
                                }

                            journey.personalStoryBeats.add(
                                StoryBeat(
                                    description = "$questName ($eventType): ${event.description}",
                                    location = locationName ?: "",
                                    significance =
                                        when (eventType) {
                                            "start" -> 7
                                            "complete" -> 8
                                            "fail" -> 7
                                            else -> 5
                                        },
                                ),
                            )
                        }

                        saveNarrative()
                        executeEvent(questEvent)
                    }

                    event
                }
            }.get()

    /**
     * Ask the DM a question about the world, story, or characters
     */
    fun askDungeonMaster(
        player: Player,
        question: String,
    ): CompletableFuture<String> {
        val result = CompletableFuture<String>()

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                // Build comprehensive context
                val context = buildDMQuestionContext(player, question)

                val systemPrompt =
                    """
                    You are an immersive, knowledgeable Game Master for a medieval fantasy RPG set in Minecraft.

                    Answer the player's question in-character as a wise, omniscient narrator who knows everything about the game world.

                    When responding:
                    - Use detailed, vivid language appropriate to the medieval fantasy setting
                    - Reference relevant lore, NPCs, and locations mentioned in the context
                    - Use MiniMessage formatting for better readability (<gold>, <italic>, etc.)
                    - Format important names and places with <yellow> tags
                    - Break longer responses into paragraphs for readability
                    - Stay in-character as a Game Master - no references to AI, servers, etc.
                    - If you truly don't know something, weave a mysterious answer rather than admitting ignorance

                    Maximum response length: 300 words
                    """.trimIndent()

                val messages =
                    mutableListOf(
                        ConversationMessage("system", systemPrompt),
                        ConversationMessage("user", "CONTEXT:\n$context\n\nPLAYER QUESTION: $question"),
                    )

                plugin
                    .getAIResponse(messages)
                    .thenAccept { response ->
                        if (response.isNullOrBlank()) {
                            result.complete("The Game Master ponders your question, but remains silent for now...")
                        } else {
                            result.complete(response)
                        }
                    }.exceptionally { e ->
                        plugin.logger.warning("Error getting DM response: ${e.message}")
                        result.complete("The Game Master seems distracted by other matters right now...")
                        null
                    }
            },
        )

        return result
    }

    /**
     * Build context for a DM question
     */
    private fun buildDMQuestionContext(
        player: Player,
        question: String,
    ): String {
        val sb = StringBuilder()

        // Add basic narrative context
        sb.append("CURRENT CHAPTER: ${worldNarrative.currentChapter}\n")

        // Add player context
        val playerJourney = worldNarrative.playerJourneys[player.uniqueId]
        if (playerJourney != null && playerJourney.personalStoryBeats.isNotEmpty()) {
            sb.append(
                "PLAYER HISTORY: ${
                    playerJourney.personalStoryBeats.takeLast(3).joinToString(". ") {
                        it.description
                    }
                }\n",
            )
        }

        // Extract potential relevant terms from the question
        val keywords =
            question
                .split(" ")
                .filter { it.length > 4 }
                .map { it.lowercase().trim(',', '.', '?', '!', '\'', '\"') }
                .distinct()

        // Check for location references
        val mentionedLocations = mutableListOf<String>()
        plugin.locationManager.getAllLocations().forEach { location ->
            if (question.contains(location.name, ignoreCase = true) ||
                keywords.any { keyword -> location.name.contains(keyword, ignoreCase = true) }
            ) {
                mentionedLocations.add(location.name)
            }
        }

        if (mentionedLocations.isNotEmpty()) {
            sb.append("MENTIONED LOCATIONS:\n")
            mentionedLocations.forEach { locationName ->
                val location = plugin.locationManager.getLocation(locationName)
                if (location != null) {
                    sb.append("- $locationName: ${location.context.joinToString(". ")}\n")
                }
            }
        }

        // Check for NPC references
        val mentionedNPCs = mutableListOf<String>()
        plugin.npcDataManager.getAllNPCNames().forEach { npcName ->
            if (question.contains(npcName, ignoreCase = true)) {
                mentionedNPCs.add(npcName)
            }
        }

        if (mentionedNPCs.isNotEmpty()) {
            sb.append("MENTIONED NPCS:\n")
            mentionedNPCs.forEach { npcName ->
                val npcData = plugin.npcDataManager.getNPCData(npcName)
                if (npcData != null) {
                    sb.append("- $npcName: ${npcData.context}\n")
                    if (npcData.memory.isNotEmpty()) {
                        sb.append(
                            "  Recent memories: ${
                                npcData.memory.takeLast(2).joinToString("; ") {
                                    it.content
                                }
                            }\n",
                        )
                    }
                }
            }
        }

        // Check for lore references
        val relatedLore = plugin.lorebookManager.findLoresByKeywords(question)
        if (relatedLore.isNotEmpty()) {
            sb.append("RELEVANT LORE:\n")
            relatedLore.take(3).forEach { lore ->
                sb.append("- ${lore.loreName}: ${lore.context}\n")
            }
        }

        return sb.toString()
    }
}
