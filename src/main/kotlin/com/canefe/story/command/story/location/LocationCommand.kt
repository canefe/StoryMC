package com.canefe.story.command.story.location

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand

class LocationCommand(private val plugin: Story) {
	private val commandUtils = LocationCommandUtils()

	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("location")
			.withPermission("story.location")
			.withSubcommand(getCreateLocationCommand())
	}

	private fun getCreateLocationCommand(): CommandAPICommand {
		return CreateLocationCommand(commandUtils).getCommand()
	}
}
