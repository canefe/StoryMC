package com.canefe.story.intelligence

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.bridge.StoryEventBus
import com.canefe.story.conversation.Conversation
import com.canefe.story.util.*
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

                // Refresh recognition-owned templates whenever caps land. This
                // covers startup and /story reload (which re-requests caps).
                if (isSupported(Method.GET_APPEARANCE_TEMPLATES) &&
                    plugin.isAppearanceTemplateCacheReady
                ) {
                    plugin.appearanceTemplateCache.refresh(this)
                }
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
                characterId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: npc.name,
                conversationId = conversation.id,
                history = conversation.history.takeLast(20).map { MessageDTO(it.role, it.content) },
                characterIds = conversation.npcNames,
                playerCharacterIds = conversation.players.mapNotNull { Bukkit.getPlayer(it)?.characterId },
            )

        return sendRequest(requestId, json.encodeToJsonElement(GenerateNPCResponseRequest.serializer(), dto).jsonObject)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"') ?: ""
            }.exceptionally { e ->
                plugin.logger.warning("Bridge generateNPCResponse failed, falling back to local: ${e.message}")
                local.generateNPCResponse(npc, conversation).get()
            }
    }

    override fun gmGhostwrite(
        npc: StoryNPC,
        draftMessage: String,
    ): CompletableFuture<String> {
        if (!isSupported(Method.GM_GHOSTWRITE)) return local.gmGhostwrite(npc, draftMessage)

        val (nearbyNpcIds, nearbyPlayerIds) =
            plugin.characterRegistry.getNearbyCharacterIds(
                npc,
                plugin.config.chatRadius,
            ) { !plugin.playerManager.isPlayerDisabled(it) }

        val requestId = UUID.randomUUID().toString()
        val dto =
            GMGhostwriteRequest(
                requestId = requestId,
                characterId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: npc.name,
                conversationId = -1,
                draftMessage = draftMessage,
                history = emptyList(),
                characterIds = nearbyNpcIds,
                playerCharacterIds = nearbyPlayerIds,
            )

        return sendRequest(requestId, json.encodeToJsonElement(GMGhostwriteRequest.serializer(), dto).jsonObject)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"') ?: ""
            }.exceptionally { e ->
                plugin.logger.warning("Bridge gmGhostwrite failed, falling back to local: ${e.message}")
                local.gmGhostwrite(npc, draftMessage).get()
            }
    }

    override fun playerGhostwrite(
        player: org.bukkit.entity.Player,
        characterId: String,
        characterName: String,
        draftMessage: String,
    ): CompletableFuture<String> {
        if (!isSupported(Method.GM_GHOSTWRITE)) {
            return local.playerGhostwrite(player, characterId, characterName, draftMessage)
        }

        val (nearbyNpcIds, nearbyPlayerIds) =
            plugin.characterRegistry.getNearbyCharacterIds(
                player,
                plugin.config.chatRadius,
            ) { !plugin.playerManager.isPlayerDisabled(it) }

        val requestId = UUID.randomUUID().toString()
        val dto =
            GMGhostwriteRequest(
                requestId = requestId,
                characterId = characterId,
                conversationId = -1,
                draftMessage = draftMessage,
                history = emptyList(),
                characterIds = nearbyNpcIds,
                playerCharacterIds = nearbyPlayerIds,
            )

        return sendRequest(requestId, json.encodeToJsonElement(GMGhostwriteRequest.serializer(), dto).jsonObject)
            .thenApply { response ->
                response["result"]?.toString()?.trim('"') ?: ""
            }.exceptionally { e ->
                plugin.logger.warning("Bridge playerGhostwrite failed, falling back to local: ${e.message}")
                local.playerGhostwrite(player, characterId, characterName, draftMessage).get()
            }
    }

    override fun selectNextSpeaker(conversation: Conversation): CompletableFuture<String?> {
        if (!isSupported(Method.SELECT_NEXT_SPEAKER)) return local.selectNextSpeaker(conversation)

        val requestId = UUID.randomUUID().toString()
        val dto =
            SelectNextSpeakerRequest(
                requestId = requestId,
                conversationId = conversation.id,
                characterIds = conversation.npcNames,
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

        val history = conversation.history
        if (history.size < 3 || conversation.npcs.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        val characterIds =
            conversation.npcs.mapNotNull { plugin.characterRegistry.getCharacterIdForNPC(it) }
        if (characterIds.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        val requestId = UUID.randomUUID().toString()
        val dto =
            SummarizeConversationRequest(
                requestId = requestId,
                conversationId = conversation.id,
                characterIds = characterIds,
                history = history.map { MessageDTO(it.role, it.content) },
                gameCreatedAt = plugin.timeService.getCurrentGameTime(),
            )

        return sendRequest(
            requestId,
            json.encodeToJsonElement(SummarizeConversationRequest.serializer(), dto).jsonObject,
        ).thenApply<Void> { null }
            .exceptionally { e ->
                plugin.logger.warning("Bridge summarizeConversation failed, falling back to local: ${e.message}")
                local.summarizeConversation(conversation).get()
            }
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
                speakerCharacterId = speakerName,
                message = message,
                characterIds =
                    conversation.npcs
                        .filter { it.name != speakerName && !conversation.mutedNPCs.contains(it) }
                        .map { plugin.characterRegistry.getCharacterIdForNPC(it) ?: it.name },
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
                characterIds = request.npcNames,
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

    /**
     * Ask the bridge (story-go → story-chargen) to procedurally generate `count`
     * characters from `template`. Returns a list of [GeneratedCharacterDTO], or an
     * empty list if the bridge does not support chargen.
     */
    fun requestCharacters(
        template: String,
        count: Int,
        locationOverride: String? = null,
    ): CompletableFuture<List<GeneratedCharacterDTO>> {
        if (!isSupported(Method.GENERATE_CHARACTERS)) {
            val f = CompletableFuture<List<GeneratedCharacterDTO>>()
            f.completeExceptionally(IllegalStateException("Bridge does not support generateCharacters"))
            return f
        }

        val requestId = UUID.randomUUID().toString()
        val dto =
            GenerateCharactersRequest(
                requestId = requestId,
                template = template,
                count = count,
                locationOverride = locationOverride,
            )

        return sendRequest(
            requestId,
            json.encodeToJsonElement(GenerateCharactersRequest.serializer(), dto).jsonObject,
        ).thenApply<List<GeneratedCharacterDTO>> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val result = response["result"] as? JsonObject
                ?: return@thenApply emptyList()
            val arr = result["characters"] as? JsonArray ?: return@thenApply emptyList()
            arr.map { json.decodeFromJsonElement(GeneratedCharacterDTO.serializer(), it) }
        }
    }

    // -----------------------------------------------------------------------
    // Recognition (story-go → story-recognition)
    // -----------------------------------------------------------------------

    /** Returns true when the bridge advertises recognition capabilities. */
    fun isRecognitionSupported(): Boolean = isSupported(Method.RESOLVE_NAMES)

    /** Record that perceiver now knows target by realName. */
    fun recognize(
        perceiverId: String,
        targetId: String,
        realName: String,
        source: String = "gm",
    ): CompletableFuture<Unit> {
        if (!isSupported(Method.RECOGNIZE)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support recognize"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = RecognizeRequest(requestId, perceiverId = perceiverId, targetId = targetId, realName = realName, source = source)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(RecognizeRequest.serializer(), dto).jsonObject,
        ).thenApply<Unit> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
        }
    }

    /** Drop a single recognition. */
    fun forgetRecognition(perceiverId: String, targetId: String): CompletableFuture<Unit> {
        if (!isSupported(Method.FORGET_RECOGNITION)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support forgetRecognition"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = ForgetRecognitionRequest(requestId, perceiverId = perceiverId, targetId = targetId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(ForgetRecognitionRequest.serializer(), dto).jsonObject,
        ).thenApply<Unit> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
        }
    }

    /** Returns (known, realName?). */
    fun knowsCharacter(
        perceiverId: String,
        targetId: String,
    ): CompletableFuture<Pair<Boolean, String?>> {
        if (!isSupported(Method.KNOWS_CHARACTER)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support knowsCharacter"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = KnowsCharacterRequest(requestId, perceiverId = perceiverId, targetId = targetId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(KnowsCharacterRequest.serializer(), dto).jsonObject,
        ).thenApply { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject ?: return@thenApply false to null
            val known = (res["known"]?.toString() ?: "false").trim('"').toBoolean()
            val realName = res["realName"]?.toString()?.trim('"')?.takeIf { it != "null" }
            known to realName
        }
    }

    /**
     * Returns the perceiver's full known-of map (targetId → realName).
     * Used to seed the StoryClient recognition cache on player join.
     */
    fun knownOf(perceiverId: String): CompletableFuture<Map<String, String>> {
        if (!isSupported(Method.KNOWN_OF)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support knownOf"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = KnownOfRequest(requestId, perceiverId = perceiverId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(KnownOfRequest.serializer(), dto).jsonObject,
        ).thenApply<Map<String, String>> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject ?: return@thenApply emptyMap()
            val known = res["known"] as? JsonObject ?: return@thenApply emptyMap()
            known.mapValues { (_, v) ->
                val obj = v as? JsonObject ?: return@mapValues ""
                obj["realName"]?.toString()?.trim('"') ?: ""
            }.filterValues { it.isNotEmpty() }
        }
    }

    /**
     * Batch-resolve a set of target IDs against the perceiver's recognition set.
     * The choke-point used by render-time code paths (NearbyNPCBroadcaster,
     * prompt rendering) so we hit the bridge once per perceiver per render.
     */
    fun resolveNames(
        perceiverId: String,
        targetIds: List<String>,
    ): CompletableFuture<List<ResolvedTargetDTO>> {
        if (targetIds.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        if (!isSupported(Method.RESOLVE_NAMES)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support resolveNames"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = ResolveNamesRequest(requestId, perceiverId = perceiverId, targetIds = targetIds)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(ResolveNamesRequest.serializer(), dto).jsonObject,
        ).thenApply<List<ResolvedTargetDTO>> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject ?: return@thenApply emptyList()
            val arr = res["targets"] as? JsonArray ?: return@thenApply emptyList()
            arr.map { json.decodeFromJsonElement(ResolvedTargetDTO.serializer(), it) }
        }
    }

    /** Returns the fallback descriptor for a character. */
    fun getDescriptor(characterId: String): CompletableFuture<String> {
        if (!isSupported(Method.GET_DESCRIPTOR)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support getDescriptor"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = GetDescriptorRequest(requestId, characterId = characterId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(GetDescriptorRequest.serializer(), dto).jsonObject,
        ).thenApply { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject
                ?: return@thenApply ""
            res["descriptor"]?.toString()?.trim('"') ?: ""
        }
    }

    /** Sets the fallback descriptor for a character. */
    fun setDescriptor(characterId: String, descriptor: String): CompletableFuture<Unit> {
        if (!isSupported(Method.SET_DESCRIPTOR)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support setDescriptor"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = SetDescriptorRequest(requestId, characterId = characterId, descriptor = descriptor)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(SetDescriptorRequest.serializer(), dto).jsonObject,
        ).thenApply<Unit> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
        }
    }

    /** True when the bridge advertises the `describe` method (per-perceiver render). */
    fun isDescribeSupported(): Boolean = isSupported(Method.DESCRIBE)

    /** Push the structured appearance trait map for a character. */
    fun setAppearance(
        characterId: String,
        gender: String,
        traits: Map<String, String>,
    ): CompletableFuture<Unit> {
        if (!isSupported(Method.SET_APPEARANCE)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support setAppearance"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = SetAppearanceRequest(requestId, characterId = characterId, gender = gender, traits = traits)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(SetAppearanceRequest.serializer(), dto).jsonObject,
        ).thenApply<Unit> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
        }
    }

    /**
     * Per-perceiver render. Returns real name when perceiver knows target, plus
     * appearance prose (or descriptor fallback). Used by render-time code paths
     * to replace local `toProse()` rendering.
     */
    fun describe(
        perceiverId: String,
        targetId: String,
    ): CompletableFuture<DescribeResultDTO> {
        if (!isSupported(Method.DESCRIBE)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support describe"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = DescribeRequest(requestId, perceiverId = perceiverId, targetId = targetId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(DescribeRequest.serializer(), dto).jsonObject,
        ).thenApply { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject
                ?: throw RuntimeException("describe: missing result")
            json.decodeFromJsonElement(DescribeResultDTO.serializer(), res)
        }
    }

    /**
     * Fetch the pronoun + slot templates from story-recognition. Cached by
     * [AppearanceTemplateCache]; called at startup and on /story reload.
     */
    fun getAppearanceTemplates(): CompletableFuture<AppearanceTemplatesDTO> {
        if (!isSupported(Method.GET_APPEARANCE_TEMPLATES)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support getAppearanceTemplates"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = GetAppearanceTemplatesRequest(requestId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(GetAppearanceTemplatesRequest.serializer(), dto).jsonObject,
        ).thenApply { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject
                ?: throw RuntimeException("getAppearanceTemplates: missing result")
            json.decodeFromJsonElement(AppearanceTemplatesDTO.serializer(), res)
        }
    }

    /** Fetches the perception log for a character from the orchestrator. */
    fun getPerceptions(characterId: String): CompletableFuture<List<PerceptionEntryDTO>> {
        if (!isSupported(Method.GET_PERCEPTIONS)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support getPerceptions"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = GetPerceptionsRequest(requestId, characterId = characterId)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(GetPerceptionsRequest.serializer(), dto).jsonObject,
        ).thenApply<List<PerceptionEntryDTO>> { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject ?: return@thenApply emptyList()
            val arr = res["entries"] as? JsonArray ?: return@thenApply emptyList()
            arr.map { json.decodeFromJsonElement(PerceptionEntryDTO.serializer(), it) }
        }
    }

    /** Removes the perception entry at [index] for [characterId]. In-memory in story-go. */
    fun forgetPerception(characterId: String, index: Int): CompletableFuture<Boolean> {
        if (!isSupported(Method.FORGET_PERCEPTION)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Bridge does not support forgetPerception"),
            )
        }
        val requestId = UUID.randomUUID().toString()
        val dto = ForgetPerceptionRequest(requestId, characterId = characterId, index = index)
        return sendRequest(
            requestId,
            json.encodeToJsonElement(ForgetPerceptionRequest.serializer(), dto).jsonObject,
        ).thenApply { response ->
            response["error"]?.let { throw RuntimeException(it.toString().trim('"')) }
            val res = response["result"] as? JsonObject ?: return@thenApply false
            res["removed"]?.toString()?.trim('"') == "true"
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
