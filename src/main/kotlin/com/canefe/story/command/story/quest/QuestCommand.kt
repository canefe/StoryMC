package com.canefe.story.command.story.quest

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand

class QuestCommand(private val plugin: Story) {
	private val commandUtils = QuestCommandUtils()

	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("quest")
			.withPermission("story.quest")
			.withSubcommand(getBookCommand())
	}

	private fun getBookCommand(): CommandAPICommand {
		return QuestBookCommand(commandUtils).getCommand()
	}
}
