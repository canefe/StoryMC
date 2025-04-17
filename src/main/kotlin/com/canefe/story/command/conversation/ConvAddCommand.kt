package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.CommandExecutor
import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import net.citizensnpcs.api.CitizensAPI


class ConvAddCommand(
    private val commandUtils: ConvCommandUtils
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("add")
            .withArguments(IntegerArgument("conversation_id"))
            .withArguments(TextArgument("npc_name")
                .replaceSuggestions(ArgumentSuggestions.strings { _ ->
                    // Get all NPCs from Citizens and convert to array
                    val npcNames = ArrayList<String>()
                    CitizensAPI.getNPCRegistry().forEach { citizenNPC ->
                        // add quotes around the name
                        npcNames.add("\"${citizenNPC.name}\"")
                    }
                    npcNames.toTypedArray()
                }))
            .withOptionalArguments(GreedyStringArgument("greeting_message"))
            .executes(CommandExecutor { sender, args ->
                val id = args.get("conversation_id") as Int
                var npcName = args.get("npc_name") as String
                val message = args.getOrDefault("greeting_message", "") as String

                val convo = commandUtils.conversationManager.getConversationById(id)

                if (convo == null) {
                    sender.sendError("Invalid conversation ID.")
                    return@CommandExecutor
                }

                npcName = npcName.replace("\"", "") // Remove quotes from the name

                // Find the NPC by name (use id) (npc is not in the convo loop all NPCs)
                for (npc in CitizensAPI.getNPCRegistry()) {
                    if (npc.name == npcName) {
                        // Check if the NPC is already in the conversation
                        if (convo.npcNames.contains(npc.name)) {
                            sender.sendError("NPC '$npcName' is already in this conversation.")
                            return@CommandExecutor
                        }
                        val success: Boolean =
                            commandUtils.conversationManager.addNPCToConversationWalk(npc, convo, message)
                        if (success) {
                            sender.sendSuccess("NPC '$npcName' added to conversation.")
                        } else {
                            sender.sendError("Failed to add NPC '$npcName' to conversation.")
                        }
                        return@CommandExecutor
                    }
                }
                sender.sendError("NPC '$npcName' not found.")

            })
    }
}