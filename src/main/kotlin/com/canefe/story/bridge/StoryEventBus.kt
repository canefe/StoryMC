package com.canefe.story.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central event bus that routes StoryEvents to registered transports and local listeners.
 *
 * Usage:
 *   eventBus.emit(PlayerMessageEvent(...))   // fires to all transports + local listeners
 *   eventBus.on<PlayerMessageEvent> { e -> } // register typed local listener
 *   eventBus.registerTransport(redisTransport) // add a transport
 */
class StoryEventBus {
    private val transports = CopyOnWriteArrayList<EventTransport>()
    private val listeners = ConcurrentHashMap<String, MutableList<(StoryEvent) -> Unit>>()

    @PublishedApi
    internal val classListeners = ConcurrentHashMap<String, MutableList<(StoryEvent) -> Unit>>()

    /**
     * Emit an event to all transports and local listeners.
     */
    fun emit(event: StoryEvent) {
        // Notify transports
        for (transport in transports) {
            try {
                transport.publish(event)
            } catch (_: Exception) {
            }
        }

        // Notify string-type listeners
        listeners[event.eventType]?.forEach { handler ->
            try {
                handler(event)
            } catch (_: Exception) {
            }
        }

        // Notify class-type listeners
        classListeners[event::class.java.name]?.forEach { handler ->
            try {
                handler(event)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Register a typed local listener. Events are matched by class type.
     */
    inline fun <reified T : StoryEvent> on(noinline handler: (T) -> Unit) {
        val className = T::class.java.name
        classListeners.getOrPut(className) { CopyOnWriteArrayList() }.add { event ->
            if (event is T) handler(event)
        }
    }

    /**
     * Register a listener by event type string.
     */
    fun onType(
        eventType: String,
        handler: (StoryEvent) -> Unit,
    ) {
        listeners.getOrPut(eventType) { CopyOnWriteArrayList() }.add(handler)
    }

    /**
     * Register a transport. Its subscribe callback will receive inbound events
     * and re-emit them through the bus (to local listeners and other transports).
     */
    fun registerTransport(transport: EventTransport) {
        transports.add(transport)
        // Inbound events from this transport get dispatched to local listeners only (no re-publish to avoid loops)
        transport.subscribe { event ->
            listeners[event.eventType]?.forEach { handler ->
                try {
                    handler(event)
                } catch (_: Exception) {
                }
            }
            classListeners[event::class.java.name]?.forEach { handler ->
                try {
                    handler(event)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Shutdown all transports.
     */
    fun shutdown() {
        transports.forEach { it.shutdown() }
        transports.clear()
        listeners.clear()
        classListeners.clear()
    }
}
