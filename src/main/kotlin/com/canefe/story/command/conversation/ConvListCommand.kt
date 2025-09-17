package com.canefe.story.command.conversation

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class ConvListCommand(
    private val commandUtils: ConvCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("list")
            .withPermission("story.conv.list")
            .executesPlayer(
                PlayerCommandExecutor { player, _ ->
                    displayActiveConversations(player)
                },
            ).executes(
                CommandExecutor { sender, _ ->
                    displayActiveConversations(sender)
                },
            )

    private fun displayActiveConversations(player: CommandSender) {
        player.sendMessage("  ")
        val mm = commandUtils.mm

        // Refresh Command
        val refreshCommand =
            commandUtils.createButton(
                "--R--",
                "green",
                "run_command",
                "/conv list",
                "Refresh the list of active conversations",
            )

        val title =
            mm
                .deserialize("<gold>==== <yellow>Active Conversations</yellow> ====</gold>")
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
            val playerNames =
                convo.players
                    .mapNotNull { Bukkit.getPlayer(it)?.name ?: "Unknown" }

            // Create prefix with conversation ID and participants
            val prefix = createConversationPrefix(convo.id, npcNames, playerNames)

            // Create action buttons
            val commands = createActionButtons(convo.id, npcNames)

            // Send to player
            for (component in prefix) {
                player.sendMessage(component)
            }
            player.sendMessage(commands)
            player.sendMessage("  ")
        }
    }

    private fun createConversationPrefix(
        id: Int,
        npcNames: List<String>,
        playerNames: List<String>,
    ): List<Component> {
        // Build the prefix with conversation ID
        val miniMessage = commandUtils.mm
        val componentList = mutableListOf<Component>()
        val prefix = miniMessage.deserialize("<gray>=====<green>[$id]</green>=====</gray>")

        // Append clickable NPC names
        val clickableNpcNames = createClickableNpcNames(id, npcNames)

        componentList.add(prefix)

        componentList.addAll(clickableNpcNames)

        componentList.addAll(
            playerNames.map { name ->
                miniMessage.deserialize("<yellow>$name</yellow>")
            },
        )

        return componentList
    }

    private fun createClickableNpcNames(
        id: Int,
        npcNames: List<String>,
    ): List<Component> {
        val miniMessage = commandUtils.mm
        var clickableNames = Component.empty()
        var first = true
        val componentList = mutableListOf<Component>()

        for (npcName in npcNames) {
            val escapedName = commandUtils.escapeForCommand(npcName)
            val npcComponent =
                miniMessage.deserialize(
                    "<click:run_command:'/setcurnpc $escapedName'>" +
                        "<hover:show_text:'Control $escapedName'>" +
                        "<aqua>$npcName</aqua></hover></click>",
                )

            if (!first) {
                clickableNames = clickableNames.append(miniMessage.deserialize("<gray>, </gray>"))
            } else {
                first = false
            }

            componentList.add(
                // First add the name component with consistent width
                npcComponent
                    // Add some padding spaces after the NPC name
                    .append(miniMessage.deserialize("<aqua>${" ".repeat(calculatePadding(npcName, npcNames))}</aqua>"))
                    // Then append action buttons
                    .append(createNpcActionButtons(id, escapedName)),
            )
        }

        return componentList
    }

    private fun calculatePadding(
        currentName: String,
        allNames: List<String>,
    ): Int {
        val longestNameLength = allNames.maxOfOrNull { it.length } ?: 0
        return (longestNameLength - currentName.length) + 2 // 2 extra spaces for margin
    }

    // NPC-specific action buttons
    private fun createNpcActionButtons(
        id: Int,
        npcName: String,
    ): Component {
        // Build all the buttons
        val feedButton =
            commandUtils.createButton(
                "F",
                "yellow",
                "suggest_command",
                "/conv feed $id Make $npcName say ",
                "Add directive to $npcName",
            )

        val talkButton =
            commandUtils.createButton(
                "T",
                "gold",
                "run_command",
                "/maketalk $npcName",
                "Make $npcName talk",
            )

        val removeButton =
            commandUtils.createButton(
                "R",
                "red",
                "run_command",
                "/conv remove $id $npcName",
                "Remove NPC from conversation",
            )

        val muteButton =
            commandUtils.createButton(
                "M",
                "#8e44ad",
                "run_command",
                "/conv mute $id $npcName",
                "Mute $npcName",
            )

        // Combine all buttons with separators
        return commandUtils.combineComponentsWithSeparator(
            listOf(feedButton, talkButton, removeButton, muteButton),
            "<gray> | </gray>",
        )
    }

    // Create action buttons
    private fun createActionButtons(
        id: Int,
        npcNames: List<String>,
    ): Component {
        // Build all the buttons
        val feedButton =
            commandUtils.createButton(
                "Feed",
                "yellow",
                "suggest_command",
                "/conv feed $id Make ",
                "Add system message to conversation",
            )

        val talkButton =
            commandUtils.createButton(
                "Talk",
                "gold",
                "run_command",
                "/conv continue $id",
                "Make Conversation Continue",
            )

        val addButton =
            commandUtils.createButton(
                "Add",
                "green",
                "suggest_command",
                "/conv add $id \"",
                "Add NPC to conversation",
            )

        val showContextButton =
            commandUtils.createButton(
                "Show",
                "#00FF00",
                "run_command",
                "/conv show $id",
                "Show conversation context",
            )

        val toggleColor =
            if (Story.instance.conversationManager
                    .getConversationById(id)
                    ?.chatEnabled == true
            ) {
                "#00FF00"
            } else {
                "red"
            }
        val toggleButton =
            commandUtils.createButton(
                "Toggle",
                toggleColor,
                "run_command",
                "/conv toggle $id",
                "Toggle conversation",
            )

        val forceEndButton =
            commandUtils.createButton(
                "F-End",
                "red",
                "run_command",
                "/conv fend $id",
                "Force End conversation",
            )

        val endButton =
            commandUtils.createButton(
                "End",
                "#FFC0CB",
                "run_command",
                "/conv end $id",
                "End conversation",
            )

        val endNoRememberButton =
            commandUtils.createButton(
                "N-End",
                "#FFC0CB",
                "run_command",
                "/conv nend $id",
                "End conversation without remembering",
            )

        // Combine all buttons with separators
        return commandUtils.combineComponentsWithSeparator(
            listOf(
                feedButton,
                talkButton,
                addButton,
                showContextButton,
                toggleButton,
                forceEndButton,
                endButton,
                endNoRememberButton,
            ),
            "<gray> | </gray>",
        )
    }
}
