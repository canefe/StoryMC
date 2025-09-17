package com.canefe.story.command.conversation

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvForceEndCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("fend")
            .withArguments(IntegerArgument("conversation_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val id = args.get("conversation_id") as Int
                    val mm = commandUtils.mm

                    // Get conversation
                    val convo =
                        commandUtils.getConversation(id, sender)
                            ?: return@CommandExecutor

                    // End conversation
                    commandUtils.conversationManager.endConversation(convo)
                    sender.sendMessage(mm.deserialize("<green>Conversation ended.</green>"))
                },
            )
    }
}
