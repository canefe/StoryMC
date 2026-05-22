package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class RadiusLocationCommand(
    private val plugin: Story,
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("radius")
            .withArguments(
                TextArgument("location_name"),
                DoubleArgument("radius"),
            ).withUsage(
                "/story location radius <location_name> <radius>",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val name = args["location_name"] as String
                    val radius = args["radius"] as Double

                    val loc = commandUtils.locationManager.getLocation(name)
                    if (loc == null) {
                        player.sendError("No location '$name'.")
                        return@PlayerCommandExecutor
                    }

                    loc.radius = radius
                    commandUtils.locationManager.saveLocation(loc)
                    emitLocationToSim(plugin, loc)

                    player.sendSuccess(
                        "Location <gold>'$name'</gold> radius set to $radius.",
                    )
                },
            )
}
