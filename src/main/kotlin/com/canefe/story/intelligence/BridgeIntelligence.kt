package com.canefe.story.intelligence

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.bridge.StoryEventBus
import com.canefe.story.conversation.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.bukkit.Bukkit
import java.util.UUID
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
    private val supportedMethods = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TIMEOUT_SECONDS = 60L
        private val json = Json { encodeDefaults = true }
    }

    init {
        eventBus.onType(EventType.INTELLIGENCE_RESPONSE) { event ->
            val data = event.toWireData() ?: return@onType
            val requestId = data["requestId"]?.toString()?.trim('"') ?: return@onType
            pendingRequests.remove(requestId)?.complete(data)
        }

        eventBus.onType(EventType.INTELLIGENCE_CAPABILITIES) { event ->
            val data = event.toWireData() ?: return@onType
            val methods = data["methods"]
            if (methods is JsonArray) {
                supportedMethods.clear()
                methods.forEach { element ->
                    supportedMethods.add(element.toString().trim('"'))
                }
                plugin.logger.info("Bridge intelligence capabilities: $supportedMethods")
            }
        }
    }

    fun requestCapabilities() {
        val dto = CapabilitiesRequest(requestId = UUID.randomUUID().toString())
        emitDto(dto)
        plugin.logger.info("Requested intelligence capabilities from bridge")
    }

    private fun isSupported(method: String): Boolean = supportedMethods.contains(method)

    override fun generateNPCResponse(
        npc: StoryNPC,
        conversation: Conversation,
    ): CompletableFuture<String> {
        if (!isSupported(Method.GENERATE_NPC_RESPONSE)) return local.generateNPCResponse(npc, conversation)

        val requestId = UUID.randomUUID().toString()
        val dto =
            GenerateNPCResponseRequest(
                requestId = requestId,
                npcName = npc.name,
                conversationId = conversation.id,
                history = conversation.history.takeLast(20).map { MessageDTO(it.role, it.content) },
                npcNames = conversation.npcNames,
                playerNames = conversation.players.mapNotNull { Bukkit.getPlayer(it)?.name },
            )

        return sendRequest(requestId, json.encodeToJsonElement(GenerateNPCResponseRequest.serializer(), dto).jsonObject)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"') ?: ""
            }.exceptionally { e ->
                plugin.logger.warning("Bridge generateNPCResponse failed, falling back to local: ${e.message}")
                local.generateNPCResponse(npc, conversation).get()
            }
    }

    override fun selectNextSpeaker(conversation: Conversation): CompletableFuture<String?> {
        if (!isSupported(Method.SELECT_NEXT_SPEAKER)) return local.selectNextSpeaker(conversation)

        val requestId = UUID.randomUUID().toString()
        val dto =
            SelectNextSpeakerRequest(
                requestId = requestId,
                conversationId = conversation.id,
                npcNames = conversation.npcNames,
                history = conversation.history.takeLast(10).map { MessageDTO(it.role, it.content) },
            )

        return sendRequest(requestId, json.encodeToJsonElement(SelectNextSpeakerRequest.serializer(), dto).jsonObject)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"')
            }.exceptionally { e ->
                plugin.logger.warning("Bridge selectNextSpeaker failed, falling back to local: ${e.message}")
                local.selectNextSpeaker(conversation).get()
            }
    }

    override fun summarizeConversation(conversation: Conversation): CompletableFuture<Void> {
        if (!isSupported(Method.SUMMARIZE_CONVERSATION)) return local.summarizeConversation(conversation)
        // For now, summarization stays local even if supported — complex side effects
        return local.summarizeConversation(conversation)
    }

    override fun generateNPCReactions(
        conversation: Conversation,
        speakerName: String,
        message: String,
    ): CompletableFuture<Map<String, String>> {
        if (!isSupported(Method.GENERATE_NPC_REACTIONS)) {
            return local.generateNPCReactions(conversation, speakerName, message)
        }

        val requestId = UUID.randomUUID().toString()
        val dto =
            GenerateNPCReactionsRequest(
                requestId = requestId,
                conversationId = conversation.id,
                speakerName = speakerName,
                message = message,
                npcNames =
                    conversation.npcs
                        .filter { it.name != speakerName && !conversation.mutedNPCs.contains(it) }
                        .map { it.name },
            )

        return sendRequest(
            requestId,
            json.encodeToJsonElement(GenerateNPCReactionsRequest.serializer(), dto).jsonObject,
        ).thenApply<Map<String, String>> { response ->
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
        if (!isSupported(Method.SUMMARIZE_MESSAGE_HISTORY)) return local.summarizeMessageHistory(conversation)
        return local.summarizeMessageHistory(conversation)
    }

    override fun processConversationInformation(request: ConversationInformationRequest): CompletableFuture<Void> {
        if (!isSupported(Method.PROCESS_CONVERSATION_INFORMATION)) {
            return local.processConversationInformation(request)
        }

        val requestId = UUID.randomUUID().toString()
        val dto =
            ProcessConversationInformationRequest(
                requestId = requestId,
                locationName = request.locationName,
                npcNames = request.npcNames,
                messages = request.messages.map { MessageDTO(it.role, it.content) },
                relevantLocations = request.relevantLocations,
            )

        return sendRequest(
            requestId,
            json.encodeToJsonElement(ProcessConversationInformationRequest.serializer(), dto).jsonObject,
        ).thenApply<Void> { null }
            .exceptionally { e ->
                plugin.logger.warning(
                    "Bridge processConversationInformation failed, falling back to local: ${e.message}",
                )
                local.processConversationInformation(request).get()
                null
            }
    }

    private inline fun <reified T> emitDto(dto: T) where T : Any {
        val jsonObject =
            json
                .encodeToJsonElement(
                    kotlinx.serialization.serializer<T>(),
                    dto,
                ).jsonObject
        eventBus.emit(
            object : com.canefe.story.bridge.StoryEvent {
                override val eventType: String = EventType.INTELLIGENCE_REQUEST

                override fun toWireData(): JsonObject = jsonObject
            },
        )
    }

    private fun sendRequest(
        requestId: String,
        data: JsonObject,
    ): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        pendingRequests[requestId] = future

        eventBus.emit(
            object : com.canefe.story.bridge.StoryEvent {
                override val eventType: String = EventType.INTELLIGENCE_REQUEST

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
