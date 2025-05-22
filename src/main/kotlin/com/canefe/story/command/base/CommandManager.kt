package com.canefe.story.command.base

import com.canefe.story.Story
import com.canefe.story.command.conversation.ConvCommand
import com.canefe.story.command.faction.FactionCommand
import com.canefe.story.command.faction.SettlementCommand
import com.canefe.story.command.story.StoryCommand
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.relationship.Relationship
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIBukkitConfig
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.CommandArguments
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.FollowTrait
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.mcmonkey.sentinel.SentinelTrait
import java.util.UUID
import kotlin.compareTo

/**
 * Centralized command manager that registers and manages all plugin commands.
 */
class CommandManager(
	private val plugin: Story,
) {
	private val commandExecutors = mutableMapOf<String, CommandExecutor>()

	/**
	 * Called during plugin load to initialize CommandAPI
	 */
	fun onLoad() {
		CommandAPI.onLoad(CommandAPIBukkitConfig(plugin).silentLogs(true))
	}

	/**
	 * Called during plugin enable to register all commands
	 */
	fun registerCommands() {
		// Register CommandAPI commands
		registerCommandAPICommands()
	}

	/**
	 * Called during plugin disable to clean up commands
	 */
	fun onDisable() {
		CommandAPI.onDisable()
	}

	private fun registerCommandAPICommands() {
		// Register CommandAPI
		CommandAPI.onEnable()

		// Register structured commands
		ConvCommand(plugin).register()
		StoryCommand(plugin).register()
		FactionCommand(plugin, plugin.factionManager).registerCommands()
		SettlementCommand().register()

		// Register simpler commands
		registerSimpleCommands()
	}

	private fun registerSimpleCommands() {
		// resetcitizensnavigation
		CommandAPICommand("resetcitizensnavigation")
			.withPermission("storymaker.npc.navigation")
			.executes(
				CommandExecutor { sender, _ ->
					val npcRegistry = CitizensAPI.getNPCRegistry()
					for (npc in npcRegistry) {
						npc.navigator.cancelNavigation()
						npc.getOrAddTrait(SentinelTrait::class.java).guarding = null
						npc.getOrAddTrait(FollowTrait::class.java).follow(null)
					}
					sender.sendSuccess("All NPC navigation has been reset.")
				},
			).register()

		// Register simple commands using CommandAPI
		CommandAPICommand("togglechat")
			.withPermission("storymaker.chat.toggle")
			.withOptionalArguments(PlayerArgument("target"))
			.executesPlayer(
				PlayerCommandExecutor { player, args ->
					val target = args.getOptional("target").orElse(player) as? Player

					plugin.playerManager.togglePlayerInteractions(player, target)
				},
			).executes(
				dev.jorel.commandapi.executors.CommandExecutor { _, args ->
					val target = args.get("target") as? Player

					plugin.playerManager.togglePlayerInteractions(null, target)
				},
			).register()

		CommandAPICommand("maketalk")
			.withPermission("storymaker.chat.toggle")
			.withArguments(
				dev.jorel.commandapi.arguments
					.GreedyStringArgument("npc"),
			).executes(
				dev.jorel.commandapi.executors.CommandExecutor { sender, args ->
					val npc = args.get("npc") as String

					// Fetch NPC conversation
					val conversation = plugin.conversationManager.getConversation(npc)
					if (conversation == null) {
						val errorMessage =
							plugin.miniMessage.deserialize(
								"<red>No active conversation found for NPC '$npc'.",
							)
						sender.sendMessage(errorMessage)
						throw CommandAPI.failWithString("No active conversation found for NPC '$npc'.")
					}

					val successMessage = plugin.miniMessage.deserialize("<green>NPC '$npc' is now talking.</green>")
					sender.sendMessage(successMessage)
					// Generate NPC responses
					plugin.conversationManager.generateResponses(conversation, npc)
				},
			).register()

		// togglegpt
		CommandAPICommand("togglegpt")
			.withPermission("storymaker.chat.toggle")
			.executes(
				dev.jorel.commandapi.executors.CommandExecutor { sender, _ ->
					plugin.config.chatEnabled = !plugin.config.chatEnabled
					if (plugin.config.chatEnabled) {
						sender.sendSuccess("Chat with NPCs enabled.")
					} else {
						sender.sendError("Chat with NPCs disabled.")
					}
					plugin.config.save()
				},
			).register()

		// toggleradiant
		CommandAPICommand("toggleradiant")
			.withPermission("storymaker.chat.toggle")
			.executes(
				dev.jorel.commandapi.executors.CommandExecutor { sender, _ ->
					plugin.config.radiantEnabled = !plugin.config.radiantEnabled
					if (plugin.config.radiantEnabled) {
						sender.sendSuccess("Radiant chat enabled.")
					} else {
						sender.sendError("Radiant chat disabled.")
					}
					plugin.config.save()
				},
			).register()

		// toggleschedule [<random_pathing>] toggle schedules or random pathing
		CommandAPICommand("toggleschedule")
			.withPermission("storymaker.npc.schedule")
			.withOptionalArguments(BooleanArgument("random_pathing"))
			.executes(
				CommandExecutor { sender, args ->
					val randomPathing = args.getOptional("random_pathing").orElse(null) as? Boolean
					if (randomPathing != null) {
						plugin.config.randomPathingEnabled = randomPathing
						if (randomPathing) {
							sender.sendSuccess("Random pathing enabled.")
						} else {
							sender.sendError("Random pathing disabled.")
						}
					} else {
						plugin.config.scheduleEnabled = !plugin.config.scheduleEnabled
						if (plugin.config.scheduleEnabled) {
							sender.sendSuccess("Schedules enabled.")
						} else {
							sender.sendError("Schedules disabled.")
						}
					}
					plugin.config.save()
				},
			).register()

		// npctalk
		CommandAPICommand("npctalk")
			.withPermission("storymaker.npc.talk")
			.withArguments(IntegerArgument("npc_id"))
			.withArguments(IntegerArgument("npc_id_target"))
			.withArguments(GreedyStringArgument("message"))
			.executesPlayer(
				PlayerCommandExecutor { player: Player, args: CommandArguments ->
					val npcId = args["npc_id"] as Int
					val npcIdTarget = args["npc_id_target"] as Int
					val message = args["message"] as String
					val npc: NPC? = CitizensAPI.getNPCRegistry().getById(npcId)
					val target: NPC? = CitizensAPI.getNPCRegistry().getById(npcIdTarget)
					if (npc == null || target == null) {
						player.sendError("NPC not found.")
						return@PlayerCommandExecutor
					}
					plugin.npcManager.walkToNPC(npc, target, message)
				},
			).register()

		// npcply
		CommandAPICommand("npcply")
			.withPermission("storymaker.npc.talk")
			.withArguments(IntegerArgument("npc_id"))
			.withArguments(PlayerArgument("player"))
			.withArguments(GreedyStringArgument("message"))
			.executesPlayer(
				PlayerCommandExecutor { player: Player, args: CommandArguments ->
					val npcId = args["npc_id"] as Int
					val target = args["player"] as Player
					val message = args["message"] as String
					val npc = CitizensAPI.getNPCRegistry().getById(npcId)
					if (npc == null) {
						player.sendError("NPC not found.")
						return@PlayerCommandExecutor
					}
					plugin.npcManager.eventGoToPlayerAndTalk(npc, target, message, null)
				},
			).register()

		// Command to generate memories for NPCs dynamically
		CommandAPICommand("npcmemory")
			.withPermission("storymaker.npc.memory")
			.withArguments(TextArgument("npc"))
			.withArguments(
				StringArgument("type").replaceSuggestions { info, builder ->
					val suggestions = listOf("event", "conversation", "observation", "experience")
					suggestions.forEach { builder.suggest(it) }
					builder.buildFuture()
				},
			).withArguments(GreedyStringArgument("context"))
			.executes(
				CommandExecutor { sender, args ->
					val npcName = args.get("npc") as String
					val type = args.get("type") as String
					val context = args.get("context") as String

					// Check if NPC exists first
					val npcData = plugin.npcDataManager.getNPCData(npcName)
					if (npcData == null) {
						sender.sendError("NPC $npcName not found. Please create the NPC first.")
						return@CommandExecutor
					}

					sender.sendInfo("Creating memory for <yellow>$npcName</yellow> based on: <italic>$context</italic>")

					plugin.npcResponseService
						.generateNPCMemory(npcName, type, context)
						.thenAccept { memory ->
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									if (memory != null) {
										sender.sendSuccess("Memory created for <yellow>$npcName</yellow>!")
										sender.sendInfo(
											"Memory preview: <yellow>${
												if (memory.content.length > 50) {
													memory.content.substring(0, 50) + "..."
												} else {
													memory.content
												}
											}</yellow>",
										)
									} else {
										sender.sendError("Failed to create memory for $npcName")
									}
								},
							)
						}.exceptionally { e ->
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									sender.sendError("Failed to create memory: ${e.message}")
								},
							)
							null
						}
				},
			).register()

		// npcinit
		CommandAPICommand("npcinit")
			.withPermission("storymaker.npc.init")
			.withArguments(StringArgument("location"))
			.withArguments(TextArgument("npc"))
			.withOptionalArguments(GreedyStringArgument("prompt"))
			.executes(
				CommandExecutor { sender, args: CommandArguments ->
					val npcName = args["npc"] as String
					val location = args["location"] as String
					val prompt = args.getOrDefault("prompt", "") as String

					val npcContext =
						plugin.npcContextGenerator.getOrCreateContextForNPC(npcName) ?: run {
							sender.sendError("NPC context not found. Please create the NPC first.")
							return@CommandExecutor
						}

					val storyLocation =
						plugin.locationManager.getLocation(location) ?: run {
							sender.sendError("Location not found. Please create the location first.")
							return@CommandExecutor
						}

					// Set the Location for the NPC
					val npcData =
						NPCData(
							npcName,
							npcContext.role,
							storyLocation,
							npcContext.context,
						)

					plugin.npcDataManager.saveNPCData(npcName, npcData)

					if (prompt.isNotEmpty()) {
						// Inform player we're generating context
						sender.sendInfo(
							"Generating AI context for NPC <yellow>$npcName</yellow> based on: <italic>$prompt</italic>",
						)

						// Create a system message to instruct the AI
						val messages: MutableList<ConversationMessage> = ArrayList()

						// Add General Context and Location context
						messages.add(
							ConversationMessage(
								"system",
								plugin.npcContextGenerator
									.getGeneralContexts()
									.joinToString("\n"),
							),
						)

						messages.add(
							ConversationMessage(
								"system",
								storyLocation.getContextForPrompt(plugin.locationManager),
							),
						)

						// Find relevant lore related to the context
						val loreContexts = plugin.lorebookManager.findLoresByKeywords(prompt)
						val loreInfo =
							if (loreContexts.isNotEmpty()) {
								"Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
							} else {
								"No relevant lore found for the given context."
							}

						sender.sendSuccess(loreInfo)

						// Include relevant lore in the prompt
						messages.add(
							ConversationMessage(
								"system",
								"Include these world lore elements in your writing:\n" +
									loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" },
							),
						)

						messages.add(
							ConversationMessage(
								"system",
								"""
									Generate detailed RPG character information for an NPC named $npcName in the location $location.
									Return ONLY a valid JSON object with these fields:
									1. "context": Background story, personality, motivations, and role in society
									2. "appearance": Detailed physical description including clothing, notable features

									Make the character interesting with clear motivations, quirks, and depth.
									The JSON must be properly formatted with quotes escaped.
								 """,
							),
						)

						// Add the user prompt
						messages.add(ConversationMessage("user", prompt))

						// Use CompletableFuture API instead of manual task scheduling
						plugin
							.getAIResponse(messages)
							.thenAccept { response ->
								// Return to the main thread to access Bukkit API
								Bukkit.getScheduler().runTask(
									plugin,
									Runnable {
										if (response == null) {
											sender.sendInfo("Failed to generate NPC data for $npcName.")
											return@Runnable
										}

										try {
											// Extract the JSON object from the response
											val jsonContent = extractJsonFromString(response)

											// Use Gson instead of org.json.JSONObject
											val gson = com.google.gson.Gson()
											val npcInfo = gson.fromJson(jsonContent, NPCInfo::class.java)

											// Get context and appearance from the parsed object
											val context = npcInfo.context ?: ""
											val appearance = npcInfo.appearance ?: ""

											if (context.isEmpty() || appearance.isEmpty()) {
												sender.sendError("Failed to parse AI response. Using default values.")
												return@Runnable
											}

											// add npcContext.context before 'response'
											val contextWithNPCContext = "${npcContext.context} $context"

											val npcData =
												NPCData(
													npcName,
													npcContext.role,
													storyLocation,
													contextWithNPCContext,
												)

											npcData.appearance = appearance

											plugin.npcDataManager.saveNPCData(npcName, npcData)
											sender.sendSuccess("AI-generated profile for <yellow>$npcName</yellow> created!")
											sender.sendInfo("Role: <yellow>${npcContext.role}</yellow>")
											sender.sendInfo(
												"Context summary: <yellow>${if (context.length > 50) {
													context.substring(0, 50) + "..."
												} else {
													context
												}}</yellow>",
											)
											sender.sendInfo(
												"Appearance: <yellow>${if (appearance.length > 50) {
													appearance.substring(0, 50) + "..."
												} else {
													appearance
												}}</yellow>",
											)
										} catch (e: Exception) {
											sender.sendError("Failed to generate AI context: ${e.message}. Using default values.")
											plugin.logger.warning("Error parsing NPC data from AI: ${e.message}")
											e.printStackTrace()
										}
									},
								)
							}.exceptionally { e ->
								Bukkit.getScheduler().runTask(
									plugin,
									Runnable {
										sender.sendError("Failed to generate AI context: ${e.message}. Using default values.")
									},
								)
								null
							}
					}
				},
			).register()

		fun talkAsNPC(
			player: Player,
			npcUniqueId: UUID,
			message: String,
		) {
			// Get Current NPC
			var currentNPC = npcUniqueId

			if (currentNPC == null) {
				player.sendError("Please select an NPC first.")
				throw CommandAPI.failWithString("You are not in a conversation with any NPC.")
			}
			// Check if NPC exists
			val npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUniqueId)
			if (npc == null) {
				player.sendError("NPC not found.")
				throw CommandAPI.failWithString("NPC not found.")
			}

			val npcName = npc.name
			val chatRadius = plugin.config.chatRadius
			val isImpersonated = plugin.disguiseManager.isNPCBeingImpersonated(npc)
			val impersonator = plugin.disguiseManager.getDisguisedPlayer(npc)
			val conversation =
				plugin.conversationManager.getConversation(npcName) ?: run {
					// create new conversation with nearby NPCs and players
					var nearbyNPCs = plugin.getNearbyNPCs(npc, chatRadius)
					var players = plugin.getNearbyPlayers(npc, chatRadius)

					if (isImpersonated && impersonator != null) {
						nearbyNPCs = plugin.getNearbyNPCs(impersonator, chatRadius)
						players = plugin.getNearbyPlayers(impersonator, chatRadius)
					}

					// remove players that have their chat disabled
					players = players.filterNot { plugin.playerManager.isPlayerDisabled(it) }

					// Add the NPC to the list of nearby NPCs
					nearbyNPCs = nearbyNPCs + listOf(npc)

					// Check if any nearby NPCs are already in a conversation
					val existingConversation =
						nearbyNPCs
							.mapNotNull { plugin.conversationManager.getConversation(it.name) }
							.firstOrNull()

					// Check if any nearby players are already in a conversation
					val playerConversation =
						players
							.flatMap { player ->
								plugin.conversationManager
									.getAllActiveConversations()
									.filter { conv -> conv.players?.contains(player.uniqueId) == true }
							}.firstOrNull()

					// Use existing conversation if available
					if (existingConversation != null) {
						// Add this NPC to the existing conversation if not already included
						if (existingConversation.npcs?.contains(npc) != true) {
							existingConversation.addNPC(npc)
						}

						// Add any players not already in the conversation
						players.forEach { p ->
							if (existingConversation.players?.contains(p.uniqueId) != true) {
								existingConversation.addPlayer(p)
							}
						}

						plugin.conversationManager.handleHolograms(existingConversation, npc.name)
						return@run existingConversation
					} else if (playerConversation != null) {
						// Add this NPC to the player's existing conversation
						if (!playerConversation.npcs.contains(npc)) {
							playerConversation.addNPC(npc)
						}

						plugin.conversationManager.handleHolograms(playerConversation, npc.name)
						return@run playerConversation
					}

					if (!(players.isNotEmpty() || nearbyNPCs.size > 1)) {
						player.sendError("No players or NPCs nearby to start a conversation.")
						return
					}

					val newConversationFuture = plugin.conversationManager.startConversation(nearbyNPCs)

					newConversationFuture.thenAccept { newConv ->
						plugin.conversationManager.handleHolograms(newConv, npc.name)

						for (p in players) {
							newConv.addPlayer(p)
						}
					}

					newConversationFuture.join()
				}

			// Show holograms for the NPCs
			plugin.conversationManager.handleHolograms(conversation, npc.name)
			val shouldStream = plugin.config.streamMessages
			val npcContext =
				plugin.npcContextGenerator.getOrCreateContextForNPC(npc.name) ?: run {
					player.sendError("NPC context not found. Please create the NPC first.")
					return
				}

			// Get only the messages from the conversation for context
			val recentMessages =
				conversation.history
					.map { it.content }

			// Prepare response context with limited messages
			var responseContext =
				listOf(
					"====CURRENT CONVERSATION====\n" +
						recentMessages.joinToString("\n") +
						"\n=========================\n" +
						"This is an active conversation and you are talking to multiple characters: ${conversation.players?.joinToString(
							", ",
						) { Bukkit.getPlayer(it)?.name?.let { name -> EssentialsUtils.getNickname(name) } ?: "" }}. " +
						conversation.npcNames.joinToString("\n") +
						"\n===APPEARANCES===\n" +
						conversation.npcs.joinToString("\n") { npc ->
							val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npc.name)
							"${npc.name}: ${npcContext?.appearance ?: "No appearance information available."}"
						} +
						// We treat players as NPCs for this purpose
						conversation.players?.joinToString("\n") { playerId ->
							val player = Bukkit.getPlayer(playerId)
							if (player == null) {
								// skip this player
								return@joinToString ""
							}
							val playerName = player.name
							val nickname = EssentialsUtils.getNickname(playerName)
							val playerContext = plugin.npcContextGenerator.getOrCreateContextForNPC(nickname)
							"$nickname: ${playerContext?.appearance ?: "No appearance information available."}"
						} +
						"\n=========================",
				)

			// Add relationship context with clear section header
			val relationships = plugin.relationshipManager.getAllRelationships(npc.name)
			if (relationships.isNotEmpty()) {
				val relationshipContext = buildRelationshipContext(relationships, conversation)
				if (relationshipContext.isNotEmpty()) {
					responseContext = responseContext + "===RELATIONSHIPS===\n$relationshipContext"
				}
			}

			// based on message, use npcresponseservice to generate a response
			responseContext =
				responseContext +
				"[INSTRUCTION] This message is a draft of what $npcName is should say. Rewrite it into a fully fleshed-out line that reflects $npcName’s personality, tone, and the context. The message is as follows: $message"
			plugin.npcResponseService.generateNPCResponse(npc, responseContext, false).thenApply { response ->

				if (!shouldStream) {
					plugin.npcMessageService.broadcastNPCMessage(
						message = response,
						npc = npc,
						npcContext = npcContext,
					)
					return@thenApply
				}

				val typingSpeed = 4

				conversation.addNPCMessage(npc, response)

				// First, start the typing animation
				plugin.typingSessionManager.startTyping(
					npc = npc,
					fullText = response,
					typingSpeed = typingSpeed,
					radius = plugin.config.chatRadius,
					messageFormat = "<npc_typing><npc_text>",
				)

				// Then use streamMessage instead of regular broadcast
				plugin.npcMessageService.broadcastNPCStreamMessage(
					message = response,
					npc = npc,
					npcContext = npcContext,
				)

				// Finally, after typing completes, send the regular message
				val delay = (response.length / (typingSpeed * 10) + 1).toLong()
				Bukkit.getScheduler().runTaskLater(
					plugin,
					Runnable {
						// Clean up holograms
						plugin.conversationManager.cleanupHolograms(conversation)
						plugin.typingSessionManager.stopTyping(npc.uniqueId)

						// Send the final message
						plugin.npcMessageService.broadcastNPCMessage(
							message = response,
							npc = npc,
							npcContext = npcContext,
						)
					},
					delay * 20, // Convert to ticks (20 ticks = 1 second)
				)
			}
		}

		// g command
		CommandAPICommand("g")
			.withPermission("storymaker.chat.toggle")
			.withArguments(
				GreedyStringArgument("message"),
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val message = args.get("message") as String
					var currentNPC = plugin.playerManager.getCurrentNPC(player.uniqueId)

					if (currentNPC == null) {
						player.sendError("Please select an NPC first.")
						return@PlayerCommandExecutor
					}

					talkAsNPC(player, currentNPC, message)
				},
			).register()

		// h command
		CommandAPICommand("h")
			.withPermission("storymaker.chat.toggle")
			.withArguments(
				GreedyStringArgument("message"),
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val message = args.get("message") as String
					val imitatedNPC = plugin.disguiseManager.getImitatedNPC(player)

					if (imitatedNPC == null) {
						player.sendError("You are not imitating any NPC.")
						return@PlayerCommandExecutor
					}

					talkAsNPC(player, imitatedNPC.uniqueId, message)
				},
			).register()

		// setcurnpc
		CommandAPICommand("setcurnpc")
			.withPermission("storymaker.chat.toggle")
			.withOptionalArguments(
				TextArgument("npc"),
			).withOptionalArguments(
				IntegerArgument("npc_id"),
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val npc = args.getOptional("npc").orElse(null) as? String
					// integer
					val npcId = args.getOptional("npc_id").orElse(null) as? Int

					// Check if NPC is in front of us first.
					val player = player as Player
					val target = player.getTargetEntity(15) // Get entity player is looking at within 15 blocks
					if (target != null && CitizensAPI.getNPCRegistry().isNPC(target)) {
						val npc = CitizensAPI.getNPCRegistry().getNPC(target)
						plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
						player.sendSuccess("Current NPC set to ${npc.name}")
						return@PlayerCommandExecutor
					}

					// If no target, check if the player provided an NPC name
					if (npc == null) {
						player.sendError("Please provide an NPC name.")
						return@PlayerCommandExecutor
					}

					// If npcId is provided, check if it exists
					if (npcId != null) {
						val npc = CitizensAPI.getNPCRegistry().getById(npcId)
						if (npc == null) {
							player.sendError("NPC with ID $npcId not found.")
							return@PlayerCommandExecutor
						}
						plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
						player.sendSuccess("Current NPC set to ${npc.name}")
						return@PlayerCommandExecutor
					}

					// There might be multiple NPCs with the same name, if so, check player radius. If not, ask player to select one.
					for (npc in CitizensAPI.getNPCRegistry()) {
						if (npc.name.equals(args["npc"])) {
							if (!npc.isSpawned) {
								continue
							}
							val npcLocation = npc.entity.location
							val playerLocation = player.location
							if (playerLocation.distance(npcLocation) <= 15) {
								plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
								player.sendSuccess("Current NPC set to $npc")
								return@PlayerCommandExecutor
							} else {
								// print all possible npcs with the same name their ids next to it (make them clickable)
								val npcList = CitizensAPI.getNPCRegistry().filter { it.name.equals(args["npc"]) }
								for (npc in npcList) {
									val clickableNpc =
										CommandComponentUtils.createButton(
											plugin.miniMessage,
											"Select ${npc.name} (${npc.id})",
											"green",
											"run_command",
											"/setcurnpc ${npc.name} ${npc.id}",
											"Set current NPC to ${npc.name} (${npc.id})",
										)

									player.sendMessage(clickableNpc)
								}
							}
						}
					}
				},
			).register()

		registerSafeStopCommand()
	}

	fun registerSafeStopCommand() {
		// Create a command to safely stop the plugin
		CommandAPICommand("safestop")
			.withPermission("story.admin")
			.withFullDescription("Safely stops the Story plugin, ensuring all conversations are summarized")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("§6Starting safe shutdown process... Please wait.")

					// Run async to avoid blocking the main thread
					Bukkit.getScheduler().runTaskAsynchronously(
						plugin,
						Runnable {
							plugin.safeStop().thenRun {
								sender.sendMessage("§2Story plugin has been safely shut down.")
								// Schedule server shutdown on the main thread
								Bukkit.getScheduler().runTask(
									plugin,
									Runnable {
										sender.sendMessage("§6Stopping the server...")
										Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop")
									},
								)
							}
						},
					)

					1
				},
			).register()
	}

	/**
	 * Extracts a JSON object from a string that might contain additional text.
	 *
	 * @param input String that contains JSON somewhere within it
	 * @return String containing only the JSON object
	 */
	private fun extractJsonFromString(input: String): String {
		// Find the first { and last } to extract the JSON object
		val startIndex = input.indexOf("{")
		val endIndex = input.lastIndexOf("}")

		if (startIndex >= 0 && endIndex > startIndex) {
			return input.substring(startIndex, endIndex + 1)
		}

		// If no JSON object found, return the original string
		return input
	}

	/**
	 * Builds a relationship context string for NPCs in the conversation
	 *
	 * @param relationships Map of relationships for the NPC
	 * @param conversation The current conversation
	 * @return A formatted string containing relationship information relevant to the conversation
	 */
	private fun buildRelationshipContext(
		relationships: Map<String, Relationship>,
		conversation: Conversation,
	): String =
		relationships.values
			.filter { rel ->
				// Only include relationships with entities that are in the conversation
				conversation.npcs.any { it.name == rel.targetName } ||
					conversation.players?.any { playerId ->
						val player = Bukkit.getPlayer(playerId)
						player != null && EssentialsUtils.getNickname(player.name) == rel.targetName
					} == true
			}.joinToString("\n") { rel ->
				"${rel.id} feels ${rel.type} towards ${rel.targetName}: ${rel.describe()}"
			}

	// Helper data class for Gson parsing
	data class NPCInfo(
		val context: String? = null,
		val appearance: String? = null,
	)
}
