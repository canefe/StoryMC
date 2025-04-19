package com.canefe.story.command.conversation

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import dev.jorel.commandapi.CommandAPICommand

class ConvCommand(private val plugin: Story) : BaseCommand {
	private val commandUtils = ConvCommandUtils()

	override fun register() {
		CommandAPICommand("conv")
			.withPermission("story.conv")
			.withSubcommand(getListSubcommand())
			.withSubcommand(getNPCSubcommand())
			.withSubcommand(getRemoveSubcommand())
			.withSubcommand(getFeedSubcommand())
			.withSubcommand(getEndSubcommand())
			.withSubcommand(getForceEndSubcommand())
			.withSubcommand(getEndAllSubcommand())
			.withSubcommand(getMuteSubcommand())
			.withSubcommand(getAddSubcommand())
			.withSubcommand(getToggleSubcommand())
			.register()
	}

	private fun getListSubcommand(): CommandAPICommand {
		return ConvListCommand(commandUtils).getCommand()
	}

	private fun getNPCSubcommand(): CommandAPICommand {
		return ConvNPCCommand(commandUtils).getCommand()
	}

	private fun getRemoveSubcommand(): CommandAPICommand {
		return ConvRemoveCommand(commandUtils).getCommand()
	}

	private fun getFeedSubcommand(): CommandAPICommand {
		return ConvFeedCommand(commandUtils).getCommand()
	}

	private fun getEndSubcommand(): CommandAPICommand {
		return ConvEndCommand(commandUtils).getCommand()
	}

	private fun getForceEndSubcommand(): CommandAPICommand {
		return ConvForceEndCommand(commandUtils).getCommand()
	}

	private fun getEndAllSubcommand(): CommandAPICommand {
		return ConvEndAllCommand(commandUtils).getCommand()
	}

	private fun getMuteSubcommand(): CommandAPICommand {
		return ConvMuteCommand(commandUtils).getCommand()
	}

	private fun getAddSubcommand(): CommandAPICommand {
		return ConvAddCommand(commandUtils).getCommand()
	}

	private fun getToggleSubcommand(): CommandAPICommand {
		return ConvToggleCommand(commandUtils).getCommand()
	}
}
