package com.canefe.story.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * WebSocket client transport for the Story event bus.
 *
 * Connects to an external WebSocket server (Go orchestrator, etc.)
 * for bidirectional event streaming.
 *
 * Protocol: each message is a JSON-encoded BridgeMessage.
 */
class WebSocketTransport(
    private val plugin: JavaPlugin,
    private val serverUri: String,
) : EventTransport {
    override val name: String = "websocket"

    private var ws: WebSocket? = null
    private var inboundHandler: ((StoryEvent) -> Unit)? = null
    private val running = AtomicBoolean(false)
    private val logger: Logger = plugin.logger
    private var reconnectTaskId: Int = -1

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    val isConnected: Boolean
        get() = ws != null && running.get()

    fun connect(): Boolean =
        try {
            running.set(true)
            doConnect()
            true
        } catch (e: Exception) {
            logger.warning("Failed to connect WebSocket to $serverUri: ${e.message}")
            scheduleReconnect()
            false
        }

    private fun doConnect() {
        // Append server ID as query param so Go deduplicates connections
        val separator = if (serverUri.contains("?")) "&" else "?"
        val serverName =
            org.bukkit.Bukkit
                .getServer()
                .name
                .replace(" ", "-")
        val uriWithId = "$serverUri${separator}id=$serverName"

        val client = HttpClient.newHttpClient()
        client
            .newWebSocketBuilder()
            .buildAsync(URI.create(uriWithId), StoryWebSocketListener())
            .thenAccept { websocket ->
                ws = websocket
                logger.info("WebSocket transport connected to $serverUri")
                cancelReconnect()
            }.exceptionally { e ->
                logger.warning("WebSocket connection failed: ${e.message}")
                scheduleReconnect()
                null
            }
    }

    override fun publish(event: StoryEvent) {
        val socket = ws ?: return

        val data =
            when (event) {
                is SerializableStoryEvent -> json.encodeToJsonElement(serializeEvent(event)).jsonObject
                else -> event.toWireData() ?: return
            }

        val message = BridgeMessage(type = event.eventType, data = data, source = "story")
        val serialized = json.encodeToString(message)

        try {
            socket.sendText(serialized, true)
            plugin.logger.info("WS published: ${event.eventType}")
        } catch (e: Exception) {
            plugin.logger.warning("WS send failed: ${e.message}")
        }
    }

    override fun subscribe(handler: (StoryEvent) -> Unit) {
        inboundHandler = handler
    }

    override fun shutdown() {
        running.set(false)
        cancelReconnect()
        ws?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        ws = null
        logger.info("WebSocket transport disconnected")
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        cancelReconnect()
        // Retry every 5 seconds
        reconnectTaskId =
            Bukkit
                .getScheduler()
                .runTaskLaterAsynchronously(
                    plugin,
                    Runnable {
                        if (running.get() && ws == null) {
                            logger.info("Attempting WebSocket reconnect to $serverUri...")
                            doConnect()
                        }
                    },
                    100L, // 5 seconds
                ).taskId
    }

    private fun cancelReconnect() {
        if (reconnectTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reconnectTaskId)
            reconnectTaskId = -1
        }
    }

    private fun handleInboundMessage(payload: String) {
        try {
            val bridgeMessage = json.decodeFromString<BridgeMessage>(payload)
            val event = deserializeEvent(bridgeMessage) ?: return

            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    inboundHandler?.invoke(event)
                },
            )
        } catch (e: Exception) {
            logger.warning("Failed to parse WebSocket message: ${e.message}")
        }
    }

    private fun serializeEvent(event: SerializableStoryEvent): kotlinx.serialization.json.JsonElement =
        when (event) {
            is PlayerMessageEvent -> json.encodeToJsonElement(event)
            is NPCDamagedEvent -> json.encodeToJsonElement(event)
            is NPCInteractionEvent -> json.encodeToJsonElement(event)
            is NPCSpeakIntent -> json.encodeToJsonElement(event)
            is NPCMoveIntent -> json.encodeToJsonElement(event)
            is NPCEmoteIntent -> json.encodeToJsonElement(event)
            else -> json.encodeToJsonElement(mapOf("raw" to event.eventType))
        }

    private fun deserializeEvent(message: BridgeMessage): StoryEvent? {
        val data = message.data.toString()
        return try {
            when (message.type) {
                "npc.speak" -> json.decodeFromString<NPCSpeakIntent>(data)
                "npc.move" -> json.decodeFromString<NPCMoveIntent>(data)
                "npc.emote" -> json.decodeFromString<NPCEmoteIntent>(data)
                "player.message" -> json.decodeFromString<PlayerMessageEvent>(data)
                "npc.damaged" -> json.decodeFromString<NPCDamagedEvent>(data)
                "npc.interaction" -> json.decodeFromString<NPCInteractionEvent>(data)
                "character.stats_update" -> json.decodeFromString<CharacterStatsUpdate>(data)
                // Pass through unknown event types as generic StoryEvents
                // so listeners registered by eventType string (e.g. intelligence.response) still receive them
                else ->
                    object : StoryEvent {
                        override val eventType: String = message.type

                        override fun toWireData(): kotlinx.serialization.json.JsonObject = message.data
                    }
            }
        } catch (e: Exception) {
            logger.warning("Failed to deserialize WebSocket event ${message.type}: ${e.message}")
            null
        }
    }

    // ── Java 11+ WebSocket Listener ─────────────────────────────────

    private inner class StoryWebSocketListener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            logger.info("WebSocket connection established")
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            buffer.append(data)
            if (last) {
                handleInboundMessage(buffer.toString())
                buffer.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*> {
            logger.info("WebSocket closed: $reason (code: $statusCode)")
            ws = null
            scheduleReconnect()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            logger.warning("WebSocket error: ${error.message}")
            ws = null
            scheduleReconnect()
        }
    }
}
