package com.canefe.story.session

import com.canefe.story.Story
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin proxy over the Go-side session handler. The plugin no longer owns
 * the sessions Mongo collection — start/end/add-player events flow to Go
 * via DomainEventEmitter, and the current sessionId is mirrored back via
 * SessionIntentListener.
 */
class SessionManager(
    private val plugin: Story,
) {
    private val current = AtomicReference<String?>(null)

    fun startSession() {
        if (current.get() != null) {
            plugin.logger.info("Session already active locally; ignoring start.")
            return
        }
        val uuids = plugin.server.onlinePlayers.map { it.uniqueId.toString() }
        plugin.domainEvents.emitSessionStart(
            initialPlayerUuids = uuids,
            startTimeGame = plugin.timeService.getCurrentGameTime(),
        )
        plugin.logger.info("Session start requested for ${uuids.size} players")
    }

    fun endSession() {
        if (current.get() == null) {
            plugin.logger.info("No local session to end.")
            return
        }
        plugin.domainEvents.emitSessionEnd(plugin.timeService.getCurrentGameTime())
    }

    fun addPlayer(name: String) {
        val player = plugin.server.getPlayer(name)
        if (player == null) {
            plugin.logger.warning("addPlayer: $name not online; skipping")
            return
        }
        plugin.domainEvents.emitSessionAddPlayer(player.uniqueId.toString())
    }

    fun hasActiveSession(): Boolean = current.get() != null

    fun getCurrentSessionId(): String? = current.get()

    /** Called by SessionIntentListener when Go confirms a session started. */
    fun onStartedFromBridge(sessionId: String) {
        current.set(sessionId)
        plugin.logger.info("Session active: $sessionId")
    }

    /** Called by SessionIntentListener when Go confirms a session ended. */
    fun onEndedFromBridge(sessionId: String) {
        plugin.logger.info("Session ended: $sessionId")
        current.set(null)
    }

    /** Plugin shutdown: emit end if local mirror still shows an active session. */
    fun shutdown() {
        if (current.get() != null) {
            plugin.domainEvents.emitSessionEnd(plugin.timeService.getCurrentGameTime())
        }
        current.set(null)
    }

    /** Stub kept for compatibility with ConfigService.load() callers. */
    fun load() = Unit
}
