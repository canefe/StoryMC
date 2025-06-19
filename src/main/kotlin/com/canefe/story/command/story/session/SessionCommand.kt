package com.canefe.story.command.story.session

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.CommandExecutor

class SessionCommand(private val plugin: Story) {
	private val utils = SessionCommandUtils()

	fun getCommand(): CommandAPICommand = CommandAPICommand("session")
		.withPermission("story.session")
		.withSubcommand(getStartCommand())
		.withSubcommand(getAddCommand())
		.withSubcommand(getFeedCommand())
		.withSubcommand(getEndCommand())

	private fun getStartCommand(): CommandAPICommand = CommandAPICommand("start")
		.executes(
			CommandExecutor { sender, _ ->
				utils.sessionManager.startSession()
				sender.sendSuccess("Session started.")
			},
		)

	private fun getEndCommand(): CommandAPICommand = CommandAPICommand("end")
		.executes(
			CommandExecutor { sender, _ ->
				utils.sessionManager.endSession()
				sender.sendSuccess("Session ended.")
			},
		)

	private fun getAddCommand(): CommandAPICommand = CommandAPICommand("add")
		.withArguments(
			StringArgument("player")
				.replaceSuggestions(
					ArgumentSuggestions.strings { _ ->
						plugin.server.onlinePlayers.map { it.name }.toTypedArray()
					},
				),
		)
		.executes(
			CommandExecutor { sender, args ->
				val name = args.get("player") as String
				utils.sessionManager.addPlayer(name)
				sender.sendSuccess("Added $name to session.")
			},
		)

	private fun getFeedCommand(): CommandAPICommand = CommandAPICommand("feed")
		.withArguments(GreedyStringArgument("text"))
		.executes(
			CommandExecutor { sender, args ->
				val text = args.get("text") as String
				utils.sessionManager.feed(text)
				sender.sendSuccess("Noted: $text")
			},
		)
}
