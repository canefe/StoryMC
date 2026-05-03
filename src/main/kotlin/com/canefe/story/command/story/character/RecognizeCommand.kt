package com.canefe.story.command.story.character

import com.canefe.story.Story
import com.canefe.story.intelligence.BridgeIntelligence
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.Bukkit

/**
 * GM commands for the recognition system. All routed through
 * [BridgeIntelligence] → story-go → story-recognition.
 *
 *  /story recognize <perceiver> <target> [realName]
 *  /story forget    <perceiver> <target>
 *  /story descriptor <character> <descriptor...>
 *
 * Perceiver/target/character arguments are character display names (resolved
 * against [CharacterRegistry]); realName defaults to the target's registered
 * display name.
 */
class RecognizeCommand(
    private val plugin: Story,
) {
    fun getRecognizeCommand(): CommandAPICommand =
        CommandAPICommand("recognize")
            .withPermission("story.dm")
            .withUsage("/story recognize <perceiver> <target> [realName]")
            .withArguments(StringArgument("perceiver"))
            .withArguments(StringArgument("target"))
            .withOptionalArguments(GreedyStringArgument("realName"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val bridge = plugin.intelligence as? BridgeIntelligence
                    if (bridge == null || !bridge.isRecognitionSupported()) {
                        player.sendError("Recognition service not available (bridge required).")
                        return@PlayerCommandExecutor
                    }

                    val perceiverArg = args.get("perceiver") as String
                    val targetArg = args.get("target") as String
                    val realNameArg = args["realName"] as? String

                    val perceiver = plugin.characterRegistry.getById(perceiverArg)
                        ?: plugin.characterRegistry.getByName(perceiverArg)
                    val target = plugin.characterRegistry.getById(targetArg)
                        ?: plugin.characterRegistry.getByName(targetArg)
                    if (perceiver == null) {
                        player.sendError("Unknown character '$perceiverArg'.")
                        return@PlayerCommandExecutor
                    }
                    if (target == null) {
                        player.sendError("Unknown character '$targetArg'.")
                        return@PlayerCommandExecutor
                    }

                    val realName = realNameArg ?: target.name
                    bridge
                        .recognize(perceiver.id, target.id, realName, source = "gm")
                        .thenAccept {
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    player.sendSuccess(
                                        "${perceiver.name} now recognizes ${target.name} as '$realName'.",
                                    )
                                    plugin.recognitionBroadcaster.refreshAll()
                                },
                            )
                        }.exceptionally { e ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable { player.sendError("Recognize failed: ${e.message}") },
                            )
                            null
                        }
                },
            )

    fun getForgetCommand(): CommandAPICommand =
        CommandAPICommand("forget")
            .withPermission("story.dm")
            .withUsage("/story forget <perceiver> <target>")
            .withArguments(StringArgument("perceiver"))
            .withArguments(StringArgument("target"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val bridge = plugin.intelligence as? BridgeIntelligence
                    if (bridge == null || !bridge.isRecognitionSupported()) {
                        player.sendError("Recognition service not available (bridge required).")
                        return@PlayerCommandExecutor
                    }

                    val perceiverArg = args.get("perceiver") as String
                    val targetArg = args.get("target") as String
                    val perceiver = plugin.characterRegistry.getById(perceiverArg)
                        ?: plugin.characterRegistry.getByName(perceiverArg)
                    val target = plugin.characterRegistry.getById(targetArg)
                        ?: plugin.characterRegistry.getByName(targetArg)
                    if (perceiver == null || target == null) {
                        player.sendError("Unknown character.")
                        return@PlayerCommandExecutor
                    }

                    bridge
                        .forgetRecognition(perceiver.id, target.id)
                        .thenAccept {
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    player.sendSuccess("${perceiver.name} no longer recognizes ${target.name}.")
                                    plugin.recognitionBroadcaster.refreshAll()
                                },
                            )
                        }.exceptionally { e ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable { player.sendError("Forget failed: ${e.message}") },
                            )
                            null
                        }
                },
            )

    fun getDescriptorCommand(): CommandAPICommand =
        CommandAPICommand("descriptor")
            .withPermission("story.dm")
            .withUsage("/story descriptor <character> <descriptor...>")
            .withArguments(StringArgument("character"))
            .withArguments(GreedyStringArgument("descriptor"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val bridge = plugin.intelligence as? BridgeIntelligence
                    if (bridge == null || !bridge.isRecognitionSupported()) {
                        player.sendError("Recognition service not available (bridge required).")
                        return@PlayerCommandExecutor
                    }

                    val character = plugin.characterRegistry.getByName(args.get("character") as String)
                    if (character == null) {
                        player.sendError("Unknown character.")
                        return@PlayerCommandExecutor
                    }
                    val descriptor = (args.get("descriptor") as String).trim()
                    if (descriptor.isEmpty()) {
                        player.sendError("Descriptor cannot be empty.")
                        return@PlayerCommandExecutor
                    }

                    bridge
                        .setDescriptor(character.id, descriptor)
                        .thenAccept {
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    player.sendSuccess(
                                        "Descriptor for ${character.name} set to '$descriptor'.",
                                    )
                                },
                            )
                        }.exceptionally { e ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable { player.sendError("Set descriptor failed: ${e.message}") },
                            )
                            null
                        }
                },
            )
}
