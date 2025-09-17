package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.command.story.npc.schedule.ScheduleCommand
import com.canefe.story.command.story.npc.schedule.ScheduleCommandUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player

class NPCCommand(
    private val plugin: Story,
) {
    private val commandUtils = ScheduleCommandUtils()

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("npc")
            .withPermission("story.npc")
            .withUsage(
                "/story npc <schedule|toggle|disguise|scale>",
            ).withSubcommand(getScheduleCommand())
            .withSubcommand(getToggleCommand())
            .withSubcommand(getDisguiseCommand())
            .withSubcommand(getScaleCommand())
            .withSubcommand(getDebugCommand())

    private fun getScheduleCommand(): CommandAPICommand = ScheduleCommand(commandUtils).getCommand()

    private fun getDebugCommand(): CommandAPICommand =
        CommandAPICommand("debug")
            .withPermission("story.npc.debug")
            .executes(
                CommandExecutor { sender, _ ->
                    commandUtils.story.npcManager.printActiveNavigationTasks(sender)
                },
            )

    // disguise command
    private fun getDisguiseCommand(): CommandAPICommand {
        return CommandAPICommand("disguise")
            .withPermission("story.npc.disguise")
            .withArguments(
                GreedyStringArgument("npc_name")
                    .replaceSuggestions(
                        ArgumentSuggestions.strings { _ ->
                            // Get all NPCs from Citizens and convert to array
                            val npcNames = ArrayList<String>()
                            CitizensAPI.getNPCRegistry().forEach { citizenNPC ->
                                npcNames.add(citizenNPC.name)
                            }
                            npcNames.toTypedArray()
                        },
                    ),
            ).executesPlayer(
                PlayerCommandExecutor { sender, args ->
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
                        return@PlayerCommandExecutor
                    }

                    if (plugin.disguiseManager.isDisguisedAsNPC(sender)) {
                        DisguiseUtil(plugin).undisguisePlayer(sender)
                        return@PlayerCommandExecutor
                    }

                    DisguiseUtil(plugin).disguisePlayer(sender, npcEntity)
                },
            )
    }

    private fun getScaleCommand(): CommandAPICommand {
        return CommandAPICommand("scale")
            .withPermission("story.npc.scale")
            .withArguments(DoubleArgument("scale"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val scale = args.get("scale") as Double
                    val player = player as Player
                    val target = player.getTargetEntity(15) // Get entity player is looking at within 15 blocks
                    if (target != null && CitizensAPI.getNPCRegistry().isNPC(target)) {
                        val npc = CitizensAPI.getNPCRegistry().getNPC(target)

                        if (plugin.npcManager.scaleNPC(npc, scale)) {
                            player.sendSuccess("Scaled NPC '${npc.name}' to $scale.")
                        } else {
                            player.sendError("Failed to scale NPC '${npc.name}'.")
                        }
                        return@PlayerCommandExecutor
                    }
                },
            )
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
            ).executes(
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
