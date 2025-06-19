package com.canefe.story.session

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * Manager to track gameplay sessions and persist them to YAML files.
 */
class SessionManager private constructor(private val plugin: Story) {
	private val sessionFolder: File =
		File(plugin.dataFolder, "sessions").apply { if (!exists()) mkdirs() }

	private val current = AtomicReference<Session?>(null)
	private var currentSessionFile: File? = null
	private var lastSaveTime: Long = 0
	private var lastFileModTime: Long = 0

	init {
		// Start autosave task (every 2 minutes)
		plugin.server.scheduler.runTaskTimerAsynchronously(
			plugin,
			Runnable {
				checkForExternalChanges()
				autosaveCurrentSession()
			},
			20 * 30,
			20 * 120,
		) // 30s initial delay, 2min interval
	}

	fun load() {
		checkForExternalChanges()
		autosaveCurrentSession()
	}

	/** Start a new session if none is active. */
	fun startSession() {
		if (current.get() != null) return

		val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss"))
		val session = Session(plugin.timeService.getCurrentGameTime())
		current.set(session)

		// Create initial session file
		currentSessionFile = File(sessionFolder, "session-$timestamp.yml")
		saveSessionToFile(session, currentSessionFile!!)

		plugin.logger.info("Started new session: ${currentSessionFile!!.name}")
	}

	/** Add a player name to the active session. */
	fun addPlayer(name: String) {
		current.get()?.players?.add(name)
		// Save changes immediately
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
		val keywords = text.split(" ")
			.filter { it.length > 3 }
			.map { it.lowercase().trim(',', '.', '?', '!', '\'', '"') }
			.distinct()

		// Check for location references
		val mentionedLocations = plugin.locationManager.getAllLocations().filter { location ->
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

		// Check for NPC references
		val mentionedNPCs = plugin.npcDataManager.getAllNPCNames().filter { npcName ->
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

		// Current context
		if (session.history.isNotEmpty()) {
			contextBuilder.append("CURRENT SESSION HISTORY:\n")
			contextBuilder.append(session.history.toString())
			contextBuilder.append("\n")
		}
		// Create AI messages
		val messages = mutableListOf(
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

		// Get AI response
		plugin.getAIResponse(messages).thenAccept { aiResponse ->
			if (aiResponse != null) {
				plugin.askForPermission(
					"<yellow>Following narrative response will be added to session" +
						" history. Do you want to proceed?</yellow> \n\n $aiResponse",
					onAccept = {
						// Append AI response to session history
						session.history.append(aiResponse)
						var message = aiResponse
						// use regex to wrap Quotes in <yellow> tags
						message = message.replace(Regex("\"([^\"]*)\""), "<yellow>\"$1\"</yellow>")
						val formatted = plugin.npcMessageService.formatMessage(
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

		// Save the final state using the existing file
		if (currentSessionFile != null) {
			saveSessionToFile(session, currentSessionFile!!)
			plugin.logger.info("Session ended and saved to: ${currentSessionFile!!.name}")
		} else {
			saveSession(session)
		}

		currentSessionFile = null
	}

	// Save current session without ending it
	private fun autosaveCurrentSession() {
		val session = current.get() ?: return
		val file = currentSessionFile ?: return

		// Only save if there were actual changes since last save
		if (System.currentTimeMillis() - lastSaveTime > 5000) { // Minimum 5s between saves
			saveSessionToFile(session, file)
			lastSaveTime = System.currentTimeMillis()
			plugin.logger.info("Auto-saved active session to: ${file.name}")
		}
	}

	// Check if the session file was modified externally and load changes
	private fun checkForExternalChanges() {
		val file = currentSessionFile ?: return
		if (!file.exists()) return

		val modTime = file.lastModified()
		if (modTime > lastFileModTime && modTime > lastSaveTime + 2000) {
			// File was modified externally (allow 2s buffer to avoid detecting our own saves)
			plugin.logger.info("Detected external changes to session file - reloading...")
			loadSessionFromFile(file)
		}
	}

	// Load session from file after external changes
	private fun loadSessionFromFile(file: File) {
		try {
			val config = YamlConfiguration.loadConfiguration(file)
			val session = current.get() ?: return

			// Update player list
			val playerList = config.getStringList("players")
			session.players.clear()
			session.players.addAll(playerList)

			// Update history if changed externally
			val fileHistory = config.getString("history") ?: ""
			if (fileHistory != session.history.toString()) {
				session.history.clear()
				session.history.append(fileHistory)
			}

			lastFileModTime = file.lastModified()
			plugin.logger.info("Session updated from modified file")
		} catch (e: Exception) {
			plugin.logger.warning("Failed to load session from file: ${e.message}")
		}
	}

	private fun saveSessionToFile(session: Session, file: File) {
		val config = YamlConfiguration()
		config.set("startTime", session.startTime)
		session.endTime?.let { config.set("endTime", it) }
		config.set("players", session.players.toList())
		config.set("history", session.history.toString())
		config.set("active", session.endTime == null) // Flag to identify active sessions
		config.save(file)
		lastFileModTime = file.lastModified()
	}

	private fun saveSession(session: Session) {
		val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss"))
		val file = File(sessionFolder, "session-$timestamp.yml")
		saveSessionToFile(session, file)
	}

	/** Persist and clear the current session. */
	fun shutdown() {
		current.getAndSet(null)?.let { session ->
			session.endTime = plugin.timeService.getCurrentGameTime()
			if (currentSessionFile != null) {
				saveSessionToFile(session, currentSessionFile!!)
			} else {
				saveSession(session)
			}
		}
		currentSessionFile = null
	}

	companion object {
		private var instance: SessionManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): SessionManager =
			instance ?: synchronized(this) { instance ?: SessionManager(plugin).also { instance = it } }
	}
}
