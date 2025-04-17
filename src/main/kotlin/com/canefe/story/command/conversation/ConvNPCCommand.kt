package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.citizensnpcs.api.CitizensAPI

class ConvNPCCommand(
    private val commandUtils: ConvCommandUtils
) {

    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("npc")
            .withArguments(IntegerArgument("conversation_id"))
            .withArguments(GreedyStringArgument("npc_name")
                .replaceSuggestions(ArgumentSuggestions.strings { _ ->
                    // Get all NPCs from Citizens and convert to array
                    val npcNames = ArrayList<String>()
                    CitizensAPI.getNPCRegistry().forEach { citizenNPC ->
                        npcNames.add(citizenNPC.name)
                    }
                    npcNames.toTypedArray()
                }))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args.get("conversation_id") as Int
                val npcName = args.get("npc_name") as String

                if (npcName.isEmpty()) {
                    player.sendError("Invalid NPC name.")
                    return@PlayerCommandExecutor
                }

                // Verify conversation and NPC exist
                val convo = commandUtils.conversationManager.getConversationById(id)
                if (convo == null || !convo.npcNames.contains(npcName)) {
                    player.sendError("Invalid conversation or NPC not in this conversation.")
                    return@PlayerCommandExecutor
                }

                player.sendMessage("  ")
                // Build menu title
                val title = "<gold>==== NPC Controls: <yellow>$npcName</yellow> ====</gold>"
                player.sendInfo(title)
                player.sendMessage("  ")

                // Menu options
                val escapedNPCName = commandUtils.escapeForCommand(npcName)

                val removeButton = commandUtils.createButton("Remove", "red",
                    "run_command", "/conv remove $id $escapedNPCName",
                    "Remove this NPC from the conversation")

                val muteButton = commandUtils.createButton("Mute", "yellow",
                    "run_command", "/conv mute $id $escapedNPCName",
                    "Toggle whether this NPC speaks")

                val backButton = commandUtils.createButton("Back", "green",
                    "run_command", "/conv list",
                    "Back to conversation list")

                val buttons = commandUtils.combineComponentsWithSeparator(
                    listOf(removeButton, muteButton, backButton),
                    "<gray> | </gray>"
                )

                player.sendMessage(buttons)
            })
    }
}