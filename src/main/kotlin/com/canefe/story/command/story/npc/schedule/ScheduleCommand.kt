package com.canefe.story.command.story.npc.schedule

import com.canefe.story.npc.NPCScheduleManager
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor


class ScheduleCommand(
    private val commandUtils: ScheduleCommandUtils
) {

    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("schedule")
            .withPermission("story.location")
            .withSubcommand(getCreateScheduleCommand())
            .withSubcommand(getSetScheduleCommand())
    }

    private fun getCreateScheduleCommand(): CommandAPICommand {
        return CommandAPICommand("create")
            .withArguments(GreedyStringArgument("npc_name"))
            .executes(CommandExecutor { sender, args ->
                val npcName = args.get("npc_name") as String

                // Check if the schedule already exists
                if (commandUtils.scheduleManager.loadSchedule(npcName) != null) {
                    sender.sendError("Schedule for '$npcName' already exists.")
                    return@CommandExecutor
                }

                // Create the schedule
                val schedule = commandUtils.scheduleManager.getEmptyScheduleTemplate(npcName)
                commandUtils.scheduleManager.saveSchedule(schedule)
                sender.sendSuccess("Schedule for '$npcName' created successfully.")
            })
    }

    private fun getSetScheduleCommand(): CommandAPICommand {
        return CommandAPICommand("set")
            .withArguments(IntegerArgument("hour")
                .replaceSuggestions { info, builder ->
                    val suggestions = (0..23).map { it.toString() }
                    suggestions.forEach {
                        builder.suggest(it)
                    }
                    builder.buildFuture()
                })
            .withArguments(StringArgument("location_name").replaceSuggestions { info, builder ->
                val locations = commandUtils.locationManager.getAllLocations()
                val suggestions = locations.map { it.name }
                    .distinct()
                suggestions.forEach {
                    builder.suggest(it)
                }
                builder.buildFuture()
            })
            .withArguments(GreedyStringArgument("npc_name").replaceSuggestions { info, builder ->
                val schedules = commandUtils.scheduleManager.schedules
                val suggestions = schedules.keys
                    .distinct()
                suggestions.forEach {
                    builder.suggest(it)
                }
                builder.buildFuture()
            })
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val npcName = args.get("npc_name") as String
                val locationName = args.get("location_name") as String
                val hour = args.get("hour") as Int

                // Check if the schedule exists
                val schedule = commandUtils.scheduleManager.loadSchedule(npcName)
                if (schedule == null) {
                    player.sendError("Schedule for '$npcName' does not exist.")
                    return@PlayerCommandExecutor
                }

                // Check if location exists
                val location = commandUtils.locationManager.getLocation(locationName) ?: run {
                    // create new one
                    commandUtils.locationManager.createLocation(locationName, player.location)
                }

                // Return if location is null
                if (location == null) {
                    player.sendError("Location '$locationName' does not exist.")
                    return@PlayerCommandExecutor
                }

                schedule.entries
                    .firstOrNull { it.time == hour }
                    ?.let {
                        it.locationName = location.name
                    } ?: run {
                        // Create a new entry if it doesn't exist
                        val newEntry = NPCScheduleManager.ScheduleEntry(hour, location.name, "idle", null)
                        schedule.addEntry(newEntry)
                    }



                // Set the schedule
                commandUtils.scheduleManager.saveSchedule(schedule)
                player.sendSuccess("Schedule for '$npcName' set successfully.")
            })
    }


}