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
import org.bukkit.Bukkit
import kotlin.text.get

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
            .withSubcommand(getNendCommand())
            .withSubcommand(getHistoryRemoveSubcommand())
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

    private fun getNendCommand(): CommandAPICommand {
        return CommandAPICommand("nend")
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
                    commandUtils.conversationManager.endConversation(convo, dontRemember = true)
                    sender.sendMessage(mm.deserialize("<green>Conversation ended.</green>"))
                },
            )
    }

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

                    conversation.history.forEachIndexed { index, message ->
                        if (message.content === "...") {
                            return@forEachIndexed
                        }

                        val deleteButton = "<red>[<click:run_command:/conv hisremove $id $index>-</click>]</red> "

                        if (message.role == "system") {
                            sender.sendRaw("$deleteButton<gray><i>${message.content}</i></gray>")
                        } else if (message.role == "user") {
                            val name = message.content.substringBefore(":")
                            val content = message.content.substringAfter(":")
                            val formattedContent =
                                content.replace(
                                    Regex("\\*(.*?)\\*"),
                                    "<gray><i>$1</i></gray>",
                                )
                            sender.sendRaw("$deleteButton<green>$name:</green> <yellow>$formattedContent</yellow>")
                        } else if (message.role == "assistant") {
                            val name = message.content.substringBefore(":")
                            val content = message.content.substringAfter(":")
                            val formattedContent =
                                content.replace(
                                    Regex("\\*(.*?)\\*"),
                                    "<gray><i>$1</i></gray>",
                                )
                            sender.sendRaw("$deleteButton<green>$name:</green> <white>$formattedContent</white>")
                        }
                    }
                },
            )

    private fun getHistoryRemoveSubcommand(): CommandAPICommand =
        CommandAPICommand("hisremove")
            .withArguments(IntegerArgument("conversation_id"))
            .withArguments(IntegerArgument("message_index"))
            .executes(
                CommandExecutor { sender, args ->
                    val id = args["conversation_id"] as Int
                    val index = args["message_index"] as Int

                    val conversation =
                        commandUtils.conversationManager.getConversationById(id)
                            ?: return@CommandExecutor sender.sendError("Invalid conversation ID.")

                    if (index < 0 || index >= conversation.history.size) {
                        return@CommandExecutor sender.sendError("Invalid message index.")
                    }

                    // Need to modify Conversation to support this
                    if (conversation.removeHistoryMessageAt(index)) {
                        sender.sendSuccess("Message removed from conversation history.")
                    } else {
                        sender.sendError("Failed to remove message from conversation history.")
                    }

                    // Make them execute the show command to see the changes
                    Bukkit.dispatchCommand(
                        sender,
                        "conv show $id",
                    )
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
