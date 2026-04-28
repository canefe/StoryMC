package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import java.util.UUID
import com.canefe.story.command.story.npc.schedule.ScheduleCommand
import com.canefe.story.command.story.npc.schedule.ScheduleCommandUtils
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class NPCCommand(
    private val plugin: Story,
) {
    private val commandUtils = ScheduleCommandUtils()

    /**
     * Resolve an NPC arg that may be either a bare characterId (UUID, sent by
     * StoryClient where the wheel only knows IDs) or a plain display name.
     * UUIDs are detected by shape — CommandAPI's TextArgument rejects ':' so
     * we can't use a prefix scheme here.
     */
    private fun resolveNpc(arg: String): StoryNPC? {
        val asUuid = runCatching { UUID.fromString(arg) }.getOrNull()
        if (asUuid != null) {
            plugin.npcRegistry.get(asUuid)?.let { return it }
        }
        return plugin.npcRegistry.getByName(arg)
    }

    /** If [arg] looks like a UUID, return the resolved NPC's display name; else return [arg] verbatim. */
    private fun stripIdPrefix(arg: String): String {
        val asUuid = runCatching { UUID.fromString(arg) }.getOrNull() ?: return arg
        return plugin.npcRegistry.get(asUuid)?.name ?: arg
    }

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("npc")
            .withPermission("story.npc")
            .withUsage(
                "/story npc <schedule|toggle|disguise|scale|nearby|mmnpc|follow|followchar>",
            ).withSubcommand(getScheduleCommand())
            .withSubcommand(getToggleCommand())
            .withSubcommand(getDisguiseCommand())
            .withSubcommand(getScaleCommand())
            .withSubcommand(getDebugCommand())
            .withSubcommand(getNearbyCommand())
            .withSubcommand(getMmnpcCommand())
            .withSubcommand(getFollowCommand())
            .withSubcommand(getFollowCharCommand())

    private fun getFollowCommand(): CommandAPICommand =
        CommandAPICommand("follow")
            .withPermission("story.dm")
            .withArguments(TextArgument("npc"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val npcArg = args.get("npc") as String
                    val npc = resolveNpc(npcArg)
                    if (npc == null) {
                        player.sendError("No registered NPC matching '$npcArg'.")
                        return@PlayerCommandExecutor
                    }
                    if (npc.isFollowing) {
                        npc.stopFollowing()
                        plugin.npcFollowTracker.cancel(npc.uniqueId)
                        // Mythic templates may listen for the inverse signal to stop following.
                        npc.signal("CommanderStop", player)
                        player.sendSuccess("${npc.name} stopped following.")
                    } else {
                        npc.follow(player)
                        // Mythic-side templates can hook this with `~onSignal:CommanderFollow`
                        // to trigger their own follow behavior (existing config does this).
                        npc.signal("CommanderFollow", player)
                        player.sendSuccess("${npc.name} is now following you.")
                    }
                },
            )

    private fun getFollowCharCommand(): CommandAPICommand =
        CommandAPICommand("followchar")
            .withPermission("story.dm")
            .withArguments(TextArgument("npc"))
            .withArguments(TextArgument("target"))
            .executes(
                CommandExecutor { sender, args ->
                    val npcArg = args.get("npc") as String
                    val targetArg = args.get("target") as String
                    val npc = resolveNpc(npcArg)
                    if (npc == null) {
                        sender.sendError("No registered NPC matching '$npcArg'.")
                        return@CommandExecutor
                    }
                    // Try NPC (by id: or name) first, then online player
                    val targetNpc = resolveNpc(targetArg)
                    val targetEntity =
                        targetNpc?.entity
                            ?: plugin.server.getPlayerExact(stripIdPrefix(targetArg))
                    if (targetEntity == null) {
                        sender.sendError("No NPC or online player matching '$targetArg'.")
                        return@CommandExecutor
                    }
                    plugin.npcFollowTracker.follow(npc, targetEntity)
                    sender.sendSuccess("${npc.name} is now following ${targetEntity.name}.")
                },
            )

    private fun getMmnpcCommand(): CommandAPICommand =
        CommandAPICommand("mmnpc")
            .withPermission("story.npc")
            .withSubcommand(
                CommandAPICommand("spawn")
                    .withArguments(StringArgument("template"))
                    .withArguments(TextArgument("name"))
                    .executesPlayer(
                        PlayerCommandExecutor { player, args ->
                            if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
                                player.sendError("MythicMobs plugin is not enabled.")
                                return@PlayerCommandExecutor
                            }
                            if (plugin.mythicMobNpcFactoryOrNull == null) {
                                player.sendError("MythicMob NPC factory is not initialized.")
                                return@PlayerCommandExecutor
                            }
                            val template = args.get("template") as String
                            val name = args.get("name") as String
                            val npc =
                                plugin.mythicMobNpcFactory.spawn(
                                    mobTemplate = template,
                                    location = player.location,
                                    displayName = name,
                                )
                            if (npc == null) {
                                player.sendError("Failed to spawn — unknown template '$template'?")
                            } else {
                                player.sendSuccess("Spawned MythicMob NPC '$name' (template=$template).")
                            }
                        },
                    ),
            ).withSubcommand(
                CommandAPICommand("list")
                    .executes(
                        CommandExecutor { sender, _ ->
                            val mmNpcs = plugin.npcRegistry.all()
                                .filterIsInstance<com.canefe.story.npc.mythicmobs.MythicMobStoryNPC>()
                            if (mmNpcs.isEmpty()) {
                                sender.sendError("No MythicMob StoryNPCs registered.")
                                return@CommandExecutor
                            }
                            sender.sendSuccess("Registered MythicMob StoryNPCs (${mmNpcs.size}):")
                            for (npc in mmNpcs) {
                                val loc = npc.location
                                val world = loc?.world?.name ?: "?"
                                val coords = loc?.let {
                                    String.format("%.0f,%.0f,%.0f", it.x, it.y, it.z)
                                } ?: "?"
                                val alive = if (npc.isSpawned) "<green>alive</green>" else "<red>dead</red>"
                                val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: "<unregistered>"
                                sender.sendMessage(
                                    plugin.miniMessage.deserialize(
                                        "  <yellow>${npc.name}</yellow> <gray>[${npc.internalName}]</gray> " +
                                            "<dark_gray>($world $coords)</dark_gray> $alive <gray>${charId}</gray>",
                                    ),
                                )
                            }
                        },
                    ),
            ).withSubcommand(
                CommandAPICommand("despawn")
                    .withArguments(TextArgument("name"))
                    .executes(
                        CommandExecutor { sender, args ->
                            val name = args.get("name") as String
                            val npc = plugin.npcRegistry.getByName(name)
                            if (npc == null) {
                                sender.sendError("No registered NPC named '$name'.")
                                return@CommandExecutor
                            }
                            npc.despawn()
                            plugin.npcRegistry.unregister(npc.uniqueId)
                            sender.sendSuccess("Despawned and unregistered '$name'.")
                        },
                    ),
            )

    private fun getNearbyCommand(): CommandAPICommand =
        CommandAPICommand("nearby")
            .withPermission("story.npc")
            .withOptionalArguments(DoubleArgument("radius"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val radius = (args.getOptional("radius").orElse(null) as? Double) ?: 20.0
                    val nearbyNPCs = NPCUtils.getNearbyNPCs(player, radius)

                    if (nearbyNPCs.isEmpty()) {
                        player.sendError("No NPCs within $radius blocks.")
                        return@PlayerCommandExecutor
                    }

                    player.sendSuccess("Nearby NPCs (${nearbyNPCs.size}) within $radius blocks:")
                    for (npc in nearbyNPCs) {
                        val record = plugin.characterRegistry.getByStoryNPC(npc)
                        val characterId = record?.id ?: "<unregistered>"
                        val distance = player.location.distance(npc.location ?: player.location)
                        val distStr = String.format("%.1f", distance)
                        player.sendMessage(
                            plugin.miniMessage.deserialize(
                                "  <yellow>${npc.name}</yellow> <gray>[$characterId]</gray> <dark_gray>(${distStr}m)</dark_gray>",
                            ),
                        )
                    }
                },
            )

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
                    var npcEntity: StoryNPC? = null

                    if (sender is Player) {
                        val nearbyNPCs = NPCUtils.getNearbyNPCs(sender, 10.0)
                        npcEntity = nearbyNPCs.find { it.name == npcName }
                    }
                    if (npcEntity == null) {
                        npcEntity =
                            CitizensAPI.getNPCRegistry().find { it.name == npcName }?.let { CitizensStoryNPC(it) }
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
                        val npc: StoryNPC = CitizensStoryNPC(CitizensAPI.getNPCRegistry().getNPC(target))

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
                    var npcEntity: StoryNPC? = null

                    if (sender is Player) {
                        val nearbyNPCs = NPCUtils.getNearbyNPCs(sender, 10.0)
                        npcEntity = nearbyNPCs.find { it.name == npcName }
                    }
                    if (npcEntity == null) {
                        npcEntity =
                            CitizensAPI.getNPCRegistry().find { it.name == npcName }?.let { CitizensStoryNPC(it) }
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
