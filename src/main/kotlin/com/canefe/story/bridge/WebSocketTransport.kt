package com.canefe.story.bridge

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
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
        val socket = ws
        if (socket == null) {
            if (event is DecisionResponseEvent) {
                plugin.logger.warning("[WSTransport] DecisionResponseEvent dropped — WebSocket not connected")
            }
            return
        }

        val data =
            when (event) {
                is SerializableStoryEvent -> json.encodeToJsonElement(serializeEvent(event)).jsonObject
                else -> event.toWireData() ?: return
            }

        val message = BridgeMessage(type = event.eventType, data = data, source = "story")
        val serialized = json.encodeToString(message)

        if (event is DecisionResponseEvent) {
            plugin.logger.info("[WSTransport] sending decision.response: $serialized")
        }

        try {
            socket.sendText(serialized, true)
        } catch (e: Exception) {
            plugin.logger.warning("WS send failed: ${e.message}")
        }
    }

    /**
     * Send a protobuf-generated [Message] over the wire as a typed, schema-enforced
     * payload.
     *
     * The envelope's `type` discriminator is derived from the message's proto
     * descriptor (`message.descriptorForType.fullName`, e.g.
     * `story.v1.LocationSightingStimulus`) — there is no separate string to
     * keep in sync. Go and Rust consumers dispatch on the same FQN.
     *
     * The payload is serialized as proto canonical JSON via [JsonFormat]: proto
     * field names (snake_case) become JSON keys (camelCase), repeated fields
     * become JSON arrays, int64 fields become JSON strings per proto3 JSON spec.
     * This matches what story-sim's prost-generated structs and story-go's
     * protojson decoders expect.
     *
     * This path intentionally bypasses [StoryEventBus] / [StoryEvent]: wire-only
     * messages have no business pretending to be in-process Bukkit events. If a
     * future plugin needs to react to such a message, the right shape is to add
     * a real Bukkit Event class and fire it from the inbound handler, NOT to
     * route the wire message through [StoryEventBus].
     */
    fun sendProto(message: Message) {
        val socket = ws ?: return
        val eventType = message.descriptorForType.fullName
        val raw = PROTO_JSON_PRINTER.print(message)
        val dataObj = json.parseToJsonElement(raw).jsonObject
        val envelope = BridgeMessage(type = eventType, data = dataObj, source = "story")
        val serialized = json.encodeToString(envelope)
        try {
            socket.sendText(serialized, true)
        } catch (e: Exception) {
            plugin.logger.warning("WS sendProto($eventType) failed: ${e.message}")
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
        // 1) Try the proto SimEvent path first. Sim→go→plugin traffic is wire-shaped
        //    as the bare protojson of a `story.v1.SimEvent` (no BridgeMessage envelope).
        //    Anything that parses cleanly AND has a populated oneof is dispatched here;
        //    everything else falls through to the legacy BridgeMessage path below.
        tryParseSimEvent(payload)?.let { simEvent ->
            val event = adaptSimEvent(simEvent)
            if (event != null) {
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable { inboundHandler?.invoke(event) },
                )
            }
            // Recognized SimEvent (even if no-op like SimInit) — do NOT fall through.
            return
        }

        // 2) Legacy BridgeMessage envelope path. Used for non-SimEvent inbound flows
        //    (intelligence.response, permission.ask, frontend.intent, etc.) and for
        //    plugin→go inbound replies that still use {type, source, timestamp, data}.
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

    /**
     * Attempt to parse [payload] as a proto-canonical-JSON [com.canefe.storyproto.v1.SimEvent].
     * Returns non-null only when parsing succeeds AND the oneof is set — a successful
     * parse with [com.canefe.storyproto.v1.SimEvent.EventCase.EVENT_NOT_SET] means the
     * payload happened to be a JSON object with no recognized fields (e.g. a
     * BridgeMessage envelope), and we want the legacy path to handle it.
     */
    private fun tryParseSimEvent(payload: String): com.canefe.storyproto.v1.SimEvent? =
        try {
            val builder = com.canefe.storyproto.v1.SimEvent.newBuilder()
            JsonFormat.parser().ignoringUnknownFields().merge(payload, builder)
            val built = builder.build()
            if (built.eventCase == com.canefe.storyproto.v1.SimEvent.EventCase.EVENT_NOT_SET) {
                null
            } else {
                built
            }
        } catch (_: Exception) {
            null
        }

    internal fun serializeEvent(event: SerializableStoryEvent): kotlinx.serialization.json.JsonElement =
        when (event) {
            is PlayerMessageEvent -> json.encodeToJsonElement(event)
            is NPCDamagedEvent -> json.encodeToJsonElement(event)
            is NPCInteractionEvent -> json.encodeToJsonElement(event)
            is NPCSpeakIntent -> json.encodeToJsonElement(event)
            is NPCEmoteIntent -> json.encodeToJsonElement(event)
            is NPCEmoteIconIntent -> json.encodeToJsonElement(event)
            is NPCActionIntent -> json.encodeToJsonElement(event)
            is PlayerProximityEvent -> json.encodeToJsonElement(event)
            is GMSpeakEvent -> json.encodeToJsonElement(event)
            is CharacterSpokeEvent -> json.encodeToJsonElement(event)
            is NPCPerceptEvent -> json.encodeToJsonElement(event)
            is CharacterPositionEvent -> json.encodeToJsonElement(event)
            is SpawnAffordanceEvent -> json.encodeToJsonElement(event)
            is PerceptionStimulusEvent -> json.encodeToJsonElement(event)
            is SimStatusEvent -> json.encodeToJsonElement(event)
            is NpcSpawnIntent -> json.encodeToJsonElement(event)
            is NpcStateIntent -> json.encodeToJsonElement(event)
            is DecisionResponseEvent -> json.encodeToJsonElement(event)
            is PermissionResponseEvent -> json.encodeToJsonElement(event)
            is NpcSpawnQueryEvent -> json.encodeToJsonElement(event)
            is NpcSpawnQueryResponseEvent -> json.encodeToJsonElement(event)
            is FrontendReadyEvent -> json.encodeToJsonElement(event)
            is FrontendPauseEvent -> json.encodeToJsonElement(event)
            is IntentCompletedEvent -> json.encodeToJsonElement(event)
            is IntentRejectedEvent -> json.encodeToJsonElement(event)
            is SessionStartEvent -> json.encodeToJsonElement(event)
            is SessionEndEvent -> json.encodeToJsonElement(event)
            is SessionAddPlayerEvent -> json.encodeToJsonElement(event)
            is LocationSpawnIntent -> json.encodeToJsonElement(event)
            is LocationCreateRequest -> json.encodeToJsonElement(event)
            is NpcSetOffersIntent -> json.encodeToJsonElement(event)
            is NpcGiveItemIntent -> json.encodeToJsonElement(event)
            is NpcGiveTraitIntent -> json.encodeToJsonElement(event)
            is NpcSetNeedIntent -> json.encodeToJsonElement(event)
            is NpcSetStatIntent -> json.encodeToJsonElement(event)
            is NpcKnowIntent -> json.encodeToJsonElement(event)
            is DMControlToggleEvent -> json.encodeToJsonElement(event)
            is CombatPlayerAttackEvent -> json.encodeToJsonElement(event)
            is CombatAttackResolvedEvent -> json.encodeToJsonElement(event)
            else -> json.encodeToJsonElement(mapOf("raw" to event.eventType))
        }

    private fun deserializeEvent(message: BridgeMessage): StoryEvent? {
        val data = message.data.toString()
        return try {
            when (message.type) {
                "npc.speak" -> json.decodeFromString<NPCSpeakIntent>(data)
                "npc.emote" -> json.decodeFromString<NPCEmoteIntent>(data)
                "npc.emote_icon" -> json.decodeFromString<NPCEmoteIconIntent>(data)
                "go_to" -> json.decodeFromString<GoToExecIntent>(data)
                "npc.action" -> json.decodeFromString<NPCActionIntent>(data)
                "npc.signal" -> json.decodeFromString<NPCSignalIntent>(data)
                "player.message" -> json.decodeFromString<PlayerMessageEvent>(data)
                "npc.damaged" -> json.decodeFromString<NPCDamagedEvent>(data)
                "npc.interaction" -> json.decodeFromString<NPCInteractionEvent>(data)
                "character.stats_update" -> json.decodeFromString<CharacterStatsUpdate>(data)
                "sim.status" -> json.decodeFromString<SimStatusEvent>(data)
                "sim.affordance_registry" -> json.decodeFromString<SimAffordanceRegistryEvent>(data)
                "npc.spawn" -> json.decodeFromString<NpcSpawnIntent>(data)
                "npc.state" -> json.decodeFromString<NpcStateIntent>(data)
                "npc.item_transfer" -> json.decodeFromString<NpcItemTransferIntent>(data)
                "frontend.intent" -> json.decodeFromString<FrontendIntentEvent>(data)
                "npc.spawn_query" -> json.decodeFromString<NpcSpawnQueryEvent>(data)
                "npc.spawn_query_response" -> json.decodeFromString<NpcSpawnQueryResponseEvent>(data)
                "frontend.ready" -> json.decodeFromString<FrontendReadyEvent>(data)
                "intent.session.started" -> json.decodeFromString<SessionStartedIntent>(data)
                "intent.session.ended" -> json.decodeFromString<SessionEndedIntent>(data)
                "intent.session.narration" -> json.decodeFromString<SessionNarrationIntent>(data)
                "combat.attack_resolved" -> json.decodeFromString<CombatAttackResolvedEvent>(data)
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

    companion object {
        /**
         * Single shared printer for [sendProto]. Configured for proto canonical JSON:
         * omitting insignificant whitespace (smaller frames) and including default
         * values (so the receiving prost struct doesn't have to distinguish
         * missing-vs-zero on optional numeric fields).
         */
        private val PROTO_JSON_PRINTER: JsonFormat.Printer =
            JsonFormat.printer()
                .omittingInsignificantWhitespace()
                .includingDefaultValueFields()
    }
}
