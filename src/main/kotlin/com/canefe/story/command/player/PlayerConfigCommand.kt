package com.canefe.story.command.player

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.minimessage.MiniMessage

class PlayerConfigCommand(
    private val plugin: Story,
) {
    fun register() {
        CommandAPICommand("playerconfig")
            .withPermission("story.playerconfig")
            .withSubcommand(
                CommandAPICommand("list")
                    .executesPlayer(
                        PlayerCommandExecutor { player, _ ->
                            val config = plugin.playerManager.getPlayerConfig(player.uniqueId)
                            val mm = MiniMessage.miniMessage()

                            player.sendInfo("Your Story settings:")

                            val delayedStatus = if (config.delayedPlayerMessageProcessing) "<green>enabled</green>" else "<red>disabled</red>"
                            player.sendMessage(
                                mm.deserialize(
                                    "  <gray>AI Character Voice</gray> <dark_gray>(accumulates your messages, then rewrites them in your character's voice via AI)</dark_gray> $delayedStatus <dark_gray>[<aqua><click:run_command:'/playerconfig toggle delayedPlayerMessageProcessing'>toggle</click></aqua>]</dark_gray>",
                                ),
                            )

                            if (player.hasPermission("story.dm")) {
                                val revealStatus = if (config.dmRevealRealNames) "<green>enabled</green>" else "<red>disabled</red>"
                                player.sendMessage(
                                    mm.deserialize(
                                        "  <gray>DM Reveal Real Names</gray> <dark_gray>(see real names regardless of recognition; off makes you see what your character sees)</dark_gray> $revealStatus <dark_gray>[<aqua><click:run_command:'/playerconfig toggle dmRevealRealNames'>toggle</click></aqua>]</dark_gray>",
                                    ),
                                )
                            }
                        },
                    ),
            ).withSubcommand(
                CommandAPICommand("toggle")
                    .withArguments(
                        StringArgument("setting").replaceSuggestions { info, builder ->
                            builder.suggest("delayedPlayerMessageProcessing")
                            if (info.sender().hasPermission("story.dm")) {
                                builder.suggest("dmRevealRealNames")
                            }
                            builder.buildFuture()
                        },
                    ).executesPlayer(
                        PlayerCommandExecutor { player, args ->
                            when (val setting = args["setting"] as String) {
                                "delayedPlayerMessageProcessing" -> {
                                    val enabled = plugin.playerManager.toggleDelayedMessageProcessing(player)
                                    if (enabled) {
                                        player.sendSuccess(
                                            "AI Character Voice <green>enabled</green>. Your messages will be accumulated and rewritten in your character's voice.",
                                        )
                                    } else {
                                        player.sendError(
                                            "AI Character Voice <red>disabled</red>. Your messages will be sent as-is.",
                                        )
                                    }
                                }
                                "dmRevealRealNames" -> {
                                    if (!player.hasPermission("story.dm")) {
                                        player.sendError("Unknown setting: <yellow>$setting</yellow>")
                                        return@PlayerCommandExecutor
                                    }
                                    val enabled = plugin.playerManager.toggleDmRevealRealNames(player)
                                    if (enabled) {
                                        player.sendSuccess(
                                            "DM Reveal Real Names <green>enabled</green>. You see real names regardless of recognition.",
                                        )
                                    } else {
                                        player.sendError(
                                            "DM Reveal Real Names <red>disabled</red>. You now see what your character would see.",
                                        )
                                    }
                                }
                                else -> player.sendError("Unknown setting: <yellow>$setting</yellow>")
                            }
                        },
                    ),
            ).register()
    }
}
