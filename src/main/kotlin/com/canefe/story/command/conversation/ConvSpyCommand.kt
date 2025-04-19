package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor

class ConvSpyCommand(
	private val commandUtils: ConvCommandUtils,
) {
	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("endall")
			.executes(
				CommandExecutor { sender, args ->
					// Stop all conversations
					commandUtils.conversationManager.stopAllConversations()
					sender.sendSuccess("All conversations ended.")
				},
			)
	}
}
