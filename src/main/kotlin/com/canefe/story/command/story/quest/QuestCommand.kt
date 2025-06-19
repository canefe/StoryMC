package com.canefe.story.command.story.quest

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.quest.Quest
import com.canefe.story.quest.QuestObjective
import com.canefe.story.quest.QuestStatus
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.StringTooltip
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.OfflinePlayerArgument
import dev.jorel.commandapi.arguments.PlayerArgument
import dev.jorel.commandapi.arguments.SafeSuggestions
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.persistence.PersistentDataType
import java.util.*

class QuestCommand(private val plugin: Story) {
	private val commandUtils = QuestCommandUtils()

	fun getCommand(): CommandAPICommand = CommandAPICommand("quest")
		.withAliases("quests", "q")
		.withPermission("story.quest")
		.withUsage(
			"/story quest <list>",
			"/story quest <info> <quest_id>",
			"/story quest <assign> <player> <quest_id>",
			"/story quest <complete> <player> <quest_id>",
			"/story quest <completeall> <player>",
			"/story quest <progress> <player> <quest_id>",
			"/story quest <fail> <player> <quest_id>",
			"/story quest <view> <player>",
			"/story quest <create> <player> <prompt>",
			"/story quest <reset> <player> <quest_id>",
			"/story quest <reload>",
			"/story quest <qb> [player]",
			"/story quest <journal> [memories|individuals] [player]",
		)
		.executesPlayer(
			PlayerCommandExecutor { player, args ->

				if (plugin.questManager.getPlayerQuests(player.uniqueId).isEmpty()) {
					player.sendError("You have no quests.")
					return@PlayerCommandExecutor
				}
				getQuestChestGUI(player, null)
			},
		).withSubcommand(getListCommand())
		.withSubcommand(getInfoCommand())
		.withSubcommands(getBookCommand())
		.withSubcommand(getAssignCommand())
		.withSubcommand(getCompleteCommand())
		.withSubcommand(getCompleteAllCommand())
		.withSubcommand(getProgressCommand())
		.withSubcommand(getReloadCommand())
		.withSubcommand(getViewCommand())
		.withSubcommand(getCreateCommand())
		.withSubcommand(getResetCommand())
		.withSubcommand(getQuestBookCommand())
		.withSubcommand(getFailCommand())
		.withSubcommand(getJournalCommand())

	private fun getQuestBookCommand(): CommandAPICommand = CommandAPICommand("qb")
		.withPermission("story.quest.book")
		.withOptionalArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.executesPlayer(
			PlayerCommandExecutor { player, args ->
				val target =
					if (args[0] is OfflinePlayer) {
						args[0] as OfflinePlayer
					} else {
						null
					}

				if (target != null) {
					if (!player.hasPermission("story.quest.admin")) {
						player.sendError("You don't have permission to create quest books for other players.")
						return@PlayerCommandExecutor
					}

					// Give a physical quest book for the specified target player
					val book = ItemStack(Material.WRITTEN_BOOK)
					val meta = book.itemMeta as BookMeta
					val targetName = EssentialsUtils.getNickname(target?.name ?: player.name)
					meta.title(plugin.miniMessage.deserialize("$targetName's Journal"))
					meta.author(plugin.miniMessage.deserialize("<gold>$targetName</gold>"))

					// Add placeholder first page
					val pageContent =
						Component
							.text()
							.append(plugin.miniMessage.deserialize("<gold>Journal</gold>\n\n"))
							.append(plugin.miniMessage.deserialize("<gray>Right-click to view $targetName's quests.</gray>"))
							.build()
					meta.addPages(pageContent)

					// Store target UUID in persistent data container
					val targetKey = NamespacedKey(plugin, "quest_book_target")
					meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, target.uniqueId.toString())

					book.itemMeta = meta

					// Give the book to the player
					if (player.inventory.addItem(book).isEmpty()) {
						player.sendSuccess("You received $targetName's Quest Book.")
					} else {
						player.sendError("Your inventory is full. Cannot give you the quest book.")
					}

					return@PlayerCommandExecutor
				}

				commandUtils.openQuestBook(player)
			},
		)

	private fun getJournalCommand(): CommandAPICommand = CommandAPICommand("journal")
		.withPermission("story.quest.journal")
		.withSubcommand(
			CommandAPICommand("memories")
				.withPermission("story.quest.journal.memories")
				.withOptionalArguments(
					OfflinePlayerArgument("player").replaceSafeSuggestions(
						SafeSuggestions.suggest {
							Bukkit.getOfflinePlayers()
								.filter { it.hasPlayedBefore() }
								.toTypedArray()
						},
					),
				)
				.executesPlayer(
					PlayerCommandExecutor { player, args ->
						val target =
							if (args[0] is OfflinePlayer) {
								args[0] as OfflinePlayer
							} else {
								player
							}

						commandUtils.openMemoriesBook(player, target)
					},
				),
		)
		.withSubcommand(
			CommandAPICommand("individuals")
				.withPermission("story.quest.journal.individuals")
				.withOptionalArguments(
					OfflinePlayerArgument("player").replaceSafeSuggestions(
						SafeSuggestions.suggest {
							Bukkit.getOfflinePlayers()
								.filter { it.hasPlayedBefore() }
								.toTypedArray()
						},
					),
				)
				.executesPlayer(
					PlayerCommandExecutor { player, args ->
						val target =
							if (args[0] is OfflinePlayer) {
								args[0] as OfflinePlayer
							} else {
								player
							}

						commandUtils.openIndividualsBook(player, target)
					},
				),
		)

	private fun getViewCommand(): CommandAPICommand = CommandAPICommand("view")
		.withPermission("story.quest.view")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			).withPermission("story.quest.admin"),
		)
		.executesPlayer(
			PlayerCommandExecutor { player, args ->
				val target =
					if (args[0] is OfflinePlayer) {
						args[0] as OfflinePlayer
					} else {
						null
					}

				getQuestChestGUI(player, target)
			},
		)

	private fun getQuestChestGUI(player: Player, target: OfflinePlayer? = null) {
		// Determine which player's quests to display
		val targetPlayer = target ?: player
		val isAdmin = target != null && player.hasPermission("story.quest.admin")
		val mm = plugin.miniMessage

		// Create GUI with appropriate title
		val guiTitle = if (isAdmin) "${targetPlayer.name}'s Quest Book" else "Quest Book"
		val gui = ChestGui(3, guiTitle)
		gui.setOnGlobalClick { event -> event.isCancelled = true }

		// Retrieve the target player's quests
		val quests = plugin.questManager.getPlayerQuests(targetPlayer.uniqueId)
		// only filter quests that are not completed quests is a Map
		val filteredQuests = quests.filter { it.value.status != QuestStatus.COMPLETED }

		if (quests.isEmpty()) {
			player.sendError("${if (isAdmin) "${targetPlayer.name} has" else "You have"} no quests.")
			return
		}

		val pages = PaginatedPane(0, 0, 9, 5)
		pages.populateWithGuiItems(
			filteredQuests.values.map { playerQuest ->
				val quest = plugin.questManager.getQuest(playerQuest.questId)
				val status = plugin.questManager.getPlayerQuestStatus(targetPlayer, playerQuest.questId)
				val questItem = ItemStack(Material.PAPER)
				val questName = "<reset><white>${quest?.title}"
				val statusColor = commandUtils.getStatusColor(status)
				val statusText = commandUtils.statusParser(status.name)
				// dive description into 50 character per component
				// add name and lore to the item
				questItem.itemMeta =
					questItem.itemMeta?.apply {
						displayName(mm.deserialize(questName))
						lore(
							listOf(
								mm.deserialize("<reset><gray>${quest?.description}"),
								mm.deserialize("<reset>$statusColor$statusText"),
								mm.deserialize(
									if (isAdmin) "<gray>Viewing ${targetPlayer.name}'s quest" else "<gray>Click to set this quest as active",
								),
							),
						)
					}
				GuiItem(
					questItem,
				) { event: InventoryClickEvent ->
					event.isCancelled = true

					val objectiveInfo =
						quest?.id?.let { questId ->
							val objectiveMap = plugin.questManager.getCurrentObjective(targetPlayer, questId)
							val currentIndex = objectiveMap?.keys?.firstOrNull()
							val currentObjective = currentIndex?.let { objectiveMap[it] }

							ObjectiveInfo(objectiveMap, currentIndex, currentObjective)
						} ?: ObjectiveInfo(null, null, null)

					if (quest == null) {
						return@GuiItem
					}

					val status = Pair(statusColor, statusText)
					// pass down currentObjectiveMap, currentObjectiveIndex, and currentObjective
					plugin.questManager.printQuest(quest, player, status, isAdmin, objectiveInfo)
					if (targetPlayer.isOnline) {
						plugin.questManager.printQuest(quest, targetPlayer.player!!, status, objectiveInfo = objectiveInfo)
					}

					val title = quest?.title ?: "Unknown"
					val currentObjective = objectiveInfo.currentObjective

					if (currentObjective != null) {
						// targetPlayer might be either OnlinePlayer or OfflinePlayer
						// plugin.playerManager.setPlayerQuest(targetPlayer, quest.id, currentObjective.description)
						if (isAdmin) {
							targetPlayer.player?.let { targetPlayer ->
								plugin.playerManager.setPlayerQuest(targetPlayer, title, currentObjective.description)
							}
						} else {
							plugin.playerManager.setPlayerQuest(player, title, currentObjective.description)
						}
					} else {
						player.sendRaw("<gray>No current objective.")
					}
				}
			},
		)
		pages.setOnClick { event: InventoryClickEvent? -> }

		gui.addPane(pages)

		val background = OutlinePane(0, 5, 9, 1)
		background.addItem(GuiItem(ItemStack(Material.BLACK_STAINED_GLASS_PANE)))
		background.setRepeat(true)
		background.priority = Pane.Priority.LOWEST

		gui.addPane(background)

		val navigation = StaticPane(0, 5, 9, 1)
		navigation.addItem(
			GuiItem(
				ItemStack(Material.RED_WOOL),
			) { event: InventoryClickEvent? ->
				if (pages.page > 0) {
					pages.page = pages.page - 1
					gui.update()
				}
			},
			0,
			0,
		)

		navigation.addItem(
			GuiItem(
				ItemStack(Material.GREEN_WOOL),
			) { event: InventoryClickEvent? ->
				if (pages.page < pages.pages - 1) {
					pages.page = pages.page + 1
					gui.update()
				}
			},
			8,
			0,
		)

		navigation.addItem(
			GuiItem(
				ItemStack(Material.BARRIER),
			) { event: InventoryClickEvent ->
				event.whoClicked.closeInventory()
			},
			4,
			0,
		)

		gui.addPane(navigation)
		gui.show(player)
	}

	private fun getBookCommand(): CommandAPICommand = QuestBookCommand(commandUtils).getCommand()

	private fun getListCommand(): CommandAPICommand {
		return CommandAPICommand("list")
			.withPermission("story.quest.list")
			.withOptionalArguments(PlayerArgument("player"))
			.executesPlayer(
				PlayerCommandExecutor { player, args ->
					val targetPlayer =
						if (args[0] is Player) {
							args[0] as Player
						} else {
							player
						}
					val questsWithPlayers = plugin.questManager.getAllQuestsWithPlayers()
					if (questsWithPlayers.isEmpty()) {
						player.sendError("No quests found.")
						return@PlayerCommandExecutor
					}

					player.sendRaw("<yellow>===== <gold>Quests</gold> =====</yellow>")
					questsWithPlayers.forEach { (questId, players) ->
						val quest = plugin.questManager.getQuest(questId) ?: return@forEach

						// Get status of this quest for current viewing player
						val status = plugin.questManager.getPlayerQuestStatus(targetPlayer, questId)
						val statusColor = commandUtils.getStatusColor(status)

						// Create header for the quest
						player.sendRaw("$statusColor${quest.id} <gray>- <white>${quest.title}")

						// Create button with the quest title instead of ID
						commandUtils
							.createButton(
								"<white>${quest.title}",
								"green",
								"run_command",
								"/story quest info ${quest.id}",
								"Click to view quest details",
							).let { button ->
								player.sendRaw(plugin.miniMessage.serialize(button))
							}

						// Show assigned players (max 5 to avoid spam)
						if (players.isNotEmpty()) {
							val displayPlayers = players.take(5)
							val playersText = displayPlayers.joinToString(", ") { it.name ?: "Unknown" }
							val extraCount = if (players.size > 5) " <gray>and ${players.size - 5} more..." else ""

							player.sendRaw("<gray>  Assigned to: <white>$playersText$extraCount")
						} else {
							player.sendRaw("<gray>  Not assigned to any players")
						}
					}
				},
			)
	}

	private fun getInfoCommand(): CommandAPICommand {
		return CommandAPICommand("info")
			.withPermission("story.quest.info")
			.withArguments(
				StringArgument("quest_id").replaceSuggestions { info, builder ->
					val quests = plugin.questManager.getAllQuests()

					val suggestions =
						quests
							.map { it.id }
							.distinct()

					suggestions.forEach {
						builder.suggest(it)
					}

					builder.buildFuture()
				},
			).executesPlayer(
				PlayerCommandExecutor { player, args ->
					val questId = args[0] as String
					val quest = plugin.questManager.getQuest(questId)

					if (quest == null) {
						player.sendError("Quest with ID $questId not found.")
						return@PlayerCommandExecutor
					}

					player.sendRaw("<yellow>===== <gold>${quest.title}</gold> =====</yellow>")
					player.sendRaw("<gray>ID: <white>${quest.id}")
					player.sendRaw("<gray>Description: <white>${quest.description}")
					player.sendRaw("<gray>Objectives:")
					quest.objectives.forEach { obj ->
						player.sendRaw("<gray>- <white>${obj.description} (${obj.required} ${obj.type} <gold>${obj.target}</gold>)")
					}
				},
			)
	}

	private fun getAssignCommand(): CommandAPICommand = CommandAPICommand("assign")
		.withPermission("story.quest.assign")
		.withArguments(PlayerArgument("player"))
		.withArguments(
			StringArgument("quest_id").replaceSuggestions { info, builder ->
				val quests = plugin.questManager.getAllQuests()

				val suggestions =
					quests
						.map { it.id }
						.distinct()

				suggestions.forEach {
					builder.suggest(it)
				}

				builder.buildFuture()
			},
		).executes(
			CommandExecutor { sender, args ->
				val player = args[0] as Player
				val questId = args[1] as String

				val success = plugin.questManager.assignQuestToPlayer(player, questId)
				if (success) {
					val quest = plugin.questManager.getQuest(questId)
					sender.sendSuccess("Successfully assigned quest ${quest?.title ?: questId} to ${player.name}")
					player.sendSuccess("New quest: e${quest?.title ?: questId}")
				} else {
					sender.sendError("Failed to assign quest $questId to ${player.name}")
				}
			},
		)

	// fail
	private fun getFailCommand(): CommandAPICommand = CommandAPICommand("fail")
		.withPermission("story.quest.fail")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.withArguments(
			StringArgument("quest_id").replaceSuggestions(
				ArgumentSuggestions.stringsWithTooltips { info ->
					val player = info.previousArgs.get(0) as OfflinePlayer
					val quests = plugin.questManager.getAllQuestsOfPlayer(player.uniqueId)

					val suggestions =
						quests
							.filter { it.value == QuestStatus.IN_PROGRESS }
							.map { it.key }
							.distinct()

					val tooltips =
						suggestions
							.map { quest ->
								StringTooltip.ofString(quest.id, quest.title)
							}.toTypedArray()

					tooltips
				},
			),
		).executes(
			CommandExecutor { sender, args ->
				val player = args[0] as OfflinePlayer
				val questId = args[1] as String
				val quest = plugin.questManager.getQuest(questId) ?: run {
					sender.sendError("Quest with ID $questId not found.")
					return@CommandExecutor
				}
				if (player.isOnline) {
					plugin.questManager.failQuest(player.player!!, quest)
				} else {
					plugin.questManager.failQuest(player, quest)
				}
				sender.sendSuccess("Quest $questId failed for ${player.name}")
			},
		)

	private fun getCompleteCommand(): CommandAPICommand = CommandAPICommand("complete")
		.withPermission("story.quest.complete")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.withArguments(
			StringArgument("quest_id").replaceSuggestions(
				ArgumentSuggestions.stringsWithTooltips { info ->
					val player = info.previousArgs.get(0) as OfflinePlayer
					val quests = plugin.questManager.getAllQuestsOfPlayer(player.uniqueId)

					val suggestions =
						quests
							.filter { it.value == QuestStatus.IN_PROGRESS }
							.map { it.key }
							.distinct()

					val tooltips =
						suggestions
							.map { quest ->
								StringTooltip.ofString(quest.id, quest.title)
							}.toTypedArray()

					tooltips
				},
			),
		).executes(
			CommandExecutor { sender, args ->
				var player = args[0] as OfflinePlayer
				val questId = args[1] as String

				if (player.isOnline) {
					plugin.questManager.completeQuest(player.player!!, questId)
				} else {
					plugin.questManager.completeQuest(player, questId)
				}
				sender.sendSuccess("Quest $questId completed for ${player.name}")
			},
		)

	// complete all command that completes all the quests for a player
	private fun getCompleteAllCommand(): CommandAPICommand = CommandAPICommand("completeall")
		.withPermission("story.quest.completeall")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.executes(
			CommandExecutor { sender, args ->
				val player = args[0] as OfflinePlayer

				val quests = plugin.questManager.getAllQuestsOfPlayer(player.uniqueId)

				if (player.isOnline) {
					quests.forEach { (quest, _) ->
						plugin.questManager.completeQuest(player.player!!, quest.id)
					}
				} else {
					quests.forEach { (quest, _) ->
						plugin.questManager.completeQuest(player, quest.id)
					}
				}

				sender.sendSuccess("All quests completed for ${player.name}")
			},
		)

	private fun getProgressCommand(): CommandAPICommand = CommandAPICommand("progress")
		.withPermission("story.quest.progress")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.withArguments(
			StringArgument("quest_id").replaceSuggestions(
				ArgumentSuggestions.stringsWithTooltips { info ->
					val player = info.previousArgs.get(0) as OfflinePlayer
					val quests = plugin.questManager.getAllQuestsOfPlayer(player.uniqueId)

					val suggestions =
						quests
							.filter { it.value == QuestStatus.IN_PROGRESS }
							.map { it.key }
							.distinct()

					val tooltips =
						suggestions
							.map { quest ->
								StringTooltip.ofString(quest.id, quest.title)
							}.toTypedArray()

					tooltips
				},
			),
		).executes(
			CommandExecutor { sender, args ->
				val player = args[0] as Player
				val questId = args[1] as String

				plugin.questManager.updateObjectiveProgress(player, questId)
				sender.sendSuccess("Updated progress for quest $questId for ${player.name}")
			},
		)

	private fun getCreateCommand(): CommandAPICommand {
		return CommandAPICommand("create")
			.withPermission("story.command.quest.create")
			.withAliases("generate")
			.withArguments(PlayerArgument("player"))
			.withArguments(GreedyStringArgument("prompt"))
			.executes(
				CommandExecutor { sender, args ->
					val targetPlayer = args["player"] as Player
					val prompt = args["prompt"] as String

					val story = plugin

					// Find relevant lore related to the context
					val loreContexts = story.lorebookManager.findLoresByKeywords(prompt)
					val loreInfo =
						if (loreContexts.isNotEmpty()) {
							"Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
						} else {
							"No relevant lore found for the given prompt."
						}

					// Find relevant locations mentioned in the prompt
					val locationKeywords =
						prompt
							.split(" ")
							.filter { it.length > 3 } // Only consider words with more than 3 characters
							.distinct()

					val relevantLocations = mutableListOf<String>()
					val locationContexts = mutableListOf<String>()

					// Check if any location names match our keywords
					locationKeywords.forEach { keyword ->
						story.locationManager.getAllLocations().forEach { location ->
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

					// Check any NPCs mentioned in the prompt
					val npcKeywords =
						prompt
							.split(" ")
							.filter { it.length > 3 } // Only consider words with more than 3 characters
							.distinct()

					val relevantNPCs = mutableListOf<String>()
					val npcContexts = mutableListOf<String>()

					// Check if any NPC names match our keywords
					npcKeywords.forEach { keyword ->
						story.npcDataManager.getAllNPCNames().forEach { npcName ->
							if (npcName.equals(keyword, ignoreCase = true)) {
								relevantNPCs.add(npcName)
								val npcContext = story.npcContextGenerator.getOrCreateContextForNPC(npcName)
								val lastFewMemories = npcContext?.getMemoriesForPrompt(story.timeService, 3)
								if (npcContext != null) {
									npcContexts.add("$npcName's context: ${npcContext.context} ")
									if (lastFewMemories != null && lastFewMemories.isNotEmpty()) {
										npcContexts.add("$npcName's recent memories: $lastFewMemories")
									}
								}
							}
						}
					}

					val npcInfo =
						if (relevantNPCs.isNotEmpty()) {
							"Relevant NPCs found: ${relevantNPCs.joinToString(", ")}"
						} else {
							"No relevant NPCs found for the given prompt."
						}

					sender.sendSuccess("Generating quest based on: '$prompt'")
					sender.sendSuccess(loreInfo)
					sender.sendSuccess(locationInfo)
					sender.sendSuccess(npcInfo)

					// Include relevant lore and location contexts in the prompt
					val loreContext =
						if (loreContexts.isNotEmpty()) {
							"\n\nInclude these world lore elements in your quest:\n" +
								loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" }
						} else {
							""
						}

					val locationContext =
						if (locationContexts.isNotEmpty()) {
							"\n\nInclude these locations and their context in your quest:\n" +
								locationContexts.joinToString("\n\n") { "- $it" }
						} else {
							""
						}
					val playerName = EssentialsUtils.getNickname(targetPlayer.name)
					// Create messages for AI prompt
					val messages =
						mutableListOf(
							ConversationMessage(
								"system",
								"""
								Generate a Minecraft quest based on the given prompt, lore, and location context.

								Quest is for character $playerName.

								Your response MUST be ONLY a valid JSON object with this structure:
								{
								    "title": "Brief Title (2 WORDS MAX)",
								    "description": "First person perspective from $playerName of brief quest description that explains the backstory and motivation (2 sentences MAX)",
								    "questType": "SIDE",
								    "objectives": [
								        {
								            "description": "Brief instruction for what the $playerName needs to do (3-4 WORDS MAX)",
								            "type": "EXPLORE",
								            "target": "Location",
								            "required": 1
								        },
								        {
								            "description": "Another objective description (3-4 WORDS MAX)",
								            "type": "TALK",
								            "target": "NPC",
								            "required": 1
								        }
								    ],
									"rewards": [
										{"type": "EXPERIENCE", "amount": 100-5000}
									]
								}

								Valid objective types: KILL, COLLECT, TALK, EXPLORE, CRAFT, USE

								Valid collection targets: ${
									story.questManager.getValidCollectibles().joinToString(", ")
								}
								Valid kill targets: ${story.questManager.getValidKillTargets().joinToString(", ")}
								Valid location targets: ${story.questManager.getValidLocations().joinToString(", ")}
								Valid talk targets: Any NPC name

								Create 2-4 objectives that form a coherent quest flow.
								Don't include dialogue in objective descriptions.
								Every objective must be brief and 1 sentence long.
								Make sure all objectives are player actions.
								Choose appropriate objective types for what the player needs to do.
								Use ONLY valid targets from the lists provided.
								$loreContext
								$locationContext
								${if (relevantNPCs.isNotEmpty()) {
									"Relevant NPCs found: ${relevantNPCs.joinToString(", ")}\n" +
										npcContexts.joinToString("\n")
								} else {
									""
								}}
								""".trimIndent(),
							),
							ConversationMessage("user", prompt),
						)

					// Get AI response to generate the quest
					story
						.getAIResponse(messages)
						.thenAccept { aiResponse ->
							if (aiResponse == null) {
								sender.sendError("Failed to generate quest content")
								return@thenAccept
							}

							try {
								// Extract JSON from response
								val jsonPattern = "\\{[\\s\\S]*\\}".toRegex()
								val jsonMatch =
									jsonPattern.find(aiResponse.trim())?.value
										?: throw Exception("No valid JSON found in response")

								// Parse quest details
								val questDetails = story.gson.fromJson(jsonMatch, QuestDetails::class.java)

								// Generate a unique quest ID
								val questId = "generated_${UUID.randomUUID().toString().substring(0, 8)}"

								// Convert to Quest objects
								val objectives =
									questDetails.objectives.map { obj ->
										com.canefe.story.quest.QuestObjective(
											description = obj.description,
											type =
											try {
												com.canefe.story.quest.ObjectiveType
													.valueOf(obj.type)
											} catch (e: Exception) {
												com.canefe.story.quest.ObjectiveType.EXPLORE // Default if invalid
											},
											target = obj.target,
											required = obj.required.coerceAtLeast(1),
										)
									}

								// Create the quest
								val quest =
									com.canefe.story.quest.Quest(
										id = questId,
										title = questDetails.title,
										description = questDetails.description,
										type =
										try {
											com.canefe.story.quest.QuestType
												.valueOf(questDetails.questType)
										} catch (e: Exception) {
											com.canefe.story.quest.QuestType.SIDE
										},
										objectives = objectives,
										rewards =
										questDetails.rewards.map { reward ->
											com.canefe.story.quest.QuestReward(
												type =
												try {
													com.canefe.story.quest.RewardType
														.valueOf(reward["type"] as String)
												} catch (e: Exception) {
													com.canefe.story.quest.RewardType.EXPERIENCE // Default if invalid
												},
												amount = (reward["amount"] as Number?)?.toInt() ?: 500,
											)
										},
									)

								// Register the quest
								story.questManager.saveQuest(quest)
								story.questManager.registerQuest(quest)

								// Assign to player
								story.questManager.assignQuestToPlayer(targetPlayer, questId)

								sender.sendSuccess("Created and assigned quest '${questDetails.title}' to ${targetPlayer.name}")
							} catch (e: Exception) {
								sender.sendError("Failed to create quest: ${e.message}")
								story.logger.warning("Error creating quest: ${e.message}")
								e.printStackTrace()
							}
						}.exceptionally { e ->
							sender.sendError("Error generating quest: ${e.message}")
							story.logger.warning("Error in AI response for quest creation: ${e.message}")
							e.printStackTrace()
							null
						}
				},
			)
	}

	// Add this data class to parse the AI response
	private data class QuestDetails(
		val title: String = "",
		val description: String = "",
		val questType: String = "SIDE",
		val objectives: List<ObjectiveDetail> = emptyList(),
		val rewards: List<Map<String, Any>> = emptyList(), // Rewards can be more complex, adjust as needed
	)

	private data class ObjectiveDetail(
		val description: String = "",
		val type: String = "EXPLORE",
		val target: String = "",
		val required: Int = 1,
	)

	data class ObjectiveInfo(
		val objectiveMap: Map<Int, QuestObjective>?,
		val currentIndex: Int?,
		val currentObjective: QuestObjective?,
	)

	private fun getResetCommand(): CommandAPICommand = CommandAPICommand("reset")
		.withPermission("story.quest.reset")
		.withArguments(
			OfflinePlayerArgument("player").replaceSafeSuggestions(
				SafeSuggestions.suggest {
					Bukkit.getOfflinePlayers()
						.filter { it.hasPlayedBefore() }
						.toTypedArray()
				},
			),
		)
		.withArguments(
			StringArgument("quest_id").replaceSuggestions(
				ArgumentSuggestions.stringsWithTooltips { info ->
					val player = info.previousArgs.get(0) as OfflinePlayer
					val quests = plugin.questManager.getAllQuestsOfPlayer(player.uniqueId)

					val suggestions =
						quests
							.filter { it.value == QuestStatus.IN_PROGRESS }
							.map { it.key }
							.distinct()

					val tooltips =
						suggestions
							.map { quest ->
								StringTooltip.ofString(quest.id, quest.title)
							}.toTypedArray()

					tooltips
				},
			),
		).executes(
			CommandExecutor { sender, args ->
				val player = args[0] as Player
				val questId = args[1] as String

				plugin.questManager.resetQuest(player, questId)
				sender.sendSuccess("Quest $questId reset for ${player.name}")
			},
		)

	private fun getReloadCommand(): CommandAPICommand = CommandAPICommand("reload")
		.withPermission("story.quest.reload")
		.executes(
			CommandExecutor { sender, _ ->
				plugin.questManager.loadAllQuests()
				sender.sendSuccess("Quests reloaded")
			},
		)
}
