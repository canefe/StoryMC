package com.canefe.story.command.story

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.command.story.location.LocationCommand
import com.canefe.story.command.story.npc.NPCCommand
import com.canefe.story.command.story.quest.QuestCommand
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor


class StoryCommand(private val plugin: Story) : BaseCommand {

    override fun register() {
        CommandAPICommand("story")
            .withAliases("st", "sto")
            .withPermission("story.command")
            // executes help command too
            .executes(CommandExecutor { sender, _ ->
                val helpMessage = """
                    ${listCommands()}
                """.trimIndent()
                sender.sendRaw(helpMessage)
            })
            .withSubcommand(getLocationCommand())
            .withSubcommand(getQuestCommand())
            .withSubcommand(getHelpCommand())
            .withSubcommand(getReloadCommand())
            .withSubcommand(getNPCCommand())
            .register()
    }

    private fun listCommands(): String {
        return """
            <yellow>=========================</yellow>
            <yellow>Story Plugin Commands</yellow>
            <yellow>=========================</yellow>
            <gold>/story</gold> help <gray><italic>- Show this help message</italic></gray>
            <gold>/story</gold> reload <gray><italic>- Reload the plugin configuration</italic></gray>
            <gold>/story</gold> location <gray><italic>- Manage locations</italic></gray>
            <gold>/story</gold> npc <gray><italic>- Manage NPCs</italic></gray>
            <gold>/conv</gold> list <gray><italic>- List all conversations and control panel</italic></gray>
        """.trimIndent()
    }

    private fun getHelpCommand(): CommandAPICommand {
        return CommandAPICommand("help")
            .withPermission("story.command.help")
            .executes(CommandExecutor { sender, _ ->
                val helpMessage = """
                    ${listCommands()}
                """.trimIndent()
                sender.sendRaw(helpMessage)
            })
    }

    private fun getReloadCommand(): CommandAPICommand {
        return CommandAPICommand("reload")
            .withPermission("story.command.reload")
            .executes(CommandExecutor { sender, _ ->
                plugin.reloadConfig()
                plugin.configService.reload()
                sender.sendSuccess("Plugin reloaded successfully.")
            })
    }

    private fun getLocationCommand(): CommandAPICommand {
        return LocationCommand(plugin).getCommand()
    }
    private fun getQuestCommand(): CommandAPICommand {
        return QuestCommand(plugin).getCommand()
    }
    private fun getNPCCommand(): CommandAPICommand {
        return NPCCommand(plugin).getCommand()
    }


}