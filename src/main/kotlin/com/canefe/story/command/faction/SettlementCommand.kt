package com.canefe.story.command.faction

import com.canefe.story.Story
import com.canefe.story.faction.LeaderActionType
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.ChatColor
import org.bukkit.Material
import java.math.RoundingMode

/**
 * Commands for managing settlements
 */
class SettlementCommand {
	fun register() {
		// Main settlement command
		CommandAPICommand("settlement")
			.withAliases("sett")
			.withSubcommand(createSettlementCreateCommand())
			.withSubcommand(createSettlementDeleteCommand())
			.withSubcommand(createSettlementListCommand())
			.withSubcommand(createSettlementInfoCommand())
			.withSubcommand(createSettlementMineCommand())
			.withSubcommand(createSettlementTreasuryCommand())
			.withSubcommand(createSettlementLeaderCommand())
			.withSubcommand(createSettlementConfigCommand())
			.withSubcommand(createSettlementCollectCommand())
			.withPermission("story.settlement")
			.executes(
				CommandExecutor { sender, args ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement create - Create a new settlement")
					sender.sendMessage("${ChatColor.GRAY}/settlement delete - Delete a settlement")
					sender.sendMessage("${ChatColor.GRAY}/settlement list - List settlements")
					sender.sendMessage("${ChatColor.GRAY}/settlement info - View settlement information")
					sender.sendMessage("${ChatColor.GRAY}/settlement mine - Manage settlement mines")
					sender.sendMessage("${ChatColor.GRAY}/settlement treasury - Manage settlement treasury")
					sender.sendMessage("${ChatColor.GRAY}/settlement leader - Manage settlement leader")
					sender.sendMessage("${ChatColor.GRAY}/settlement config - Configure settlement settings")
					sender.sendMessage("${ChatColor.GRAY}/settlement collect - Collect taxes or resources")
				},
			).register()
	}

	/**
	 * Command to create a new settlement in a faction
	 */
	private fun createSettlementCreateCommand(): CommandAPICommand {
		return CommandAPICommand("create")
			.withArguments(StringArgument("name"))
			.withArguments(
				StringArgument("factionId").replaceSuggestions { info, builder ->
					val suggestions =
						Story.instance.factionManager
							.getAllFactions()
							.map { it.id }
					suggestions.forEach {
						builder.suggest(it)
					}
					builder.buildFuture()
				},
			).withOptionalArguments(BooleanArgument("isCapital"))
			.withPermission("story.settlement.create")
			.executes(
				CommandExecutor { sender, args ->
					val settlementName = args[0] as String
					val factionId = args[1] as String
					val isCapital = args.getOrDefault(2, false) as Boolean

					val faction = Story.instance.factionManager.getFaction(factionId)
					if (faction == null) {
						sender.sendMessage("${ChatColor.RED}Faction not found!")
						return@CommandExecutor
					}

					val settlement = faction.createSettlement(settlementName, isCapital)
					sender.sendMessage("${ChatColor.GREEN}Created settlement ${settlement.name} in faction ${faction.name}")

					// Save changes
					Story.instance.factionManager.saveAllFactions()
				},
			)
	}

	/**
	 * Command to delete a settlement
	 */
	private fun createSettlementDeleteCommand(): CommandAPICommand {
		return CommandAPICommand("delete")
			.withArguments(
				StringArgument("settlementId").replaceSuggestions { info, builder ->
					val suggestions =
						Story.instance.factionManager
							.getFactionSettlementIds()

					suggestions.forEach {
						builder.suggest(it)
					}

					builder.buildFuture()
				},
			).withPermission("story.settlement.delete")
			.executes(
				CommandExecutor { sender, args ->
					val settlementId = args[0] as String

					// Find the settlement and its parent faction
					val result = Story.instance.factionManager.findSettlement(settlementId)
					if (result == null) {
						sender.sendMessage("${ChatColor.RED}Settlement not found!")
						return@CommandExecutor
					}
					val (faction, settlement) = result

					if (settlement.isCapital) {
						sender.sendMessage("${ChatColor.RED}Cannot delete the capital settlement!")
						return@CommandExecutor
					}

					// Remove the settlement
					if (faction.removeSettlement(settlementId)) {
						sender.sendMessage("${ChatColor.GREEN}Deleted settlement ${settlement.name} from faction ${faction.name}")
						Story.instance.factionManager.saveAllFactions()
					} else {
						sender.sendMessage("${ChatColor.RED}Failed to delete settlement.")
					}
				},
			)
	}

	/**
	 * Command to list all settlements
	 */
	private fun createSettlementListCommand(): CommandAPICommand {
		return CommandAPICommand("list")
			.withOptionalArguments(
				StringArgument("factionId").replaceSuggestions { _, builder ->
					val suggestions =
						Story.instance.factionManager
							.getAllFactions()
							.map { it.id }
							.toTypedArray()

					suggestions.forEach {
						builder.suggest(it)
					}
					builder.buildFuture()
				},
			).withPermission("story.settlement.list")
			.executes(
				CommandExecutor { sender, args ->
					val factionId = args[0] as? String

					if (factionId != null) {
						val faction = Story.instance.factionManager.getFaction(factionId)
						if (faction == null) {
							sender.sendMessage("${ChatColor.RED}Faction not found!")
							return@CommandExecutor
						}

						sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlements in ${faction.name} ${ChatColor.GOLD}===")
						if (faction.settlements.isEmpty()) {
							sender.sendMessage("${ChatColor.GRAY}No settlements.")
						} else {
							faction.settlements.forEach { settlement ->
								sender.sendMessage(
									"${ChatColor.GRAY}- ${ChatColor.WHITE}${settlement.name} ${if (settlement.isCapital) "${ChatColor.GOLD}(Capital)" else ""}",
								)
							}
						}
					} else {
						// List all settlements in all factions
						sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}All Settlements ${ChatColor.GOLD}===")
						var found = false

						Story.instance.factionManager.getAllFactions().forEach { faction ->
							if (faction.settlements.isNotEmpty()) {
								found = true
								sender.sendMessage("${ChatColor.YELLOW}${faction.name}:")
								faction.settlements.forEach { settlement ->
									sender.sendMessage(
										"${ChatColor.GRAY}- ${ChatColor.WHITE}${settlement.name} ${if (settlement.isCapital) "${ChatColor.GOLD}(Capital)" else ""}",
									)
								}
							}
						}

						if (!found) {
							sender.sendMessage("${ChatColor.GRAY}No settlements found.")
						}
					}
				},
			)
	}

	/**
	 * Command to view detailed settlement info
	 */
	private fun createSettlementInfoCommand(): CommandAPICommand {
		return CommandAPICommand("info")
			.withArguments(
				StringArgument("settlementId").replaceSuggestions { _, builder ->
					val suggestions =
						Story.instance.factionManager
							.getFactionSettlementIds()
							.toTypedArray()

					suggestions.forEach {
						builder.suggest(it)
					}

					builder.buildFuture()
				},
			).withPermission("story.settlement.info")
			.executes(
				CommandExecutor { sender, args ->
					val settlementId = args[0] as String

					// Find the settlement and its parent faction
					val (_, settlement) =
						Story.instance.factionManager.findSettlement(settlementId) ?: run {
							sender.sendMessage("${ChatColor.RED}Settlement not found!")
							return@CommandExecutor
						}

					// Display settlement info
					settlement.getStatusDisplay().forEach { line ->
						sender.sendMessage(line)
					}
				},
			)
	}

	/**
	 * Command to manage settlement mines
	 */
	private fun createSettlementMineCommand(): CommandAPICommand {
		return CommandAPICommand("mine")
			.withSubcommand(
				CommandAPICommand("addchest")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.mine.addchest")
					.executesPlayer(
						PlayerCommandExecutor { player, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (faction, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									player.sendMessage("${ChatColor.RED}Settlement not found!")
									return@PlayerCommandExecutor
								}

							// Get the block player is looking at
							val block = player.getTargetBlock(null, 5)
							if (block.type != Material.CHEST) {
								player.sendMessage("${ChatColor.RED}You must be looking at a chest!")
								return@PlayerCommandExecutor
							}

							// Add chest to settlement
							if (settlement.addMineChest(block)) {
								player.sendMessage("${ChatColor.GREEN}Added chest to ${settlement.name}'s mine system.")
								Story.instance.factionManager.saveAllFactions()
							} else {
								player.sendMessage("${ChatColor.RED}Failed to add chest. It may already be registered.")
							}
						},
					),
			).withSubcommand(
				CommandAPICommand("listchests")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.mine.listchests")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Display chests
							sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}${settlement.name} Mine Chests ${ChatColor.GOLD}===")
							settlement.getMineChestStatus().forEach { line ->
								sender.sendMessage(line)
							}
						},
					),
			).withSubcommand(
				CommandAPICommand("removechest")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withArguments(IntegerArgument("chestIndex"))
					.withPermission("story.settlement.mine.removechest")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String
							val chestIndex = args[1] as Int

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Remove chest
							if (settlement.removeMineChest(chestIndex - 1)) { // Convert to 0-based index
								sender.sendMessage("${ChatColor.GREEN}Removed chest #$chestIndex from ${settlement.name}'s mine system.")
								Story.instance.factionManager.saveAllFactions()
							} else {
								sender.sendMessage("${ChatColor.RED}Invalid chest index.")
							}
						},
					),
			).withSubcommand(
				CommandAPICommand("collect")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.mine.collect")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Collect income
							val income = settlement.collectDailyMineIncome()
							sender.sendMessage(
								"${ChatColor.GREEN}Collected ${income.setScale(
									2,
									RoundingMode.HALF_UP,
								)} coins from ${settlement.name}'s mines.",
							)

							// Add history entry
							settlement.addHistoryEntry(
								"Mining Income",
								"Collected ${income.setScale(2, RoundingMode.HALF_UP)} coins from mines",
							)

							// Save changes
							Story.instance.factionManager.saveAllFactions()
						},
					),
			).withPermission("story.settlement.mine")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Mine Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement mine addchest - Add a chest to the mine system")
					sender.sendMessage("${ChatColor.GRAY}/settlement mine listchests - List all mine chests")
					sender.sendMessage("${ChatColor.GRAY}/settlement mine removechest - Remove a chest from the system")
					sender.sendMessage("${ChatColor.GRAY}/settlement mine collect - Collect daily mine income")
				},
			)
	}

	/**
	 * Command to manage settlement treasury
	 */
	private fun createSettlementTreasuryCommand(): CommandAPICommand {
		return CommandAPICommand("treasury")
			.withSubcommand(
				CommandAPICommand("addchest")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.treasury.addchest")
					.executesPlayer(
						PlayerCommandExecutor { player, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									player.sendMessage("${ChatColor.RED}Settlement not found!")
									return@PlayerCommandExecutor
								}

							// Get the block player is looking at
							val block = player.getTargetBlock(null, 5)
							if (block.type != Material.CHEST) {
								player.sendMessage("${ChatColor.RED}You must be looking at a chest!")
								return@PlayerCommandExecutor
							}

							// Add chest to treasury
							if (settlement.addTreasuryChest(block)) {
								player.sendMessage("${ChatColor.GREEN}Added chest to ${settlement.name}'s treasury.")
								Story.instance.factionManager.saveAllFactions()
							} else {
								player.sendMessage("${ChatColor.RED}Failed to add chest. It may already be registered.")
							}
						},
					),
			).withSubcommand(
				CommandAPICommand("balance")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.treasury.balance")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							sender.sendMessage(
								"${ChatColor.GOLD}${settlement.name} Treasury Balance: ${ChatColor.WHITE}${settlement.treasuryBalance.setScale(
									2,
									RoundingMode.HALF_UP,
								)} coins",
							)
						},
					),
			).withSubcommand(
				CommandAPICommand("update")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.treasury.update")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							settlement.updateTreasuryDisplay()
							sender.sendMessage("${ChatColor.GREEN}Updated ${settlement.name}'s treasury display.")
						},
					),
			).withPermission("story.settlement.treasury")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Treasury Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement treasury addchest - Add a chest to the treasury")
					sender.sendMessage("${ChatColor.GRAY}/settlement treasury balance - View treasury balance")
					sender.sendMessage("${ChatColor.GRAY}/settlement treasury update - Update physical currency display")
				},
			)
	}

	/**
	 * Command to manage settlement leader
	 */
	private fun createSettlementLeaderCommand(): CommandAPICommand {
		return CommandAPICommand("leader")
			.withSubcommand(
				CommandAPICommand("generate")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.leader.generate")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Generate new leader
							val leader =
								com.canefe.story.faction.Leader
									.generateRandom("settlement")
							settlement.leader = leader

							sender.sendMessage(
								"${ChatColor.GREEN}Generated new leader for ${settlement.name}: ${ChatColor.YELLOW}${leader.title} ${leader.name}",
							)
							sender.sendMessage("${ChatColor.GRAY}Traits: ${leader.traits.joinToString(", ") { it.name }}")

							// Record the event
							settlement.addHistoryEntry("New Leader", "${leader.title} ${leader.name} has become the new leader")
							settlement.addAction(LeaderActionType.MISC, "${leader.title} ${leader.name} has become the new leader")

							// Save changes
							Story.instance.factionManager.saveAllFactions()
						},
					),
			).withSubcommand(
				CommandAPICommand("info")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.leader.info")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							val leader = settlement.leader
							if (leader == null) {
								sender.sendMessage("${ChatColor.RED}${settlement.name} has no leader!")
								return@CommandExecutor
							}

							// Display leader info
							sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Leader of ${settlement.name} ${ChatColor.GOLD}===")
							sender.sendMessage("${ChatColor.GRAY}Name: ${ChatColor.YELLOW}${leader.title} ${leader.name}")
							// sender.sendMessage("${ChatColor.GRAY}Age: ${ChatColor.WHITE}${leader.age}")
							sender.sendMessage("${ChatColor.GRAY}Skills:")
							sender.sendMessage("${ChatColor.GRAY}- Diplomacy: ${ChatColor.WHITE}${leader.diplomacy}")
							sender.sendMessage("${ChatColor.GRAY}- Martial: ${ChatColor.WHITE}${leader.martial}")
							sender.sendMessage("${ChatColor.GRAY}- Stewardship: ${ChatColor.WHITE}${leader.stewardship}")
							sender.sendMessage("${ChatColor.GRAY}- Intrigue: ${ChatColor.WHITE}${leader.intrigue}")
							sender.sendMessage("${ChatColor.GRAY}- Charisma: ${ChatColor.WHITE}${leader.charisma}")
							sender.sendMessage(
								"${ChatColor.GRAY}Traits: ${ChatColor.WHITE}${leader.traits.joinToString(", ") { it.name }}",
							)
						},
					),
			).withPermission("story.settlement.leader")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Leader Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement leader generate - Generate a new leader")
					sender.sendMessage("${ChatColor.GRAY}/settlement leader info - View leader information")
				},
			)
	}

	/**
	 * Command to configure settlement settings
	 */
	private fun createSettlementConfigCommand(): CommandAPICommand {
		return CommandAPICommand("config")
			.withSubcommand(
				CommandAPICommand("settaxrate")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withArguments(DoubleArgument("taxRate", 0.0, 0.8))
					.withPermission("story.settlement.config.settaxrate")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String
							val taxRate = args[1] as Double

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Set tax rate (the setter handles the record-keeping)
							settlement.taxRate = taxRate
							sender.sendMessage("${ChatColor.GREEN}Set ${settlement.name}'s tax rate to ${(taxRate * 100).toInt()}%")

							// Save changes
							Story.instance.factionManager.saveAllFactions()
						},
					),
			).withPermission("story.settlement.config")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Config Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement config settaxrate - Set the tax rate")
				},
			)
	}

	/**
	 * Command to collect taxes or resources
	 */
	private fun createSettlementCollectCommand(): CommandAPICommand {
		return CommandAPICommand("collect")
			.withSubcommand(
				CommandAPICommand("taxes")
					.withArguments(
						StringArgument("settlementId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getFactionSettlementIds()
									.toTypedArray()

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.collect.taxes")
					.executes(
						CommandExecutor { sender, args ->
							val settlementId = args[0] as String

							// Find the settlement
							val (_, settlement) =
								Story.instance.factionManager.findSettlement(settlementId) ?: run {
									sender.sendMessage("${ChatColor.RED}Settlement not found!")
									return@CommandExecutor
								}

							// Collect taxes
							val taxes = settlement.collectTaxes()
							sender.sendMessage(
								"${ChatColor.GREEN}Collected ${taxes.setScale(
									2,
									RoundingMode.HALF_UP,
								)} coins in taxes from ${settlement.name}.",
							)

							// Save changes
							Story.instance.factionManager.saveAllFactions()
						},
					),
			).withSubcommand(
				CommandAPICommand("alltaxes")
					.withArguments(
						StringArgument("factionId").replaceSuggestions { _, builder ->
							val suggestions =
								Story.instance.factionManager
									.getAllFactions()
									.map { it.id }

							suggestions.forEach {
								builder.suggest(it)
							}

							builder.buildFuture()
						},
					).withPermission("story.settlement.collect.alltaxes")
					.executes(
						CommandExecutor { sender, args ->
							val factionId = args[0] as String

							val faction = Story.instance.factionManager.getFaction(factionId)
							if (faction == null) {
								sender.sendMessage("${ChatColor.RED}Faction not found!")
								return@CommandExecutor
							}

							// Collect taxes from all settlements
							val totalTaxes = faction.collectSettlementTaxes()
							sender.sendMessage(
								"${ChatColor.GREEN}Collected a total of ${totalTaxes.setScale(
									2,
									RoundingMode.HALF_UP,
								)} coins in taxes from all settlements in ${faction.name}.",
							)

							// Save changes
							Story.instance.factionManager.saveAllFactions()
						},
					),
			).withPermission("story.settlement.collect")
			.executes(
				CommandExecutor { sender, _ ->
					sender.sendMessage("${ChatColor.GOLD}=== ${ChatColor.YELLOW}Settlement Collection Commands ${ChatColor.GOLD}===")
					sender.sendMessage("${ChatColor.GRAY}/settlement collect taxes - Collect taxes from a settlement")
					sender.sendMessage(
						"${ChatColor.GRAY}/settlement collect alltaxes - Collect taxes from all settlements in a faction",
					)
				},
			)
	}
}
