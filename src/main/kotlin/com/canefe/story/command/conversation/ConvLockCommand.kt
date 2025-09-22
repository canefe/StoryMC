package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvLockCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("lock")
            .withArguments(IntegerArgument("conversation_id"))
            .executes(
                CommandExecutor { sender, args ->
                    val id = args.get("conversation_id") as Int

                    // Get conversation and check if it exists
                    val convo = commandUtils.getConversation(id, sender) ?: return@CommandExecutor

                    // Check if the conversation is already locke, unlock
                    if (commandUtils.conversationManager.isConversationLocked(convo)) {
                        commandUtils.conversationManager.unlockConversation(convo)
                        sender.sendSuccess("Conversation $id is now unlocked.")
                        return@CommandExecutor
                    }

                    // Lock the conversation
                    commandUtils.conversationManager.lockConversation(convo)
                    sender.sendSuccess("Conversation $id is now locked.")
                },
            )
    }
}
