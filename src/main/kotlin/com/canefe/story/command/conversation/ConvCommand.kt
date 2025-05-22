package com.canefe.story.command.conversation

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class ConvCommand(
	private val plugin: Story,
) : BaseCommand {
	private val commandUtils = ConvCommandUtils()

	override fun register() {
		CommandAPICommand("conv")
			.withPermission("story.conv")
			.withSubcommand(getListSubcommand())
			.withSubcommand(getNPCSubcommand())
			.withSubcommand(getRemoveSubcommand())
			.withSubcommand(getFeedSubcommand())
			.withSubcommand(getEndSubcommand())
			.withSubcommand(getForceEndSubcommand())
			.withSubcommand(getEndAllSubcommand())
			.withSubcommand(getMuteSubcommand())
			.withSubcommand(getAddSubcommand())
			.withSubcommand(getToggleSubcommand())
			.withSubcommand(getSpySubcommand())
			.withSubcommand(getLockSubcommand())
			.withSubcommand(getToggleGlobalHearingSubcommand())
			.withSubcommand(getContinueSubcommand())
			.withSubcommand(getShowSubcommand())
			.register()
	}

	private fun getListSubcommand(): CommandAPICommand = ConvListCommand(commandUtils).getCommand()

	private fun getNPCSubcommand(): CommandAPICommand = ConvNPCCommand(commandUtils).getCommand()

	private fun getRemoveSubcommand(): CommandAPICommand = ConvRemoveCommand(commandUtils).getCommand()

	private fun getFeedSubcommand(): CommandAPICommand = ConvFeedCommand(commandUtils).getCommand()

	private fun getEndSubcommand(): CommandAPICommand = ConvEndCommand(commandUtils).getCommand()

	private fun getForceEndSubcommand(): CommandAPICommand = ConvForceEndCommand(commandUtils).getCommand()

	private fun getEndAllSubcommand(): CommandAPICommand = ConvEndAllCommand(commandUtils).getCommand()

	private fun getMuteSubcommand(): CommandAPICommand = ConvMuteCommand(commandUtils).getCommand()

	private fun getAddSubcommand(): CommandAPICommand = ConvAddCommand(commandUtils).getCommand()

	private fun getToggleSubcommand(): CommandAPICommand = ConvToggleCommand(commandUtils).getCommand()

	private fun getSpySubcommand(): CommandAPICommand = ConvSpyCommand(commandUtils).getCommand()

	private fun getLockSubcommand(): CommandAPICommand = ConvLockCommand(commandUtils).getCommand()

	private fun getContinueSubcommand(): CommandAPICommand =
		CommandAPICommand("continue")
			.withArguments(IntegerArgument("conversation_id"))
			.executesPlayer(
				PlayerCommandExecutor { player, args ->
					val id = args["conversation_id"] as Int

					val conversation =
						commandUtils.conversationManager.getConversationById(id)
							?: return@PlayerCommandExecutor player.sendError("Invalid conversation ID.")

					player.sendSuccess("Continuing conversation with ID $id...")
					plugin.conversationManager.generateResponses(conversation)
				},
			)

	private fun getShowSubcommand(): CommandAPICommand =
		CommandAPICommand("show")
			.withArguments(IntegerArgument("conversation_id"))
			.executes(
				CommandExecutor { sender, args ->
					val id = args["conversation_id"] as Int

					val conversation =
						commandUtils.conversationManager.getConversationById(id)
							?: return@CommandExecutor sender.sendError("Invalid conversation ID.")

					sender.sendRaw("<gray>=====<green>[${conversation.id}]</green>=====</gray>")
					for (message in conversation.history) {
						if (message.content === "...") {
							continue
						}
						if (message.role == "system") {
							sender.sendRaw("<gray><i>${message.content}</i>")
						} else if (message.role == "user") {
							// extract until ':' from the message and the rest is the content (the first : only)
							val name = message.content.substringBefore(":")
							val content = message.content.substringAfter(":")
							// in the content format words between *words* as <gray><i>words</i></gray>
							val formattedContent =
								content.replace(
									Regex("\\*(.*?)\\*"),
									"<gray><i>$1</i></gray>",
								)
							sender.sendRaw("<green>$name:</green> <yellow>$formattedContent</yellow>")
						} else if (message.role == "assistant") {
							// extract until ':' from the message and the rest is the content (the first : only)
							val name = message.content.substringBefore(":")
							val content = message.content.substringAfter(":")
							val formattedContent =
								content.replace(
									Regex("\\*(.*?)\\*"),
									"<gray><i>$1</i></gray>",
								)
							sender.sendRaw("<green>$name:</green> <aqua>$formattedContent</aqua>")
						}
					}

					// Show the conversation history for the sender
				},
			)

	private fun getToggleGlobalHearingSubcommand(): CommandAPICommand =
		CommandAPICommand("toggleglobal")
			.executesPlayer(
				PlayerCommandExecutor { player, _ ->

					val disabledHearing = commandUtils.story.playerManager.disabledHearing

					val isGlobalHearingEnabled = !disabledHearing.contains(player.uniqueId)

					if (isGlobalHearingEnabled) {
						disabledHearing.add(player.uniqueId)

						player.sendMessage("Global hearing disabled.")
					} else {
						disabledHearing.remove(player.uniqueId)

						player.sendMessage("Global hearing enabled.")
					}
				},
			)
}
