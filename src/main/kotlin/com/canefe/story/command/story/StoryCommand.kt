package com.canefe.story.command.story

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.command.base.CommandComponentUtils
import com.canefe.story.command.story.location.LocationCommand
import com.canefe.story.command.story.npc.NPCCommand
import com.canefe.story.command.story.quest.QuestCommand
import com.canefe.story.command.story.session.SessionCommand
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.PlayerArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.CommandExecutor
import kotlin.collections.get
import kotlin.compareTo
import kotlin.text.get

class StoryCommand(private val plugin: Story) : BaseCommand {
	override fun register() {
		CommandAPICommand("story")
			.withAliases("st", "sto")
			.withPermission("story.command")
			// executes help command too
			.executes(
				CommandExecutor { sender, _ ->
					val helpMessage =
						"""
						${listCommands()}
						""".trimIndent()
					sender.sendRaw(helpMessage)
				},
			).withSubcommand(getLocationCommand())
			.withSubcommand(getQuestCommand())
			.withSubcommand(getHelpCommand())
			.withSubcommand(getReloadCommand())
			.withSubcommand(getNPCCommand())
			.withSubcommand(getMessageCommand())
			.withSubcommand(getGMCommand())
			.withSubcommand(getSessionCommand())
			.withSubcommand(getTaskCommand())
			.register()
	}

	private fun getMessageCommand(): CommandAPICommand {
		return CommandAPICommand("message")
			.withAliases("msg", "m")
			.withPermission("story.command.message")
			.withArguments(TextArgument("context"))
			.withOptionalArguments(PlayerArgument("target"))
			.executes(
				CommandExecutor { sender, args ->
					val context = args[0] as String
					val targetPlayer = args["target"] as? org.bukkit.entity.Player

					// Create prompt for AI
					val prompt = mutableListOf<ConversationMessage>()

					// Find relevant lore related to the context
					val loreContexts = plugin.lorebookManager.findLoresByKeywords(context)
					val loreInfo =
						if (loreContexts.isNotEmpty()) {
							"Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
						} else {
							"No relevant lore found for the given context."
						}

					sender.sendSuccess(loreInfo)

					// Include relevant lore in the prompt
					val loreContext =
						if (loreContexts.isNotEmpty()) {
							"\n\nInclude these world lore elements in your writing:\n" +
								loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" }
						} else {
							""
						}

					// Find relevant locations mentioned in the prompt
					val locationKeywords =
						context
							.split(" ")
							.filter { it.length > 3 } // Only consider words with more than 3 characters
							.distinct()

					val relevantLocations = mutableListOf<String>()
					val locationContexts = mutableListOf<String>()

					// Check if any location names match our keywords
					locationKeywords.forEach { keyword ->
						plugin.locationManager.getAllLocations().forEach { location ->
							if (location.name.equals(keyword, ignoreCase = true) && location.context.isNotEmpty()) {
								relevantLocations.add(location.name)
								location.context.forEach { ctx ->
									locationContexts.add("${location.name}: $ctx")
								}
							}
						}
					}

					val locationInfo =
						if (relevantLocations.isNotEmpty()) {
							"Relevant locations found: ${relevantLocations.joinToString(", ")}"
						} else {
							"No relevant locations found for the given prompt."
						}

					// Find relevant NPCs mentioned in the question
					val words = context.split(Regex("\\s+|[,.?!;:\"]")).filter { it.isNotBlank() }
					val relevantNPCs = mutableListOf<String>()
					val npcContexts = mutableListOf<String>()

					// First check for exact word matches
					plugin.npcDataManager.getAllNPCNames().forEach { npcName ->
						// Simple case: check if any word in the question matches an NPC name
						if (words.any { word -> word.equals(npcName, ignoreCase = true) }) {
							relevantNPCs.add(npcName)
							addNpcContext(npcName, npcContexts)
						}
						// For multi-word NPCs or names with punctuation, check the full question
						else if (context.contains(npcName, ignoreCase = true)) {
							relevantNPCs.add(npcName)
							addNpcContext(npcName, npcContexts)
						}
					}

					if (relevantNPCs.isEmpty()) {
						sender.sendSuccess("No relevant NPCs found for your question.")
					} else {
						sender.sendSuccess("Relevant NPCs found: ${relevantNPCs.joinToString(", ")}")
					}

					prompt.add(
						ConversationMessage(
							"system",
							"""
                        You are a system message generator for a Minecraft RPG plugin.
                        Create a professional, visually appealing message based on the given context.

                        Guidelines:
                        - Use MiniMessage formatting tags (<#hexcolor>, <i>, <b> etc.)
                        - Create a short but impactful message (1 line)
						- Use prefix: <dark_gray>[<gold>Story</gold>]</dark_gray>
                        - Maintain a professional system announcement tone
                        - No emojis or excessive punctuation, system messages should be clear and concise.
						- Interpret the context fully, do not cut parts of the context.
     ${if (relevantNPCs.isNotEmpty()) {
								"""===CHARACTER INFORMATION===
      The question involves these characters: ${relevantNPCs.joinToString(", ")}
      ${npcContexts.joinToString("\n")}"""
							} else {
								""
							}}

     ${if (loreContexts.isNotEmpty()) {
								"""===LORE INFORMATION===
      $loreInfo"""
							} else {
								""
							}}

     ${if (relevantLocations.isNotEmpty()) {
								"""===LOCATION INFORMATION===
      The question involves these locations: ${relevantLocations.joinToString(", ")}
      ${locationContexts.joinToString("\n")}"""
							} else {
								""
							}}
                        Output only the formatted message, nothing else.
                        """,
						),
					)

					prompt.add(ConversationMessage("user", context))

					// Get AI response
					plugin
						.getAIResponse(prompt)
						.thenAccept { response ->
							if (response.isNullOrEmpty()) {
								sender.sendSuccess("Failed to generate message. Please try again.")
								return@thenAccept
							}

							// Send the message to target player or all online players
							if (targetPlayer != null) {
								targetPlayer.sendRaw(response)
								sender.sendSuccess("Message sent to ${targetPlayer.name}.")
							} else {
								plugin.server.onlinePlayers.forEach { player ->
									player.sendRaw(response)
								}
								// also send to console
								plugin.server.consoleSender.sendRaw(response)
								sender.sendSuccess("Message broadcast to all players.")
							}
						}.exceptionally { e ->
							sender.sendSuccess("Error generating message: ${e.message}")
							null
						}
				},
			)
	}

	private fun getGMCommand(): CommandAPICommand {
		return CommandAPICommand("gm")
			.withAliases("gamemaster", "ask")
			.withPermission("story.command.gm")
			.withArguments(GreedyStringArgument("question"))
			.executes(
				CommandExecutor { sender, args ->
					val question = args["question"] as String

					// Create prompt for AI
					val prompts = mutableListOf<ConversationMessage>()

					// Find relevant NPCs mentioned in the question
					val words = question.split(Regex("\\s+|[,.?!;:\"]")).filter { it.isNotBlank() }
					val relevantNPCs = mutableListOf<String>()
					val npcContexts = mutableListOf<String>()

					// First check for exact word matches
					plugin.npcDataManager.getAllNPCNames().forEach { npcName ->
						// Simple case: check if any word in the question matches an NPC name
						if (words.any { word -> word.equals(npcName, ignoreCase = true) }) {
							relevantNPCs.add(npcName)
							addNpcContext(npcName, npcContexts)
						}
						// For multi-word NPCs or names with punctuation, check the full question
						else if (question.contains(npcName, ignoreCase = true)) {
							relevantNPCs.add(npcName)
							addNpcContext(npcName, npcContexts)
						}
					}

					if (relevantNPCs.isEmpty()) {
						sender.sendSuccess("No relevant NPCs found for your question.")
					} else {
						sender.sendSuccess("Relevant NPCs found: ${relevantNPCs.joinToString(", ")}")
					}

					// Find relevant lore related to the question
					val loreContexts = plugin.lorebookManager.findLoresByKeywords(question)
					val loreInfo =
						if (loreContexts.isNotEmpty()) {
							loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" }
						} else {
							""
						}

					if (loreInfo.isEmpty()) {
						sender.sendSuccess("No relevant lore found for your question.")
					} else {
						sender.sendSuccess("Relevant lore found: ${loreContexts.joinToString(", ") { it.loreName }}")
					}

					// Find relevant locations mentioned in the question
					val relevantLocations = mutableListOf<String>()
					val locationContexts = mutableListOf<String>()

					plugin.locationManager.getAllLocations().forEach { location ->
						// Extract the meaningful location name
						val simpleName = location.name.substringAfterLast('/')

						// Only consider substantial location names
						if (simpleName.length > 3) {
							// Check for exact matches with word boundaries using regex
							val pattern = "\\b${Regex.escape(simpleName)}\\b"
							if (question.contains(Regex(pattern, RegexOption.IGNORE_CASE))) {
								relevantLocations.add(location.name)
								location.context.forEach { ctx ->
									locationContexts.add("${location.name}: $ctx")
								}
							}
						}

						// Special check for multi-part paths to prevent matching just "Fort"
						val pathParts = location.name.split("/")
						if (pathParts.size > 1) {
							// Check if the full location path is explicitly mentioned
							val fullPath = pathParts.joinToString(" ")
							if (question.contains(fullPath, ignoreCase = true)) {
								relevantLocations.add(location.name)
								location.context.forEach { ctx ->
									locationContexts.add("${location.name}: $ctx")
								}
							}
						}
					}

					val locationInfo =
						if (relevantLocations.isNotEmpty()) {
							"Relevant locations found: ${relevantLocations.joinToString(", ")}"
						} else {
							"No relevant locations found for the given question."
						}

					if (locationInfo.isEmpty()) {
						sender.sendSuccess("No relevant locations found for your question.")
					} else {
						sender.sendSuccess(locationInfo)
					}

					sender.sendSuccess("Processing your question about: '$question'")

					// Build the system prompt
					val systemPrompt =
						"""
     You are the Game Master for a Minecraft RPG server. Your job is to provide detailed, informative answers to player questions about the game world, characters, lore, and events.

     When responding:
     - Answer as a knowledgeable narrator of the world
     - Provide detailed explanations based on the available context
     - Use MiniMessage formatting for better readability (<gold>, <italic>, <dark_gray>, <#hexcolor>, etc.)
     - Format important names and places with <yellow> tags
     - Break longer responses into paragraphs for readability
     - When explaining character relationships or motivations, provide depth and nuance
     - Maintain the fantasy medieval setting's tone and atmosphere
	 - No emojis or excessive punctuation

     ${if (relevantNPCs.isNotEmpty()) {
							"""===CHARACTER INFORMATION===
      The question involves these characters: ${relevantNPCs.joinToString(", ")}
      ${npcContexts.joinToString("\n")}"""
						} else {
							""
						}}

     ${if (loreContexts.isNotEmpty()) {
							"""===LORE INFORMATION===
      $loreInfo"""
						} else {
							""
						}}

     ${if (relevantLocations.isNotEmpty()) {
							"""===LOCATION INFORMATION===
      The question involves these locations: ${relevantLocations.joinToString(", ")}
      ${locationContexts.joinToString("\n")}"""
						} else {
							""
						}}
     """.trimIndent()

					prompts.add(ConversationMessage("system", systemPrompt))
					prompts.add(ConversationMessage("user", question))

					// Get AI response
					plugin
						.getAIResponse(prompts)
						.thenAccept { response ->
							if (response.isNullOrEmpty()) {
								sender.sendSuccess("I couldn't formulate a response to your question. Please try asking differently.")
								return@thenAccept
							}

							// Format the response with a header
							val formattedResponse = "<dark_gray>[<gold>Game Master</gold>]</dark_gray> $response"

							// Split the response into multiple messages to avoid chat limitations
							val messageParts = splitMessageForMinecraft(formattedResponse)

							// Send the response to appropriate recipients
							plugin.askForPermission(
								"Should we broadcast the following response to all players? \n\n${messageParts.joinToString("\n")}",
								onAccept = {
									messageParts.forEach { part ->
										plugin.server.onlinePlayers.forEach { player ->
											player.sendRaw(part)
										}
										plugin.server.consoleSender.sendRaw(part)
									}
									sender.sendSuccess("Response broadcast to all players.")
								},
								onRefuse = {
									sender.sendSuccess("Response broadcast cancelled.")
								},
							)
						}.exceptionally { e ->
							sender.sendSuccess("Error processing your question: ${e.message}")
							null
						}
				},
			)
	}

	private fun getTaskCommand(): CommandAPICommand = CommandAPICommand("task")
		.withAliases("tasks")
		.withPermission("story.command.task")
		.withUsage(
			"<gray>Usage: /story task <accept|deny|list> [taskId]</gray>",
		)
		.withSubcommand(getTaskAcceptCommand())
		.withSubcommand(getTaskDenyCommand())
		.withSubcommand(getTaskListCommand())

	private fun getTaskAcceptCommand(): CommandAPICommand = CommandAPICommand("accept")
		.withPermission("story.task.respond")
		.withArguments(dev.jorel.commandapi.arguments.IntegerArgument("taskId"))
		.executes(
			CommandExecutor { sender, args ->
				val taskId = args["taskId"] as Int
				val success = plugin.taskManager.acceptTask(taskId, sender)

				if (success) {
					// sender.sendSuccess("Task #$taskId accepted.")
				} else {
					sender.sendSuccess("Task #$taskId not found or already completed.")
				}
			},
		)

	private fun getTaskDenyCommand(): CommandAPICommand = CommandAPICommand("deny")
		.withAliases("refuse")
		.withPermission("story.task.respond")
		.withArguments(dev.jorel.commandapi.arguments.IntegerArgument("taskId"))
		.executes(
			CommandExecutor { sender, args ->
				val taskId = args["taskId"] as Int
				val success = plugin.taskManager.refuseTask(taskId, sender)

				if (success) {
					sender.sendSuccess("Task #$taskId refused.")
				} else {
					sender.sendSuccess("Task #$taskId not found or already completed.")
				}
			},
		)

	private fun getTaskListCommand(): CommandAPICommand {
		return CommandAPICommand("list")
			.withPermission("story.task.list")
			.executes(
				CommandExecutor { sender, _ ->
					val tasks = plugin.taskManager.getActiveTasks()

					if (tasks.isEmpty()) {
						sender.sendSuccess("No active tasks found.")
						return@CommandExecutor
					}

					sender.sendRaw("<yellow>=== Active Tasks ===</yellow>")
					tasks.forEach { task ->
						// Calculate timeout information
						val timeoutInfo = if (task.timeoutAt > 0) {
							val remainingSeconds = (task.timeoutAt - System.currentTimeMillis()) / 1000
							if (remainingSeconds <= 0) {
								" <red>(Expiring...)</red>"
							} else {
								val minutes = remainingSeconds / 60
								val seconds = remainingSeconds % 60
								" <gray>(${minutes}m ${seconds}s remaining)</gray>"
							}
						} else {
							" <gray>(No timeout)</gray>"
						}

						sender.sendRaw("<white>Task #${task.id}</white>: ${task.description}$timeoutInfo")

						// Add clickable buttons
						val mm = plugin.miniMessage
						val acceptButton = CommandComponentUtils.createButton(
							mm,
							"Accept",
							"green",
							"run_command",
							"/story task accept ${task.id}",
							"Accept this task",
						)

						val refuseButton = CommandComponentUtils.createButton(
							mm,
							"Refuse",
							"red",
							"run_command",
							"/story task deny ${task.id}",
							"Refuse this task",
						)

						val buttons = CommandComponentUtils.combineComponentsWithSeparator(
							mm,
							listOf(acceptButton, refuseButton),
							" ",
						)

						sender.sendMessage(buttons)
						sender.sendRaw(" ")
					}
				},
			)
	}

	// Helper function to add NPC context
	private fun addNpcContext(npcName: String, npcContexts: MutableList<String>) {
		val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
		val lastFewMemories = npcContext?.getMemoriesForPrompt(plugin.timeService, 5)
		if (npcContext != null) {
			npcContexts.add("$npcName's context: ${npcContext.context} ")
			if (lastFewMemories != null && lastFewMemories.isNotEmpty()) {
				npcContexts.add("$npcName's recent memories: $lastFewMemories")
			}
		}
	}

	// Helper function to split messages
	private fun splitMessageForMinecraft(message: String): List<String> {
		val parts = mutableListOf<String>()
		val maxLength = 250 // Conservative limit to account for formatting

		// Check if message needs splitting
		if (message.length <= maxLength) {
			return listOf(message)
		}

		// Split by paragraphs first
		val paragraphs = message.split("\n\n")

		// Process each paragraph
		for (paragraph in paragraphs) {
			if (paragraph.length <= maxLength) {
				parts.add(paragraph)
			} else {
				// For longer paragraphs, split by sentences
				val sentences = paragraph.split(". ")
				var currentPart = ""

				for (sentence in sentences) {
					val potentialPart = if (currentPart.isEmpty()) sentence else "$currentPart. $sentence"

					if (potentialPart.length <= maxLength) {
						currentPart = potentialPart
					} else {
						// Add current accumulated part if not empty
						if (currentPart.isNotEmpty()) {
							parts.add(currentPart)
						}
						// Start new part with this sentence
						currentPart = sentence
					}
				}

				// Add any remaining content
				if (currentPart.isNotEmpty()) {
					parts.add(currentPart)
				}
			}
		}

		// Add prefix to all parts except the first one
		for (i in 1 until parts.size) {
			parts[i] = "<dark_gray>→</dark_gray> ${parts[i]}"
		}

		return parts
	}

	private fun listCommands(): String = """<yellow>=========================</yellow>
		<yellow>Story Plugin Commands</yellow>
		<yellow>=========================</yellow>
		<gold>/story</gold> help <gray><italic>- Show this help message</italic></gray>
		<gold>/story</gold> reload <gray><italic>- Reload the plugin configuration</italic></gray>
		<gold>/story</gold> location <gray><italic>- Manage locations</italic></gray>
		<gold>/story</gold> npc <gray><italic>- Manage NPCs</italic></gray>
		<gold>/conv</gold> list <gray><italic>- List all conversations and control panel</italic></gray>
		<gold>/story</gold> gm <question> [broadcast] <gray><italic>- Ask the Game Master a question about the world</italic></gray>
		<gold>/story</gold> task <gray><italic>- Manage AI permission requests</italic></gray>
	""".trimIndent()

	private fun getHelpCommand(): CommandAPICommand = CommandAPICommand("help")
		.withPermission("story.command.help")
		.executes(
			CommandExecutor { sender, _ ->
				sender.sendRaw(listCommands())
			},
		)

	private fun getReloadCommand(): CommandAPICommand = CommandAPICommand("reload")
		.withPermission("story.command.reload")
		.executes(
			CommandExecutor { sender, _ ->
				plugin.reloadConfig()
				plugin.configService.reload()
				sender.sendSuccess("Plugin reloaded successfully.")
			},
		)

	private fun getLocationCommand(): CommandAPICommand = LocationCommand(plugin).getCommand()

	private fun getQuestCommand(): CommandAPICommand = QuestCommand(plugin).getCommand()

	private fun getNPCCommand(): CommandAPICommand = NPCCommand(plugin).getCommand()
	private fun getSessionCommand(): CommandAPICommand = SessionCommand(plugin).getCommand()
}
