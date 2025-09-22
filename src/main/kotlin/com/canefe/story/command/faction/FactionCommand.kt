package com.canefe.story.command.faction

import com.canefe.story.Story
import com.canefe.story.faction.FactionManager
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.CommandExecutor
import java.util.*

/**
 * Registers all faction-related commands using CommandAPI
 */
class FactionCommand(
    private val plugin: Story,
    private val factionManager: FactionManager,
) {
    /**
     * Register all faction commands
     */
    fun registerCommands() {
        registerFactionBaseCommand()
		/*
		registerTreasuryCommands()
		registerMineChestCommands()
		registerFactionResourceCommands()
		registerFactionConfigCommands()

		 */
    }

    /**
     * Register base faction commands
     */
    private fun registerFactionBaseCommand() {
        // Main faction command
        CommandAPICommand("faction")
            .withAliases("f")
            .withSubcommand(createFactionInfoCommand())
            .withSubcommand(createFactionCreateCommand())
            .withSubcommand(createFactionDeleteCommand())
            .withSubcommand(createFactionListCommand())
            .withSubcommand(createFactionSetPopulationCommand())
            .withSubcommand(createFactionSetHappinessCommand())
            .register()
    }

    /**
     * Creates faction info command
     */
    private fun createFactionInfoCommand(): CommandAPICommand {
        return CommandAPICommand("info")
            .withArguments(StringArgument("faction_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String
                    val faction = factionManager.getFaction(factionId)

                    if (faction == null) {
                        sender.sendError("Faction with ID '$factionId' does not exist!")
                        return@CommandExecutor
                    }

                    // Display faction information
                    sender.sendInfo("=== Faction: ${faction.name} (${faction.id}) ===")
                    sender.sendInfo("Description: ${faction.description}")
                    sender.sendInfo("Population: ${faction.populationCount}")
                    sender.sendInfo("Happiness: ${faction.happiness}")
                    sender.sendInfo("Treasury: ${faction.treasuryBalance}")
                },
            )
    }

    /**
     * Creates faction create command
     */
    private fun createFactionCreateCommand(): CommandAPICommand {
        return CommandAPICommand("create")
            .withPermission("faction.admin")
            .withArguments(StringArgument("faction_id"))
            .withArguments(StringArgument("name"))
            .withOptionalArguments(GreedyStringArgument("description"))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String
                    val name = args["name"] as String
                    val description = args.getOptional("description").orElse("A new faction") as String

                    if (factionManager.getFaction(factionId) != null) {
                        sender.sendError("Faction with ID '$factionId' already exists!")
                        return@CommandExecutor
                    }

                    val faction = factionManager.createFaction(factionId, name, description)
                    if (faction != null) {
                        sender.sendSuccess("Faction '${faction.name}' created with ID '${faction.id}'")
                    }
                },
            )
    }

    /**
     * Creates faction delete command
     */
    private fun createFactionDeleteCommand(): CommandAPICommand {
        return CommandAPICommand("delete")
            .withPermission("faction.admin")
            .withArguments(StringArgument("faction_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String

                    if (!factionManager.factionExists(factionId)) {
                        sender.sendError("Faction with ID '$factionId' does not exist!")
                        return@CommandExecutor
                    }

                    // Delete the faction
                    factionManager.deleteFaction(factionId)
                    sender.sendSuccess("Faction '$factionId' deleted!")
                },
            )
    }

    /**
     * Creates faction list command
     */
    private fun createFactionListCommand(): CommandAPICommand {
        return CommandAPICommand("list")
            .executes(
                CommandExecutor { sender, _ ->
                    val factions = factionManager.getAllFactions()

                    if (factions.isEmpty()) {
                        sender.sendInfo("No factions exist!")
                        return@CommandExecutor
                    }

                    sender.sendInfo("=== Factions (${factions.size}) ===")
                    factions.forEach { faction ->
                        sender.sendInfo("- ${faction.name} (${faction.id}): ${faction.populationCount} population")
                    }
                },
            )
    }

    /**
     * Creates faction set population command
     */
    private fun createFactionSetPopulationCommand(): CommandAPICommand {
        return CommandAPICommand("setpopulation")
            .withPermission("faction.admin")
            .withArguments(StringArgument("faction_id"))
            .withArguments(IntegerArgument("count", 0))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String
                    val count = args["count"] as Int

                    val faction = factionManager.getFaction(factionId)
                    if (faction == null) {
                        sender.sendError("Faction with ID '$factionId' does not exist!")
                        return@CommandExecutor
                    }

                    faction.setPopulation(count)
                    sender.sendSuccess("Set faction '${faction.name}' population to $count")
                },
            )
    }

    /**
     * Creates faction set happiness command
     */
    private fun createFactionSetHappinessCommand(): CommandAPICommand {
        return CommandAPICommand("sethappiness")
            .withPermission("faction.admin")
            .withArguments(StringArgument("faction_id"))
            .withArguments(DoubleArgument("happiness", 0.0, 1.0))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String
                    val happiness = args["happiness"] as Double

                    val faction = factionManager.getFaction(factionId)
                    if (faction == null) {
                        sender.sendError("Faction with ID '$factionId' does not exist!")
                        return@CommandExecutor
                    }

                    faction.setHappiness(happiness)
                    sender.sendSuccess("Set faction '${faction.name}' happiness to ${happiness * 100}%")
                },
            )
    }

    /**
     * Register treasury management commands

     private fun registerTreasuryCommands() {
     CommandAPICommand("treasury")
     .withAliases("t")
     .withSubcommand(createTreasuryAddCommand())
     .withSubcommand(createTreasuryListCommand())
     .withSubcommand(createTreasuryRemoveCommand())
     .withSubcommand(createTreasuryUpdateCommand())
     .register()
     }
     */
    /**
     * Creates treasury add command

     private fun createTreasuryAddCommand(): CommandAPICommand {
     return CommandAPICommand("add")
     .withPermission("faction.treasury")
     .withArguments(StringArgument("faction_id"))
     .executesPlayer(
     PlayerCommandExecutor { player, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     player.sendError("Faction with ID '$factionId' does not exist!")
     return@PlayerCommandExecutor
     }

     // Get target chest
     val target = player.getTargetBlock(null, 5)
     if (target.type != Material.CHEST) {
     player.sendError("You must be looking at a chest!")
     return@PlayerCommandExecutor
     }

     // Check if chest is already registered
     if (faction.treasuryChests.any { it.getLocation() == target.location }) {
     player.sendError("This chest is already registered as a treasury!")
     return@PlayerCommandExecutor
     }

     // Add the chest
     faction.addTreasuryChest(target)
     player.sendSuccess("Chest added to ${faction.name}'s treasury system!")

     // Update display immediately
     faction.updateTreasuryDisplay()
     },
     )
     }
     */
    /**
     * Creates treasury list command

     private fun createTreasuryListCommand(): CommandAPICommand {
     return CommandAPICommand("list")
     .withPermission("faction.treasury")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     val treasuries = faction.treasuryChests
     if (treasuries.isEmpty()) {
     sender.sendInfo("No treasury chests registered for faction '${faction.name}'")
     return@CommandExecutor
     }

     sender.sendInfo("=== ${faction.name} Treasury Chests (${treasuries.size}) ===")
     treasuries.forEachIndexed { index, chest ->

     sender.sendInfo("${index + 1}. ${chest.world} (${chest.x}, ${chest.y}, ${chest.z})")
     }
     },
     )
     }
     */
    /**
     * Creates treasury remove command

     private fun createTreasuryRemoveCommand(): CommandAPICommand {
     return CommandAPICommand("remove")
     .withPermission("faction.treasury")
     .withArguments(StringArgument("faction_id"))
     .withArguments(IntegerArgument("index", 1))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String
     val index = args["index"] as Int

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Remove chest by index
     if (faction.removeTreasuryChest(index - 1)) { // Convert to 0-based index
     sender.sendSuccess("Removed treasury chest from faction '${faction.name}'!")
     } else {
     sender.sendError("Invalid chest index! Use '/treasury list $factionId' to see available chests.")
     }
     },
     )
     }
     */
    /**
     * Creates treasury update command

     private fun createTreasuryUpdateCommand(): CommandAPICommand {
     return CommandAPICommand("update")
     .withPermission("faction.treasury")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Update treasury display
     faction.updateTreasuryDisplay()
     sender.sendSuccess("Updated treasury display for faction '${faction.name}'!")
     },
     )
     }
     */
    /**
     * Register mine chest management commands

     private fun registerMineChestCommands() {
     CommandAPICommand("minechest")
     .withAliases("mc")
     .withSubcommand(createMineChestAddCommand())
     .withSubcommand(createMineChestListCommand())
     .withSubcommand(createMineChestRemoveCommand())
     .withSubcommand(createMineChestCheckCommand())
     .withSubcommand(createMineChestCollectCommand())
     .register()
     }
     */
    /**
     * Creates mine chest add command

     private fun createMineChestAddCommand(): CommandAPICommand {
     return CommandAPICommand("add")
     .withPermission("faction.minechest")
     .withArguments(StringArgument("faction_id"))
     .executesPlayer(
     PlayerCommandExecutor { player, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     player.sendError("Faction with ID '$factionId' does not exist!")
     return@PlayerCommandExecutor
     }

     // Get target chest
     val target = player.getTargetBlock(null, 5)
     if (target.type != Material.CHEST) {
     player.sendError("You must be looking at a chest!")
     return@PlayerCommandExecutor
     }

     // Check if chest is already registered
     if (faction.isMineChest(target)) {
     player.sendError("This chest is already registered as a mine chest!")
     return@PlayerCommandExecutor
     }

     // Add the chest
     faction.addMineChest(target)
     player.sendSuccess("Chest added to ${faction.name}'s mine chest system!")
     },
     )
     }

     /**
     * Creates mine chest list command
     */
     private fun createMineChestListCommand(): CommandAPICommand {
     return CommandAPICommand("list")
     .withPermission("faction.minechest")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     val chests = faction.mineChests
     if (chests.isEmpty()) {
     sender.sendInfo("No mine chests registered for faction '${faction.name}'")
     return@CommandExecutor
     }

     sender.sendInfo("=== ${faction.name} Mine Chests (${chests.size}) ===")
     chests.forEachIndexed { index, chest ->
     sender.sendInfo("${index + 1}. ${chest.world} ${chest.x}, ${chest.y}, ${chest.z})")
     }
     },
     )
     }

     /**
     * Creates mine chest remove command
     */
     private fun createMineChestRemoveCommand(): CommandAPICommand {
     return CommandAPICommand("remove")
     .withPermission("faction.minechest")
     .withArguments(StringArgument("faction_id"))
     .withArguments(IntegerArgument("index", 1))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String
     val index = args["index"] as Int

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Remove chest by index
     if (faction.removeMineChest(index - 1)) { // Convert to 0-based index
     sender.sendSuccess("Removed mine chest from faction '${faction.name}'!")
     } else {
     sender.sendError("Invalid chest index! Use '/minechest list $factionId' to see available chests.")
     }
     },
     )
     }

     /**
     * Creates mine chest check command
     */
     private fun createMineChestCheckCommand(): CommandAPICommand {
     return CommandAPICommand("check")
     .withPermission("faction.minechest")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Check mine chest status
     val status = faction.getMineChestStatus()
     if (status.isEmpty()) {
     sender.sendInfo("No mine chests registered for faction '${faction.name}'")
     return@CommandExecutor
     }

     sender.sendInfo("=== ${faction.name} Mine Chest Status ===")
     status.forEach { line ->
     sender.sendInfo(line)
     }
     },
     )
     }

     /**
     * Creates mine chest collect command
     */
     private fun createMineChestCollectCommand(): CommandAPICommand {
     return CommandAPICommand("collect")
     .withPermission("faction.admin")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Collect daily mine income
     val income = faction.collectDailyMineIncome()
     sender.sendSuccess("Collected ${income.toPlainString()} for faction '${faction.name}'!")
     },
     )
     }

     /**
     * Register resource management commands
     */
     private fun registerFactionResourceCommands() {
     CommandAPICommand("resource")
     .withAliases("r")
     .withSubcommand(createResourceSetCommand())
     .withSubcommand(createResourceListCommand())
     .register()
     }

     /**
     * Creates resource set command
     */
     private fun createResourceSetCommand(): CommandAPICommand {
     return CommandAPICommand("set")
     .withPermission("faction.resource")
     .withArguments(StringArgument("faction_id"))
     .withArguments(
     StringArgument("resource_type").replaceSuggestions(
     ArgumentSuggestions.strings("FOOD", "WOOD", "STONE", "IRON", "COAL"),
     ),
     ).withArguments(DoubleArgument("level", 0.0, 1.0))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String
     val resourceType = args["resource_type"] as String
     val level = args["level"] as Double

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Set resource level
     try {
     val type =
     ResourceType
     .valueOf(resourceType.uppercase(Locale.getDefault()))
     faction.resources[type] = level
     sender.sendSuccess("Set ${faction.name}'s $resourceType level to ${level * 100}%")
     } catch (e: IllegalArgumentException) {
     sender.sendError("Invalid resource type: $resourceType")
     }
     },
     )
     }

     /**
     * Creates resource list command
     */
     private fun createResourceListCommand(): CommandAPICommand {
     return CommandAPICommand("list")
     .withArguments(StringArgument("faction_id"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // List all resources
     val resources = faction.resources

     sender.sendInfo("=== ${faction.name} Resources ===")
     resources.forEach { (type, level) ->
     sender.sendInfo("- ${type.name}: ${level * 100}%")
     }
     },
     )
     }

     /**
     * Register faction config commands
     */
     private fun registerFactionConfigCommands() {
     CommandAPICommand("fconfig")
     .withPermission("faction.admin")
     .withSubcommand(createConfigSetCommand())
     .withSubcommand(createConfigGetCommand())
     .register()
     }

     /**
     * Creates config set command
     */
     private fun createConfigSetCommand(): CommandAPICommand {
     return CommandAPICommand("set")
     .withArguments(StringArgument("faction_id"))
     .withArguments(
     StringArgument("key").replaceSuggestions(
     ArgumentSuggestions.strings("workdayHours", "minerCount", "minerSalary", "ironValue", "coalValue"),
     ),
     ).withArguments(DoubleArgument("value"))
     .executes(
     CommandExecutor { sender, args ->
     val factionId = args["faction_id"] as String
     val key = args["key"] as String
     val value = args["value"] as Double

     val faction = factionManager.getFaction(factionId)
     if (faction == null) {
     sender.sendError("Faction with ID '$factionId' does not exist!")
     return@CommandExecutor
     }

     // Set config value
     when (key) {
     "workdayHours" -> faction.config.workdayHours = value.toInt()
     "minerCount" -> faction.config.minerCount = value.toInt()
     "minerSalary" -> faction.config.minerSalary = value
     "ironValue" -> faction.config.ironValue = value
     "coalValue" -> faction.config.coalValue = value
     else -> {
     sender.sendError("Invalid config key: $key")
     return@CommandExecutor
     }
     }

     sender.sendSuccess("Set ${faction.name}'s $key to $value")
     },
     )
     }
     */

    /**
     * Creates config get command
     */
    private fun createConfigGetCommand(): CommandAPICommand {
        return CommandAPICommand("get")
            .withArguments(StringArgument("faction_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val factionId = args["faction_id"] as String

                    val faction = factionManager.getFaction(factionId)
                    if (faction == null) {
                        sender.sendError("Faction with ID '$factionId' does not exist!")
                        return@CommandExecutor
                    }

                    // Get all config values
                    sender.sendInfo("=== ${faction.name} Configuration ===")
                    sender.sendInfo("- Workday Hours: ${faction.config.workdayHours}")
                    sender.sendInfo("- Miner Count: ${faction.config.minerCount}")
                    sender.sendInfo("- Miner Salary: ${faction.config.minerSalary}")
                    sender.sendInfo("- Iron Value: ${faction.config.ironValue}")
                    sender.sendInfo("- Coal Value: ${faction.config.coalValue}")
                },
            )
    }
}
