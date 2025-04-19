package com.canefe.story.command.conversation

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandExecutor

class ConvRemoveCommand(
	private val commandUtils: ConvCommandUtils,
) {
	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("remove")
			.withArguments(IntegerArgument("conversation_id"))
			.withArguments(GreedyStringArgument("npc_name"))
			.executes(
				CommandExecutor { sender, args ->
					val id = args.get("conversation_id") as Int
					val npcName = args.get("npc_name") as String

					// Get conversation
					val convo =
						commandUtils.getConversation(id, sender)
							?: return@CommandExecutor

					if (!convo.npcNames.contains(npcName)) {
						sender.sendError("NPC '$npcName' is not spawned or does not exist.")
						return@CommandExecutor
					}

					// Clean up all NPCs in the conversation
					val npcs = convo.npcs

					commandUtils.conversationManager.cleanupHolograms(convo)

					// Find the NPC by name
					val npc =
						npcs.find { it.name == npcName }
							?: run {
								sender.sendError("NPC '$npcName' not found in conversation.")
								return@CommandExecutor
							}

					// Remove NPC from conversation first
					commandUtils.conversationManager.removeNPC(npc, convo)

					// End conversation if no NPCs left
					if (npcs.isEmpty()) {
						sender.sendInfo("Conversation ended as there are no NPCs left.")
					} else {
						sender.sendInfo("NPC '$npcName' removed from conversation.")
					}

					// Make the NPC walk away using the NPCManager
					if (npc.isSpawned) {
						commandUtils.npcManager.makeNPCWalkAway(npc, convo)
					}
				},
			)
	}
}
