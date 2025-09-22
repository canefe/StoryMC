package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class CallLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("call")
            .withOptionalArguments(DoubleArgument("radius"))
            .withUsage("/story location call [radius]")
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val playerLocation = player.location
                    val radius = (args["radius"] as? Double) ?: 50.0 // Default 50 block radius
                    val scheduleManager = commandUtils.story.scheduleManager

                    try {
                        // Call all nearby NPCs to the player's location
                        scheduleManager.moveNearbyNPCsToLocation(
                            targetLocation = playerLocation,
                            proximityRadius = radius,
                            action = "idle", // Default action when they arrive
                        )

                        player.sendSuccess(
                            "Called all nearby NPCs within $radius blocks to your location. They will arrive shortly.",
                        )

                        // Optional: Send a follow-up message with more details
                        if (commandUtils.story.config.debugMessages) {
                            player.sendMessage(
                                commandUtils.mm.deserialize(
                                    "<gray>Search radius: $radius blocks | Action on arrival: idle</gray>",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        player.sendError("Failed to call NPCs: ${e.message}")
                        commandUtils.story.logger.warning("Error in call command: ${e.message}")
                        e.printStackTrace()
                    }
                },
            )
}
