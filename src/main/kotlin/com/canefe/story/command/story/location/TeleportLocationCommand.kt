package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.PlayerArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.ConsoleCommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class TeleportLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("teleport")
            .withArguments(TextArgument("location_name"))
            .withOptionalArguments(PlayerArgument("player"))
            .withUsage("/story location teleport [player]")
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val locationName = args["location_name"] as String
                    val targetPlayer = (args["player"] as? org.bukkit.entity.Player) ?: player
                    // The argument is optional when run by a player; defaults to self
                    // Teleport the target player to the command sender's location
                    val location =
                        commandUtils.locationManager.getLocation(locationName)
                            ?: run {
                                player.sendError("Location '$locationName' does not exist.")
                                return@PlayerCommandExecutor
                            }
                    if (location.bukkitLocation == null) {
                        player.sendError("Location '$locationName' does not have a set Bukkit location.")
                        return@PlayerCommandExecutor
                    }
                    if (location.bukkitLocation?.world == null) {
                        player.sendError("Location '$locationName' is in an unloaded world.")
                        return@PlayerCommandExecutor
                    }
                    targetPlayer.teleport(location.bukkitLocation!!)
                    player.sendSuccess("Teleported ${targetPlayer.name} to location '$locationName'.")
                },
            ).executesConsole(
                ConsoleCommandExecutor { sender, args ->
                    // The argument becomes required when run from console
                    val targetPlayer =
                        args["player"] as? org.bukkit.entity.Player
                            ?: run {
                                sender.sendError("You must specify a player when running this command from console.")
                                return@ConsoleCommandExecutor
                            }
                    val locationName = args["location_name"] as String
                    val location =
                        commandUtils.locationManager.getLocation(locationName)
                            ?: run {
                                sender.sendError("Location '$locationName' does not exist.")
                                return@ConsoleCommandExecutor
                            }
                    if (location.bukkitLocation == null) {
                        sender.sendError("Location '$locationName' does not have a set Bukkit location.")
                        return@ConsoleCommandExecutor
                    }
                    if (location.bukkitLocation?.world == null) {
                        sender.sendError("Location '$locationName' is in an unloaded world.")
                        return@ConsoleCommandExecutor
                    }
                    targetPlayer.teleport(location.bukkitLocation!!)
                    sender.sendSuccess("Teleported ${targetPlayer.name} to location '$locationName'.")
                },
            )
}
