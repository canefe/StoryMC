package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendInfo
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvEndCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("end")
            .withArguments(IntegerArgument("conversation_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val id = args.get("conversation_id") as Int

                    // Get conversation
                    val conversation =
                        commandUtils.getConversation(id, sender)
                            ?: return@CommandExecutor

                    sender.sendInfo("Ending conversation with ID $id...")

                    // Generate goodbye
                    commandUtils.conversationManager.endConversationWithGoodbye(
                        conversation,
                    )
                },
            )
    }
}
