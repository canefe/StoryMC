package com.canefe.story.command.conversation

import ConversationManager
import com.canefe.story.Story
import com.canefe.story.command.base.CommandComponentUtils
import com.canefe.story.conversation.Conversation
import com.canefe.story.player.NPCManager
import com.canefe.story.util.Msg.sendError
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender

class ConvCommandUtils {
	val story: Story = Story.instance
	val mm: MiniMessage = story.miniMessage
	val conversationManager: ConversationManager get() = Story.instance.conversationManager
	val npcManager: NPCManager get() = Story.instance.npcManager

	// Return the GroupConversation if it exists, null otherwise
	fun getConversation(
		conversationId: Int,
		sender: CommandSender,
	): Conversation? {
		val conversation = story.conversationManager.getConversationById(conversationId)

		if (conversation == null) {
			sender.sendError("Invalid conversation ID.")
			return null
		}
		return conversation
	}

	fun createButton(
		label: String,
		color: String,
		clickAction: String,
		command: String,
		hoverText: String,
	): Component {
		return CommandComponentUtils.createButton(mm, label, color, clickAction, command, hoverText)
	}

	fun combineComponentsWithSeparator(
		components: List<Component>,
		separatorText: String,
	): Component {
		return CommandComponentUtils.combineComponentsWithSeparator(mm, components, separatorText)
	}

	fun escapeForCommand(text: String): String {
		return CommandComponentUtils.escapeForCommand(text)
	}
}
