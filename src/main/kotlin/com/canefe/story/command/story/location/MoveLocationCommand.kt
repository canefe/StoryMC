package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import org.bukkit.Location

class MoveLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("move")
            .withArguments(GreedyStringArgument("location_name"))
            .withUsage(
                "/story location move <location_name>",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val locationName = args["location_name"] as String
                    val playerLocation: Location = player.location

                    // Find existing location
                    val location =
                        commandUtils.locationManager.getLocation(locationName) ?: run {
                            player.sendError("Location '$locationName' does not exist.")
                            return@PlayerCommandExecutor
                        }

                    // Update location's coordinates
                    location.bukkitLocation = playerLocation

                    commandUtils.locationManager.saveLocation(location)

                    player.sendSuccess(
                        "Location <gold>'$locationName'</gold> updated successfully at your current location.",
                    )
                },
            )
    }
}
