package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import org.bukkit.Location

class CreateLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("create")
            .withArguments(GreedyStringArgument("location_name"))
            .withUsage(
                "/story location create <location_name>",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val locationName = args["location_name"] as String
                    val playerLocation: Location = player.location

                    val location =
                        commandUtils.locationManager.createLocation(locationName, playerLocation) ?: run {
                            player.sendError("Failed to create location. The location name may already exist.")
                            return@PlayerCommandExecutor
                        }

                    commandUtils.locationManager.saveLocation(location)

                    player.sendSuccess(
                        "Location <gold>'$locationName'</gold> created successfully at your current location.",
                    )
                },
            )
    }
}
