package com.canefe.story.command.story

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.command.story.location.LocationCommand
import com.canefe.story.command.story.npc.NPCCommand
import com.canefe.story.command.story.quest.QuestCommand
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.Msg.sendRaw
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.PlayerArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.CommandExecutor

class StoryCommand(
	private val plugin: Story,
) : BaseCommand {
	override fun register() {
		CommandAPICommand("story")
			.withAliases("st", "sto")
			.withPermission("story.command")
			// executes help command too
			.executes(
				CommandExecutor { sender, _ ->
					val helpMessage =
						"""
						${listCommands()}
						""".trimIndent()
					sender.sendRaw(helpMessage)
				},
			).withSubcommand(getLocationCommand())
			.withSubcommand(getQuestCommand())
			.withSubcommand(getHelpCommand())
			.withSubcommand(getReloadCommand())
			.withSubcommand(getNPCCommand())
			.withSubcommand(getMessageCommand())
			.register()
	}

	private fun getMessageCommand(): CommandAPICommand {
		return CommandAPICommand("message")
			.withAliases("msg", "m")
			.withPermission("story.command.message")
			.withArguments(TextArgument("context"))
			.withOptionalArguments(PlayerArgument("target"))
			.executes(
				CommandExecutor { sender, args ->
					val context = args[0] as String
					val targetPlayer = args["target"] as? org.bukkit.entity.Player

					// Create prompt for AI
					val prompt = mutableListOf<ConversationMessage>()

					prompt.add(
						ConversationMessage(
							"system",
							"""
                        You are a system message generator for a Minecraft RPG plugin.
                        Create a professional, visually appealing message based on the given context.

                        Guidelines:
                        - Use MiniMessage formatting tags (<#hexcolor>, <i>, <b> etc.)
                        - Create a short but impactful message (1 line)
						- Use prefix: <dark_gray>[<gold>Story</gold>]</dark_gray>
                        - Maintain a professional system announcement tone
                        - No emojis or excessive punctuation, system messages should be clear and concise.
						- Interpret the context fully, do not cut parts of the context.

                        Output only the formatted message, nothing else.
                        """,
						),
					)

					prompt.add(ConversationMessage("user", context))

					// Get AI response
					plugin
						.getAIResponse(prompt)
						.thenAccept { response ->
							if (response.isNullOrEmpty()) {
								sender.sendSuccess("Failed to generate message. Please try again.")
								return@thenAccept
							}

							// Send the message to target player or all online players
							if (targetPlayer != null) {
								targetPlayer.sendRaw(response)
								sender.sendSuccess("Message sent to ${targetPlayer.name}.")
							} else {
								plugin.server.onlinePlayers.forEach { player ->
									player.sendRaw(response)
								}
								sender.sendSuccess("Message broadcast to all players.")
							}
						}.exceptionally { e ->
							sender.sendSuccess("Error generating message: ${e.message}")
							null
						}
				},
			)
	}

	private fun listCommands(): String =
		"""
		<yellow>=========================</yellow>
		<yellow>Story Plugin Commands</yellow>
		<yellow>=========================</yellow>
		<gold>/story</gold> help <gray><italic>- Show this help message</italic></gray>
		<gold>/story</gold> reload <gray><italic>- Reload the plugin configuration</italic></gray>
		<gold>/story</gold> location <gray><italic>- Manage locations</italic></gray>
		<gold>/story</gold> npc <gray><italic>- Manage NPCs</italic></gray>
		<gold>/conv</gold> list <gray><italic>- List all conversations and control panel</italic></gray>
		""".trimIndent()

	private fun getHelpCommand(): CommandAPICommand =
		CommandAPICommand("help")
			.withPermission("story.command.help")
			.executes(
				CommandExecutor { sender, _ ->
					val helpMessage =
						"""
						${listCommands()}
						""".trimIndent()
					sender.sendRaw(helpMessage)
				},
			)

	private fun getReloadCommand(): CommandAPICommand =
		CommandAPICommand("reload")
			.withPermission("story.command.reload")
			.executes(
				CommandExecutor { sender, _ ->
					plugin.reloadConfig()
					plugin.configService.reload()
					sender.sendSuccess("Plugin reloaded successfully.")
				},
			)

	private fun getLocationCommand(): CommandAPICommand = LocationCommand(plugin).getCommand()

	private fun getQuestCommand(): CommandAPICommand = QuestCommand(plugin).getCommand()

	private fun getNPCCommand(): CommandAPICommand = NPCCommand(plugin).getCommand()
}
