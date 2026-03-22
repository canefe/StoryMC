package com.canefe.story.intelligence

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.bridge.StoryEventBus
import com.canefe.story.conversation.Conversation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bridge implementation of StoryIntelligence that delegates to the Go orchestrator
 * via WebSocket. Only routes methods the bridge has declared as supported.
 * Everything else goes straight to local — no timeout waiting.
 *
 * On initialization, sends a capabilities request to the bridge. The bridge responds
 * with a list of supported method names. Only those methods are routed remotely.
 */
class BridgeIntelligence(
    private val plugin: Story,
    private val local: LocalIntelligence,
    private val eventBus: StoryEventBus,
) : StoryIntelligence {
    private val pendingRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()

    // Methods the bridge has declared support for
    private val supportedMethods = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TIMEOUT_SECONDS = 10L
    }

    init {
        // Listen for responses from the Go orchestrator
        eventBus.onType("intelligence.response") { event ->
            val data = event.toWireData() ?: return@onType
            val requestId = data["requestId"]?.toString()?.trim('"') ?: return@onType
            pendingRequests.remove(requestId)?.complete(data)
        }

        // Listen for capabilities response
        eventBus.onType("intelligence.capabilities") { event ->
            val data = event.toWireData() ?: return@onType
            val methods = data["methods"]
            if (methods is kotlinx.serialization.json.JsonArray) {
                supportedMethods.clear()
                methods.forEach { element ->
                    val name = element.toString().trim('"')
                    supportedMethods.add(name)
                }
                plugin.logger.info("Bridge intelligence capabilities: $supportedMethods")
            }
        }
    }

    /**
     * Requests the list of supported methods from the bridge.
     * Called during event bus initialization / config reload.
     */
    fun requestCapabilities() {
        val request =
            buildJsonObject {
                put(
                    "requestId",
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
                put("method", "getCapabilities")
            }
        eventBus.emit(
            object : com.canefe.story.bridge.StoryEvent {
                override val eventType: String = "intelligence.request"

                override fun toWireData(): JsonObject = request
            },
        )
        plugin.logger.info("Requested intelligence capabilities from bridge")
    }

    private fun isSupported(method: String): Boolean = supportedMethods.contains(method)

    override fun generateNPCResponse(
        npc: StoryNPC,
        conversation: Conversation,
    ): CompletableFuture<String> {
        if (!isSupported("generateNPCResponse")) return local.generateNPCResponse(npc, conversation)

        val requestId =
            java.util.UUID
                .randomUUID()
                .toString()
        val request =
            buildJsonObject {
                put("requestId", requestId)
                put("method", "generateNPCResponse")
                put("npcName", npc.name)
                put("conversationId", conversation.id)
                putJsonArray("history") {
                    conversation.history.takeLast(20).forEach { msg ->
                        add(
                            buildJsonObject {
                                put("role", msg.role)
                                put("content", msg.content)
                            },
                        )
                    }
                }
                putJsonArray("npcNames") { conversation.npcNames.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("playerNames") {
                    conversation.players
                        .mapNotNull {
                            org.bukkit.Bukkit
                                .getPlayer(it)
                                ?.name
                        }.forEach { add(JsonPrimitive(it)) }
                }
            }

        return sendRequest(requestId, "intelligence.request", request)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"') ?: ""
            }.exceptionally { e ->
                plugin.logger.warning("Bridge generateNPCResponse failed, falling back to local: ${e.message}")
                local.generateNPCResponse(npc, conversation).get()
            }
    }

    override fun selectNextSpeaker(conversation: Conversation): CompletableFuture<String?> {
        if (!isSupported("selectNextSpeaker")) return local.selectNextSpeaker(conversation)

        val requestId =
            java.util.UUID
                .randomUUID()
                .toString()
        val request =
            buildJsonObject {
                put("requestId", requestId)
                put("method", "selectNextSpeaker")
                put("conversationId", conversation.id)
                putJsonArray("npcNames") { conversation.npcNames.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("history") {
                    conversation.history.takeLast(10).forEach { msg ->
                        add(
                            buildJsonObject {
                                put("role", msg.role)
                                put("content", msg.content)
                            },
                        )
                    }
                }
            }

        return sendRequest(requestId, "intelligence.request", request)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"')
            }.exceptionally { e ->
                plugin.logger.warning("Bridge selectNextSpeaker failed, falling back to local: ${e.message}")
                local.selectNextSpeaker(conversation).get()
            }
    }

    override fun summarizeConversation(conversation: Conversation): CompletableFuture<Void> {
        if (!isSupported("summarizeConversation")) return local.summarizeConversation(conversation)
        // For now, summarization stays local even if supported — complex side effects
        return local.summarizeConversation(conversation)
    }

    override fun generateNPCReactions(
        conversation: Conversation,
        speakerName: String,
        message: String,
    ): CompletableFuture<Map<String, String>> {
        if (!isSupported("generateNPCReactions")) return local.generateNPCReactions(conversation, speakerName, message)

        val requestId =
            java.util.UUID
                .randomUUID()
                .toString()
        val request =
            buildJsonObject {
                put("requestId", requestId)
                put("method", "generateNPCReactions")
                put("conversationId", conversation.id)
                put("speakerName", speakerName)
                put("message", message)
                putJsonArray("npcNames") {
                    conversation.npcs
                        .filter { it.name != speakerName && !conversation.mutedNPCs.contains(it) }
                        .forEach { add(JsonPrimitive(it.name)) }
                }
            }

        return sendRequest(requestId, "intelligence.request", request)
            .thenApply<Map<String, String>> { response ->
                val result = mutableMapOf<String, String>()
                val reactionsObj = response["result"]
                if (reactionsObj is JsonObject) {
                    reactionsObj.forEach { (name, value) ->
                        result[name] = value.toString().trim('"')
                    }
                }
                result
            }.exceptionally { e ->
                plugin.logger.warning("Bridge generateNPCReactions failed, falling back to local: ${e.message}")
                local.generateNPCReactions(conversation, speakerName, message).get()
            }
    }

    override fun summarizeMessageHistory(conversation: Conversation): CompletableFuture<String?> {
        if (!isSupported("summarizeMessageHistory")) return local.summarizeMessageHistory(conversation)
        return local.summarizeMessageHistory(conversation)
    }

    private fun sendRequest(
        requestId: String,
        type: String,
        data: JsonObject,
    ): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        pendingRequests[requestId] = future

        eventBus.emit(
            object : com.canefe.story.bridge.StoryEvent {
                override val eventType: String = type

                override fun toWireData(): JsonObject = data
            },
        )

        future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally {
            pendingRequests.remove(requestId)
            null
        }

        return future
    }
}
