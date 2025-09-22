package com.canefe.story.command.story.location

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand

class LocationCommand(
    private val plugin: Story,
) {
    private val commandUtils = LocationCommandUtils()

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("location")
            .withPermission("story.location")
            .withUsage(
                "/story location <create|find|call> [arguments]",
            ).withSubcommand(getCreateLocationCommand())
            .withSubcommand(getFindLocationCommand())
            .withSubcommand(getCallLocationCommand())
            .withSubcommand(getMoveLocationCommand())
            .withSubcommand(getUpdateLocationCommand())
            .withSubcommand(getTeleportLocationCommand())

    private fun getCreateLocationCommand(): CommandAPICommand = CreateLocationCommand(commandUtils).getCommand()

    private fun getFindLocationCommand(): CommandAPICommand = FindLocationCommand(commandUtils).getCommand()

    private fun getCallLocationCommand(): CommandAPICommand = CallLocationCommand(commandUtils).getCommand()

    private fun getMoveLocationCommand(): CommandAPICommand = MoveLocationCommand(commandUtils).getCommand()

    private fun getUpdateLocationCommand(): CommandAPICommand = UpdateLocationCommand(commandUtils).getCommand()

    private fun getTeleportLocationCommand(): CommandAPICommand = TeleportLocationCommand(commandUtils).getCommand()
}
