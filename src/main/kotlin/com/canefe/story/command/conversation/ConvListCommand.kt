package com.canefe.story.command.conversation

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class ConvListCommand(
    private val commandUtils: ConvCommandUtils
) {

    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("list")
            .withPermission("story.conv.list")
            .executesPlayer(PlayerCommandExecutor { player, _ ->
                displayActiveConversations(player)
            })
            .executes(CommandExecutor { sender, _ ->
                displayActiveConversations(sender)
            })
    }

    private fun displayActiveConversations(player: CommandSender) {
        player.sendMessage("  ")
        val mm = commandUtils.mm


        // Refresh Command
        val refreshCommand = commandUtils.createButton("--R--", "green",
            "run_command", "/conv list",
            "Refresh the list of active conversations")

        val title = mm.deserialize("<gold>==== <yellow>Active Conversations</yellow> ====</gold>")
            .append(mm.deserialize(" "))
            .append(refreshCommand)
        player.sendMessage(title)
        player.sendMessage("  ")
        val activeConversations = commandUtils.conversationManager.activeConversations
        if (activeConversations.isEmpty()) {
            player.sendMessage("§7No active conversations.")
            return
        }

        activeConversations.forEach { convo ->
            val npcNames = convo.npcNames
            val playerNames = convo.players
                .mapNotNull { Bukkit.getPlayer(it)?.name ?: "Unknown" }
                .joinToString(", ")

            // Create prefix with conversation ID and participants
            val prefix = createConversationPrefix(convo.id, npcNames, playerNames)

            // Create action buttons
            val commands = createActionButtons(convo.id, npcNames)

            // Send to player
            player.sendMessage(prefix)
            player.sendMessage(commands)
            player.sendMessage("  ")
        }
    }

    private fun createConversationPrefix(id: Int, npcNames: List<String>, playerNames: String): Component {
        // Build the prefix with conversation ID
        val miniMessage = commandUtils.mm
        val prefix = miniMessage.deserialize("<gray>[<green>$id</green>] </gray>")

        // Append clickable NPC names
        val clickableNpcNames = createClickableNpcNames(id, npcNames)

        // Combine with player names
        return prefix
            .append(clickableNpcNames)
            .append(miniMessage.deserialize("<gray>, <yellow>$playerNames</yellow> </gray>"))
    }

    private fun createClickableNpcNames(id: Int, npcNames: List<String>): Component {
        val miniMessage = commandUtils.mm
        var clickableNames = Component.empty()
        var first = true

        for (npcName in npcNames) {
            val escapedName = commandUtils.escapeForCommand(npcName)
            val npcComponent = miniMessage.deserialize(
                "<click:run_command:'/conv npc $id $escapedName'>" +
                        "<hover:show_text:'Control $escapedName'>" +
                        "<aqua>$npcName</aqua></hover></click>"
            )

            if (!first) {
                clickableNames = clickableNames.append(miniMessage.deserialize("<gray>, </gray>"))
            } else {
                first = false
            }

            clickableNames = clickableNames.append(npcComponent)
        }

        return clickableNames
    }

    private fun createActionButtons(id: Int, npcNames: List<String>): Component {
        // Build all the buttons
        val feedButton = commandUtils.createButton("Feed", "yellow",
            "suggest_command", "/conv feed $id Make ",
            "Add system message to conversation")

        val randomNpcName = npcNames.randomOrNull() ?: ""
        val talkButton = commandUtils.createButton("Talk", "gold",
            "run_command", "/maketalk ${commandUtils.escapeForCommand(randomNpcName)}",
            "Make Conversation Continue")

        val addButton = commandUtils.createButton("Add", "green",
            "suggest_command", "/conv add $id \"",
            "Add NPC to conversation")

        val toggleColor = if (Story.instance.conversationManager.getConversationById(id)?.chatEnabled == true) {
            "#00FF00"
        } else {
            "red"
        }
        val toggleButton = commandUtils.createButton("Toggle", toggleColor,
            "run_command", "/conv toggle $id",
            "Toggle conversation")

        val forceEndButton = commandUtils.createButton("F-End", "red",
            "run_command", "/conv fend $id",
            "Force End conversation")

        val endButton = commandUtils.createButton("End", "#FFC0CB",
            "run_command", "/conv end $id",
            "End conversation")

        // Combine all buttons with separators
        return commandUtils.combineComponentsWithSeparator(
            listOf(feedButton, talkButton, addButton, toggleButton, forceEndButton, endButton),
            "<gray> | </gray>"
        )
    }
}