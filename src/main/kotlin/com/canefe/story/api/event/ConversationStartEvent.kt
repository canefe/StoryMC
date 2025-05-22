package com.canefe.story.api.event

import com.canefe.story.conversation.Conversation
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class ConversationStartEvent(
	val player: Player,
	val npcs: List<NPC>,
	val conversation: Conversation,
) : Event(),
	Cancellable {
	private var cancelled = false

	override fun isCancelled(): Boolean = cancelled

	override fun setCancelled(cancel: Boolean) {
		this.cancelled = cancel
	}

	companion object {
		private val HANDLERS = HandlerList()

		@JvmStatic
		fun getHandlerList(): HandlerList = HANDLERS
	}

	override fun getHandlers(): HandlerList = HANDLERS
}
