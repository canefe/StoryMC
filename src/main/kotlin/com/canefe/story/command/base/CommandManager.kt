package com.canefe.story.command.base

import com.canefe.story.Story
import com.canefe.story.command.conversation.ConvCommand
import com.canefe.story.command.story.StoryCommand
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCData
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIBukkitConfig
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.CommandArguments
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player

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

		// Register simpler commands
		registerSimpleCommands()
	}

	private fun registerSimpleCommands() {
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
					plugin.conversationManager.generateResponses(conversation)
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

		// npcinit
		CommandAPICommand("npcinit")
			.withPermission("storymaker.npc.init")
			.withArguments(StringArgument("location"))
			.withArguments(TextArgument("npc"))
			.withOptionalArguments(GreedyStringArgument("prompt"))
			.executesPlayer(
				PlayerCommandExecutor { player: Player, args: CommandArguments ->
					val npcName = args["npc"] as String
					val location = args["location"] as String
					val prompt = args.getOrDefault("prompt", "") as String

					val npcContext =
						plugin.npcContextGenerator.getOrCreateContextForNPC(npcName) ?: run {
							player.sendError("NPC context not found. Please create the NPC first.")
							return@PlayerCommandExecutor
						}

					val storyLocation =
						plugin.locationManager.getLocation(location) ?: run {
							player.sendError("Location not found. Please create the location first.")
							return@PlayerCommandExecutor
						}

					if (prompt.isNotEmpty()) {
						// Inform player we're generating context
						player.sendInfo(
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

						messages.add(
							ConversationMessage(
								"system",
								npcContext.context,
							),
						)

						messages.add(
							ConversationMessage(
								"system",
								"Generate a detailed NPC profile for a character named " + npcName +
									" in the location " + location + ". Include: personality traits, " +
									"background story, appearance, unique quirks, and role in society. " +
									"Be creative, detailed, and make the character feel alive. " +
									"Format the response as 'ROLE: [brief role description]' followed by " +
									"a detailed paragraph about the character.",
							),
						)

						// Add the user prompt
						messages.add(ConversationMessage("user", prompt))

						// Use CompletableFuture API instead of manual task scheduling
						plugin.getAIResponse(messages).thenAccept { response ->
							// Return to the main thread to access Bukkit API
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									if (response?.contains("ROLE:") == true) { // Ensure response is not null
										val parts = response.split("ROLE:", ignoreCase = false, limit = 2).toTypedArray()
										val role: String
										val context: String?

										if (parts.size > 1) {
											val roleParts = parts[1].split("\n", limit = 2).toTypedArray()
											role = roleParts[0].trim()
											context = if (roleParts.size > 1) roleParts[1].trim() else parts[1].trim()
										} else {
											role = ""
											context = response
										}

										val npcData =
											NPCData(
												npcName,
												role,
												storyLocation,
												context,
											)

										plugin.npcDataManager.saveNPCData(npcName, npcData)
										player.sendSuccess("AI-generated profile for <yellow>$npcName</yellow> created!")
										player.sendInfo("Role: <yellow>$role</yellow>")
										player.sendInfo(
											"Context summary: <yellow>${if (context.length > 50) {
												context.substring(
													0,
													50,
												) + "..."
											} else {
												context
											}}</yellow>",
										)
									} else {
										player.sendError("Failed to generate AI context. Using default values.")

										val npcData =
											NPCData(
												npcName,
												npcContext.role,
												storyLocation,
												plugin.config.defaultContext,
											)
										plugin.npcDataManager.saveNPCData(npcName, npcData)

										player.sendInfo("Basic NPC data saved for <yellow>$npcName</yellow>.")
									}
								},
							)
						}
					}
				},
			).register()

		// g command
		CommandAPICommand("g")
			.withPermission("storymaker.chat.toggle")
			.withArguments(
				dev.jorel.commandapi.arguments
					.GreedyStringArgument("message"),
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val message = args.get("message") as String
					// Get Current NPC
					val currentNPC = plugin.playerManager.getCurrentNPC(player.uniqueId)
					if (currentNPC == null) {
						player.sendError("Please select an NPC first.")
						throw CommandAPI.failWithString("You are not in a conversation with any NPC.")
					}
					// Check if NPC exists
					val npc = CitizensAPI.getNPCRegistry().getByUniqueId(currentNPC)
					if (npc == null) {
						player.sendError("NPC not found.")
						throw CommandAPI.failWithString("NPC not found.")
					}

					val npcName = npc.name

					val conversation =
						plugin.conversationManager.getConversation(npcName) ?: run {
							// create new conversation with nearby NPCs and players
							val nearbyNPCs = plugin.getNearbyNPCs(npc, plugin.config.chatRadius)
							val players = plugin.getNearbyPlayers(npc, plugin.config.chatRadius)
							val randomPlayer = players.random()

							val newConversation = plugin.conversationManager.startConversation(randomPlayer, nearbyNPCs)

							// add other players
							for (p in players) {
								if (p != randomPlayer) {
									newConversation.addPlayer(p)
								}
							}

							newConversation
						}

					// Show holograms for the NPCs
					plugin.conversationManager.handleHolograms(conversation, npc.name)

					Bukkit.getScheduler().runTaskLater(
						plugin,
						Runnable {
							// remove hologram after a delay
							plugin.conversationManager.cleanupNPCHologram(npc)

							plugin.npcMessageService.broadcastNPCMessage(message, npc)
						},
						60L,
					) // 20 ticks = 1 second
				},
			).register()

		// setcurnpc
		CommandAPICommand("setcurnpc")
			.withPermission("storymaker.chat.toggle")
			.withArguments(
				dev.jorel.commandapi.arguments
					.TextArgument("npc"),
			).withOptionalArguments(
				dev.jorel.commandapi.arguments
					.IntegerArgument("npc_id"),
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val npc = args.get("npc") as String
					// integer
					val npcId = args.getOptional("npc_id").orElse(null) as? Int
					// There might be multiple NPCs with the same name, if so, check player radius. If not, ask player to select one.
					for (npc in CitizensAPI.getNPCRegistry()) {
						if (npc.name.equals(args["npc"])) {
							if (!npc.isSpawned) {
								continue
							}
							val npcLocation = npc.entity.location
							val playerLocation = player.location
							if (playerLocation.distance(npcLocation) <= 15) {
								plugin.playerManager.getCurrentNPC(player.uniqueId)?.let {
									plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
								}
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
	}
}
