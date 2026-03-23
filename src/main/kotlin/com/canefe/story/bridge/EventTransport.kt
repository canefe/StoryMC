package com.canefe.story.bridge

/**
 * A transport layer that can publish and subscribe to StoryEvents.
 * Multiple transports can be registered on the event bus simultaneously.
 */
interface EventTransport {
    val name: String

    /**
     * Publish an event through this transport.
     */
    fun publish(event: StoryEvent)

    /**
     * Start listening for inbound events and forward them to the handler.
     */
    fun subscribe(handler: (StoryEvent) -> Unit)

    /**
     * Shutdown the transport cleanly.
     */
    fun shutdown()
}
