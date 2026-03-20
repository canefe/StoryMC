package com.canefe.story.api.event

import com.canefe.story.api.character.Character
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a skill check is triggered during a conversation.
 *
 * The skill check agent analyzes conversation context and determines when
 * an action warrants a skill check (e.g. intimidation, persuasion, deception).
 *
 * Listeners can modify the [dc], [roll], or cancel the event to prevent the check.
 * After the event fires, [passed] reflects whether the check succeeded.
 *
 * @property character    The character attempting the action.
 * @property target       The character being targeted by the action.
 * @property skill        The skill being checked (e.g. "intimidation", "persuasion").
 * @property action       A description of the action being attempted.
 * @property dc           The difficulty class. Can be modified by listeners.
 * @property roll         The roll result (modifier + random). Can be overridden by listeners.
 * @property passed       Whether the check passed. Computed from roll >= dc, but can be overridden.
 * @property conversationId The ID of the conversation where the check was triggered.
 */
class SkillCheckEvent(
    val character: Character,
    val target: Character,
    val skill: String,
    val action: String,
    var dc: Int,
    var roll: Int,
    var passed: Boolean,
    val conversationId: Int,
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
