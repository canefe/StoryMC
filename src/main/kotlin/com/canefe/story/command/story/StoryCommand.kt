package com.canefe.story.command.story

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.command.story.location.LocationCommand
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor


class StoryCommand(private val plugin: Story) : BaseCommand {

    override fun register() {
        CommandAPICommand("story")
            .withPermission("story.command")
            .withSubcommand(getLocationCommand())
            .withSubcommand(getHelpCommand())
            .withSubcommand(getReloadCommand())
            .register()
    }

    private fun getLocationCommand(): CommandAPICommand {
        return LocationCommand(plugin).getCommand()
    }
    private fun getHelpCommand(): CommandAPICommand {
        return StoryHelpCommand(plugin).getCommand()
    }

    private fun getReloadCommand(): CommandAPICommand {
        return CommandAPICommand("reload")
            .withPermission("story.command.reload")
            .executes(CommandExecutor { sender, _ ->
                plugin.reloadConfig()
                sender.sendSuccess("Plugin reloaded successfully.")
            })
    }

}