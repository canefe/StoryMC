package com.canefe.story.api.event

import com.canefe.story.api.StoryNPC
import com.canefe.story.bridge.StoryEvent
import com.canefe.story.conversation.Conversation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class ConversationEndEvent(
    val player: Player,
    val npcs: List<StoryNPC>,
    val conversation: Conversation,
) : Event(),
    Cancellable,
    StoryEvent {
    override val eventType: String get() = "conversation.ended"

    override fun toWireData(): JsonObject =
        buildJsonObject {
            put("conversationId", conversation.id)
            putJsonArray("npcNames") { conversation.npcNames.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("playerNames") {
                conversation.players.mapNotNull { Bukkit.getPlayer(it)?.name }.forEach { add(JsonPrimitive(it)) }
            }
            put("messageCount", conversation.history.size)
        }

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
