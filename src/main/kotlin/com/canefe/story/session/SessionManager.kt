package com.canefe.story.session

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.storage.SessionDocument
import com.canefe.story.storage.SessionStorage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * Manager to track gameplay sessions and persist them using a storage backend.
 */
class SessionManager private constructor(
    private val plugin: Story,
    private var sessionStorage: SessionStorage,
) {
    fun updateStorage(storage: SessionStorage) {
        sessionStorage = storage
    }

    private val current = AtomicReference<Session?>(null)
    private var currentSessionId: String? = null
    private var lastSaveTime: Long = 0

    init {
        // Start autosave task (every 2 minutes)
        plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable {
                autosaveCurrentSession()
            },
            20 * 30,
            20 * 120,
        ) // 30s initial delay, 2min interval
    }

    fun load() {
        autosaveCurrentSession()
    }

    /** Start a new session if none is active. */
    fun startSession() {
        if (current.get() != null) return

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss"))
        val session = Session(plugin.timeService.getCurrentGameTime())
        current.set(session)

        currentSessionId = "session-$timestamp"

        // Let's add all currently online players to the session by default
        plugin.server.onlinePlayers.forEach { player ->
            session.players.add(player.name)
        }

        // Save immediately
        autosaveCurrentSession()

        plugin.logger.info("Started new session: $currentSessionId")
    }

    /** Add a player name to the active session. */
    fun addPlayer(name: String) {
        current.get()?.players?.add(name)
        autosaveCurrentSession()
    }

    /**
     * Generate an AI response based on the input text enriched with relevant context,
     * then append the response to the session history.
     */
    fun feed(text: String) {
        val session = current.get() ?: return

        // Gather context from lore, NPCs, and locations
        val contextBuilder = StringBuilder()

        // Find relevant lore
        val loreContexts = plugin.lorebookManager.findLoresByKeywords(text)
        if (loreContexts.isNotEmpty()) {
            contextBuilder.append("RELEVANT LORE:\n")
            loreContexts.take(3).forEach { lore ->
                contextBuilder.append("- ${lore.loreName}: ${lore.context}\n")
            }
            contextBuilder.append("\n")
        }

        // Extract keywords for location and NPC matching
        val keywords =
            text
                .split(" ")
                .filter { it.length > 3 }
                .map { it.lowercase().trim(',', '.', '?', '!', '\'', '"') }
                .distinct()

        // Determine which player is mentioned in the text and get their current location
        var currentPlayerLocation: String? = null
        for (playerName in session.players) {
            val player = plugin.server.getPlayer(playerName)
            if (player != null) {
                val nickname =
                    com.canefe.story.util.EssentialsUtils
                        .getNickname(player.name)
                if (text.contains(player.name, ignoreCase = true) ||
                    text.contains(nickname, ignoreCase = true) ||
                    keywords.any { keyword ->
                        keyword.equals(player.name, ignoreCase = true) ||
                            keyword.equals(nickname, ignoreCase = true)
                    }
                ) {
                    val actualLocation = plugin.locationManager.getLocationByPosition2D(player.location, 150.0)
                    if (actualLocation != null) {
                        currentPlayerLocation = "${player.name} is currently at ${actualLocation.name}.\n" +
                            "Location context: ${actualLocation.getContextForPrompt(plugin.locationManager)}\n"
                        break
                    }
                }
            }
        }

        if (currentPlayerLocation == null) {
            for (playerName in session.players) {
                val player = plugin.server.getPlayer(playerName)
                if (player != null) {
                    val actualLocation = plugin.locationManager.getLocationByPosition2D(player.location, 150.0)
                    if (actualLocation != null) {
                        currentPlayerLocation = "Current scene location: ${actualLocation.name}.\n" +
                            "Location context: ${actualLocation.getContextForPrompt(plugin.locationManager)}\n"
                        break
                    }
                }
            }
        }

        if (currentPlayerLocation != null) {
            contextBuilder.append("CURRENT LOCATION:\n")
            contextBuilder.append(currentPlayerLocation)
            contextBuilder.append("\n")
        }

        val mentionedLocations =
            plugin.locationManager.getAllLocations().filter { location ->
                text.contains(location.name, ignoreCase = true) ||
                    keywords.any { keyword -> location.name.contains(keyword, ignoreCase = true) }
            }

        if (mentionedLocations.isNotEmpty()) {
            contextBuilder.append("RELEVANT LOCATIONS:\n")
            mentionedLocations.take(3).forEach { location ->
                contextBuilder.append("- ${location.name}: ${location.context.joinToString(". ")}\n")
            }
            contextBuilder.append("\n")
        }

        val mentionedNPCs =
            plugin.npcDataManager.getAllNPCNames().filter { npcName ->
                text.contains(npcName, ignoreCase = true) ||
                    keywords.any { keyword ->
                        keyword.equals(npcName, ignoreCase = true) || npcName.contains(keyword, ignoreCase = true)
                    }
            }

        if (mentionedNPCs.isNotEmpty()) {
            contextBuilder.append("RELEVANT NPCS:\n")
            mentionedNPCs.take(3).forEach { npcName ->
                val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
                val lastFewMemories = npcContext?.getMemoriesForPrompt(plugin.timeService, 3)
                if (npcContext != null) {
                    contextBuilder.append("- $npcName: ${npcContext.context}\n")
                    if (lastFewMemories != null && lastFewMemories.isNotEmpty()) {
                        contextBuilder.append("  Recent memories: $lastFewMemories\n")
                    }
                }
            }
            contextBuilder.append("\n")
        }

        if (session.history.isNotEmpty()) {
            contextBuilder.append("CURRENT SESSION HISTORY:\n")
            contextBuilder.append(session.history.toString())
            contextBuilder.append("\n")
        }

        val messages =
            mutableListOf(
                ConversationMessage(
                    "system",
                    """
            You are a narrative storyteller in a medieval fantasy world. Convert the given input
            into a rich narrative description, incorporating any context about locations, NPCs,
            and lore provided below.

            Write in a descriptive, literary style appropriate for a fantasy story.
            Be specific, evocative, and incorporate references to the world.
            Limit your response to 2-3 paragraphs maximum.

            $contextBuilder
            """,
                ),
                ConversationMessage("user", text),
            )

        plugin
            .getAIResponse(messages)
            .thenAccept { aiResponse ->
                if (aiResponse != null) {
                    plugin.askForPermission(
                        "<yellow>Following narrative response will be added to session" +
                            " history. Do you want to proceed?</yellow> \n\n $aiResponse",
                        onAccept = {
                            session.history.append(aiResponse)
                            var message = aiResponse
                            message = message.replace(Regex("\"([^\"]*)\""), "<yellow>\"$1\"</yellow>")
                            val formatted =
                                plugin.npcMessageService.formatMessage(
                                    message = message,
                                    name = "",
                                    formatColor = "<color:#e67e22>",
                                    formatColorSuffix = "</color:#e67e22>",
                                )
                            if (plugin.config.broadcastSessionEntries) {
                                session.players.forEach { player ->
                                    val ply = plugin.server.getPlayer(player)
                                    for (messagePart in formatted) {
                                        ply?.sendMessage(messagePart)
                                    }
                                }
                            }
                            session.history.append("\n\n")
                            autosaveCurrentSession()
                        },
                        onRefuse = {
                            plugin.logger.info("Narrative response was not added to session history. Rejected.")
                        },
                    )
                } else {
                    plugin.logger.warning("[ERROR] Failed to generate narrative response")
                }
            }.exceptionally { e ->
                plugin.logger.warning("[ERROR] Failed to process narrative: ${e.message}")
                null
            }
    }

    /** End the active session and persist it to disk. */
    fun endSession() {
        val session = current.getAndSet(null) ?: return
        session.endTime = plugin.timeService.getCurrentGameTime()

        val sessionId = currentSessionId
        if (sessionId != null) {
            val doc = sessionToDocument(sessionId, session)
            sessionStorage.saveSession(sessionId, doc)
            plugin.logger.info("Session ended and saved: $sessionId")
        }

        currentSessionId = null
    }

    private fun autosaveCurrentSession() {
        val session = current.get() ?: return
        val sessionId = currentSessionId ?: return

        if (System.currentTimeMillis() - lastSaveTime > 5000) {
            val doc = sessionToDocument(sessionId, session)
            sessionStorage.updateSession(sessionId, doc)
            lastSaveTime = System.currentTimeMillis()
            plugin.logger.info("Auto-saved active session: $sessionId")
        }
    }

    /** Persist and clear the current session. */
    fun shutdown() {
        current.getAndSet(null)?.let { session ->
            session.endTime = plugin.timeService.getCurrentGameTime()
            val sessionId = currentSessionId
            if (sessionId != null) {
                val doc = sessionToDocument(sessionId, session)
                sessionStorage.saveSession(sessionId, doc)
            }
        }
        currentSessionId = null
    }

    private fun sessionToDocument(
        sessionId: String,
        session: Session,
    ): SessionDocument =
        SessionDocument(
            sessionId = sessionId,
            startTime = session.startTime,
            endTime = session.endTime,
            players = session.players.toList(),
            history = session.history.toString(),
            active = session.endTime == null,
        )

    companion object {
        private var instance: SessionManager? = null

        @JvmStatic
        fun getInstance(
            plugin: Story,
            sessionStorage: SessionStorage,
        ): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(plugin, sessionStorage).also { instance = it }
            }

        @JvmStatic
        fun getInstance(plugin: Story): SessionManager =
            instance
                ?: throw IllegalStateException(
                    "SessionManager not initialized. Call getInstance(plugin, sessionStorage) first.",
                )
    }
}
