package com.canefe.story.bridge

import com.canefe.story.api.event.ConversationEndEvent
import com.canefe.story.api.event.ConversationStartEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

/**
 * Transport that bridges Bukkit events into the StoryEvent bus.
 *
 * Since Bukkit events now implement StoryEvent directly, this transport
 * just listens for them and forwards to the bus handler. No conversion needed.
 */
class BukkitTransport(
    private val plugin: JavaPlugin,
) : EventTransport,
    Listener {
    override val name: String = "bukkit"

    private var inboundHandler: ((StoryEvent) -> Unit)? = null

    override fun publish(event: StoryEvent) {
        // Bukkit events are fired at their origin via Bukkit.getPluginManager().callEvent().
        // This transport doesn't re-fire them to avoid double-firing.
    }

    override fun subscribe(handler: (StoryEvent) -> Unit) {
        inboundHandler = handler
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun shutdown() {
        // Bukkit auto-unregisters listeners on plugin disable
    }

    // ── Forward Bukkit events that implement StoryEvent to the bus ───

    @EventHandler
    fun onConversationStart(event: ConversationStartEvent) {
        inboundHandler?.invoke(event)
    }

    @EventHandler
    fun onConversationEnd(event: ConversationEndEvent) {
        inboundHandler?.invoke(event)
    }
}
