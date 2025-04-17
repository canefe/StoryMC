package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvEndCommand(
    private val commandUtils: ConvCommandUtils
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("end")
            .withArguments(IntegerArgument("conversation_id"))
            .executes(CommandExecutor { sender, args ->
                val id = args.get("conversation_id") as Int

                // Get conversation
                val convo = commandUtils.getConversation(id, sender) ?:
                    return@CommandExecutor


                // Add System Message
                convo.addSystemMessage("Each NPC should now deliver a final line or action that reflects their current feelings and intentions. Let them exit the scene naturally — avoid stating that the conversation is ending.")

                commandUtils.conversationManager.generateGroupNPCResponses(convo, null, null).thenRun {
                    // End conversation
                    commandUtils.conversationManager.endConversation(convo)
                    sender.sendSuccess("Conversation ended.")
                }

                sender.sendInfo("Ending conversation with ID $id...")

            })
    }
}