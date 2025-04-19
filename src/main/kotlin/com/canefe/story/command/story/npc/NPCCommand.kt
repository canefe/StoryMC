package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.command.story.npc.schedule.ScheduleCommand
import com.canefe.story.command.story.npc.schedule.ScheduleCommandUtils
import com.canefe.story.util.Msg.sendError
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.executors.CommandExecutor
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player

class NPCCommand(private val plugin: Story) {
	private val commandUtils = ScheduleCommandUtils()

	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("npc")
			.withPermission("story.npc")
			.withSubcommand(getScheduleCommand())
			.withSubcommand(getToggleCommand())
	}

	private fun getScheduleCommand(): CommandAPICommand {
		return ScheduleCommand(commandUtils).getCommand()
	}

	private fun getToggleCommand(): CommandAPICommand {
		return CommandAPICommand("toggle")
			.withPermission("story.npc.toggle")
			.withArguments(
				GreedyStringArgument("npc_name")
					.replaceSuggestions(
						ArgumentSuggestions.strings { _ ->
							// Get all NPCs from Citizens and convert to array
							val npcNames = ArrayList<String>()
							CitizensAPI.getNPCRegistry().forEach { citizenNPC ->
								// add quotes around the name
								npcNames.add(citizenNPC.name)
							}
							npcNames.toTypedArray()
						},
					),
			)
			.executes(
				CommandExecutor { sender, args ->
					// Implement toggle functionality here
					// sender.sendSuccess
					val npcName = args.get("npc_name") as String
					var npcEntity: NPC? = null

					if (sender is Player) {
						val nearbyNPCs = plugin.getNearbyNPCs(sender, 10.0)
						npcEntity = nearbyNPCs.find { it.name == npcName }
					}
					if (npcEntity == null) {
						npcEntity = CitizensAPI.getNPCRegistry().find { it.name == npcName }
					}

					if (npcEntity == null) {
						sender.sendError("NPC '$npcName' not found.")
						return@CommandExecutor
					}

					plugin.npcManager.toggleNPC(npcEntity, sender)
				},
			)
	}
}
