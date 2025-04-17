package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendInfo
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvFeedCommand(
    private val commandUtils: ConvCommandUtils
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("feed")
            .withArguments(IntegerArgument("conversation_id"))
            .withArguments(GreedyStringArgument("message"))
            .executes(CommandExecutor { sender, args ->
                val id = args.get("conversation_id") as Int
                val message = args.get("message") as String

                val conversation = commandUtils.getConversation(id, sender) ?:
                    return@CommandExecutor


                conversation.addSystemMessage(message)
                val successMessage = "<green>Added system message: <gray><italic>'$message'</italic></gray> to conversation ID $id.</green>"
                sender.sendInfo(successMessage)

            })
    }
}