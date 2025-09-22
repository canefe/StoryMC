package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor

class ConvToggleCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("toggle")
            .withArguments(
                dev.jorel.commandapi.arguments
                    .IntegerArgument("conversation_id"),
            ).executes(
                CommandExecutor { sender, args ->
                    val id = args.get("conversation_id") as Int

                    // Get Conversation
                    val conversation = commandUtils.getConversation(id, sender)

                    if (conversation != null) {
                        conversation.chatEnabled = !conversation.chatEnabled
                        if (conversation.chatEnabled) {
                            sender.sendSuccess("Conversation $id is now enabled.")
                        } else {
                            sender.sendSuccess("Conversation $id is now disabled.")
                        }
                    }
                },
            )
}
