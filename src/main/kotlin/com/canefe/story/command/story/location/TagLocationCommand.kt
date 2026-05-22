package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.MultiLiteralArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class TagLocationCommand(
    private val plugin: Story,
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("tag")
            .withArguments(
                TextArgument("location_name"),
                MultiLiteralArgument("op", "add", "remove"),
                StringArgument("tag"),
            ).withUsage(
                "/story location tag <location_name> <add|remove> <tag>",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val name = args["location_name"] as String
                    val op = args["op"] as String
                    val tag = args["tag"] as String

                    val loc = commandUtils.locationManager.getLocation(name)
                    if (loc == null) {
                        player.sendError("No location '$name'.")
                        return@PlayerCommandExecutor
                    }

                    if (op == "add") {
                        if (!loc.tags.contains(tag)) loc.tags.add(tag)
                    } else {
                        loc.tags.remove(tag)
                    }

                    commandUtils.locationManager.saveLocation(loc)
                    emitLocationToSim(plugin, loc)

                    player.sendSuccess(
                        "Location <gold>'$name'</gold> tags: ${loc.tags.joinToString(", ")}",
                    )
                },
            )
}
