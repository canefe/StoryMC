package com.canefe.story.command.story

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendRaw
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor


class StoryHelpCommand(private val plugin: Story) {

    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("help")
            .withPermission("story.command.help")
            .executes(CommandExecutor { sender, _ ->
                val helpMessage = """
                    <green>Story Plugin Help</green>
                    ${listCommands()}
                """.trimIndent()
                sender.sendRaw(helpMessage)
            })
    }

    private fun listCommands(): String {
        return """
            <green>Available commands:</green>
            <gray>/story location create <italic><location_name></italic></gray>
            <gray>/story location delete <italic><location_name></italic></gray>
            <gray>/story location list</gray>
            <gray>/story location info <italic><location_name></italic></gray>
        """.trimIndent()
    }

}