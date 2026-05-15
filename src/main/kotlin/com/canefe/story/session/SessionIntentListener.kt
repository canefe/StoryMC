package com.canefe.story.session

import com.canefe.story.Story
import com.canefe.story.bridge.SessionEndedIntent
import com.canefe.story.bridge.SessionNarrationIntent
import com.canefe.story.bridge.SessionStartedIntent

/**
 * Consumes the three intent.session.* events broadcast by story-go and
 * applies them to the plugin's local state (current sessionId mirror) and
 * to player chat (narration broadcast).
 *
 * Wire-up: call register(eventBus) once during plugin startup.
 */
class SessionIntentListener(
    private val plugin: Story,
) {
    fun onStarted(intent: SessionStartedIntent) {
        plugin.sessionManager.onStartedFromBridge(intent.sessionId)
    }

    fun onEnded(intent: SessionEndedIntent) {
        plugin.sessionManager.onEndedFromBridge(intent.sessionId)
    }

    fun onNarration(intent: SessionNarrationIntent) {
        if (!plugin.config.broadcastSessionEntries) return
        var message = intent.text
        message = message.replace(Regex("\"([^\"]*)\""), "<yellow>\"$1\"</yellow>")
        val formatted =
            plugin.npcMessageService.formatMessage(
                message = message,
                name = "",
                formatColor = "<color:#e67e22>",
                formatColorSuffix = "</color:#e67e22>",
            )
        intent.playerNames.forEach { name ->
            val ply = plugin.server.getPlayer(name) ?: return@forEach
            for (part in formatted) {
                ply.sendMessage(part)
            }
        }
    }
}
