package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class ConvSpyCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("spy")
            .withArguments(IntegerArgument("conv_id"))
            .executesPlayer(
                PlayerCommandExecutor { sender, args ->
                    val id = args.get("conv_id") as Int

                    // Get conversation and check if it exists
                    val convo = commandUtils.getConversation(id, sender) ?: return@PlayerCommandExecutor

                    val isSpying = commandUtils.story.playerManager.getSpyingConversation(sender) == convo

                    if (isSpying) {
                        // Stop spying
                        commandUtils.story.playerManager.stopSpying(sender.uniqueId)
                        sender.sendSuccess("Stopped spying on conversation $id.")
                        return@PlayerCommandExecutor
                    } else {
                        // Start spying
                        commandUtils.story.playerManager.setSpyingConversation(sender.uniqueId, convo.id)
                        sender.sendSuccess("Started spying on conversation $id.")
                    }
                },
            )
    }
}
