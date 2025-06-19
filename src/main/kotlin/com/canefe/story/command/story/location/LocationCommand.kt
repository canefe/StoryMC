package com.canefe.story.command.story.location

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand

class LocationCommand(private val plugin: Story) {
	private val commandUtils = LocationCommandUtils()

	fun getCommand(): CommandAPICommand = CommandAPICommand("location")
		.withPermission("story.location")
		.withUsage(
			"/story location <create> <name>",
		)
		.withSubcommand(getCreateLocationCommand())

	private fun getCreateLocationCommand(): CommandAPICommand = CreateLocationCommand(commandUtils).getCommand()
}
