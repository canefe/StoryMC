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
                            val line =
                                mm.deserialize(
                                    "  <gray>AI Character Voice</gray> <dark_gray>(accumulates your messages, then rewrites them in your character's voice via AI)</dark_gray> $delayedStatus <dark_gray>[<aqua><click:run_command:'/playerconfig toggle delayedPlayerMessageProcessing'>toggle</click></aqua>]</dark_gray>",
                                )
                            player.sendMessage(line)
                        },
                    ),
            ).withSubcommand(
                CommandAPICommand("toggle")
                    .withArguments(
                        StringArgument("setting").replaceSuggestions { _, builder ->
                            builder.suggest("delayedPlayerMessageProcessing")
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
                                else -> player.sendError("Unknown setting: <yellow>$setting</yellow>")
                            }
                        },
                    ),
            ).register()
    }
}
