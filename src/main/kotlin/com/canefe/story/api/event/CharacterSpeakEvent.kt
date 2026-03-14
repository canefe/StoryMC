package com.canefe.story.api.event

import com.canefe.story.api.character.Character
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a [Character] (player or AI NPC) speaks a message within the Story system.
 *
 * @property speaker   The character who is speaking.
 * @property nearby    All other characters within hearing range, excluding the speaker.
 * @property message   The spoken message. Can be modified by listeners before propagation.
 */
class CharacterSpeakEvent(
    val speaker: Character,
    val nearby: Set<Character>,
    var message: String,
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
