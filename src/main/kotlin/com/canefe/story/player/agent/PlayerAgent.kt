package com.canefe.story.player.agent

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background agent that tracks and periodically analyses a single player.
 *
 * Each agent:
 * - Collects a rolling observation buffer of notable player events (chat, movement, combat)
 * - Calls an LLM on a configurable interval to produce a [PlayerAgentReport]
 * - Exposes the latest report and the raw observation log to other systems
 *
 * The LLM endpoint is either the plugin-wide StoryAI (OpenRouter) or a custom URL
 * configured in config.yml via `player-agent.custom-endpoint`.
 */
class PlayerAgent(
    private val plugin: Story,
    val playerUUID: UUID,
    private val scheduler: ScheduledExecutorService,
) {
    val playerName: String
        get() = plugin.server.getOfflinePlayer(playerUUID).name ?: playerUUID.toString()

    /** Ring-buffer of recent observations fed to the LLM. */
    private val observations = ArrayDeque<String>(MAX_OBSERVATIONS)

    /** Latest analysis result. Null until the first cycle completes. */
    @Volatile
    var latestReport: PlayerAgentReport? = null
        private set

    private val analysisInProgress = AtomicBoolean(false)
    private var scheduledTask: ScheduledFuture<*>? = null

    // Custom-endpoint HTTP client (lazy, only created when needed)
    private val customHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Start the recurring analysis loop. */
    fun start() {
        val intervalSeconds = plugin.config.playerAgentIntervalSeconds.toLong()
        scheduledTask =
            scheduler.scheduleAtFixedRate(
                ::runAnalysisCycle,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS,
            )
        plugin.logger.info("[PlayerAgent] Started agent for $playerName (interval ${intervalSeconds}s)")
    }

    /** Stop the agent and cancel the scheduled task. */
    fun stop() {
        scheduledTask?.cancel(false)
        scheduledTask = null
        plugin.logger.info("[PlayerAgent] Stopped agent for $playerName")
    }

    /**
     * Record a notable observation about this player.
     * Call this from the main thread or any async context — it is thread-safe.
     */
    fun observe(observation: String) {
        synchronized(observations) {
            if (observations.size >= MAX_OBSERVATIONS) observations.removeFirst()
            observations.addLast(observation)
        }
    }

    // ------------------------------------------------------------------
    // Internal

    private fun runAnalysisCycle() {
        if (!analysisInProgress.compareAndSet(false, true)) return

        val snapshot: List<String>
        synchronized(observations) {
            snapshot = observations.toList()
        }

        if (snapshot.isEmpty()) {
            analysisInProgress.set(false)
            return
        }

        val player: Player? = plugin.server.getPlayer(playerUUID)
        val locationName = plugin.playerManager.lastLocation[playerUUID]?.name ?: "unknown"
        val questTitle = if (player != null) plugin.playerManager.getQuestTitle(player) else ""
        val teamName = plugin.playerManager.getPlayerTeam(playerUUID) ?: "none"
        val inConversation = player != null && plugin.conversationManager.getConversation(player) != null

        val systemPrompt =
            plugin.promptService.getPlayerAnalysisPrompt(
                playerName = playerName,
                location = locationName,
                questTitle = questTitle.ifBlank { "none" },
                team = teamName,
                inConversation = inConversation.toString(),
            )

        val observationText = snapshot.joinToString("\n")

        val messages =
            listOf(
                ConversationMessage("system", systemPrompt),
                ConversationMessage("user", observationText),
            )

        val customEndpoint = plugin.config.playerAgentCustomEndpoint

        val future: CompletableFuture<String?> =
            if (customEndpoint.isNotBlank()) {
                callCustomEndpoint(customEndpoint, messages)
            } else {
                plugin.getAIResponse(messages, lowCost = true)
            }

        future
            .thenAccept { response ->
                if (!response.isNullOrBlank()) {
                    latestReport = parseReport(response)
                    if (plugin.config.debugMessages) {
                        plugin.logger.info("[PlayerAgent] $playerName report: $latestReport")
                    }
                }
            }.exceptionally { ex ->
                plugin.logger.warning("[PlayerAgent] Analysis failed for $playerName: ${ex.message}")
                null
            }.whenComplete { _, _ ->
                analysisInProgress.set(false)
            }
    }

    /**
     * Parses the LLM response (expected JSON) into a [PlayerAgentReport].
     * Falls back to a minimal report on parse errors.
     *
     * Expected format:
     * ```json
     * {
     *   "mood": "curious",
     *   "tags": "exploring, quest-active",
     *   "notes": "Player is investigating the ruins near Yohg..."
     * }
     * ```
     */
    private fun parseReport(raw: String): PlayerAgentReport =
        try {
            val json = plugin.gson.fromJson(raw.trim(), JsonObject::class.java)
            val mood = json.get("mood")?.asString ?: "neutral"
            val tags =
                json
                    .get("tags")
                    ?.asString
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            val notes = json.get("notes")?.asString ?: ""
            PlayerAgentReport(
                playerName = playerName,
                mood = mood,
                behaviorTags = tags,
                narrativeNotes = notes,
            )
        } catch (e: Exception) {
            PlayerAgentReport(
                playerName = playerName,
                mood = "unknown",
                behaviorTags = emptyList(),
                narrativeNotes = raw.take(200),
            )
        }

    private fun callCustomEndpoint(
        endpoint: String,
        messages: List<ConversationMessage>,
    ): CompletableFuture<String?> =
        CompletableFuture.supplyAsync(
            {
                try {
                    val messagesArray = com.google.gson.JsonArray()
                    for (msg in messages) {
                        val obj = JsonObject()
                        obj.addProperty("role", msg.role)
                        obj.addProperty("content", msg.content)
                        messagesArray.add(obj)
                    }
                    val requestBody = JsonObject()
                    requestBody.add("messages", messagesArray)
                    requestBody.addProperty("model", plugin.config.aiConversationModel)

                    val body =
                        requestBody
                            .toString()
                            .toRequestBody("application/json".toMediaTypeOrNull())

                    val request =
                        Request
                            .Builder()
                            .url(endpoint)
                            .addHeader("Authorization", "Bearer ${plugin.config.openAIKey}")
                            .addHeader("Content-Type", "application/json")
                            .post(body)
                            .build()

                    customHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@supplyAsync null
                        val responseText = response.body?.string() ?: return@supplyAsync null
                        val json = plugin.gson.fromJson(responseText, JsonObject::class.java)
                        json
                            .getAsJsonArray("choices")
                            ?.get(0)
                            ?.asJsonObject
                            ?.getAsJsonObject("message")
                            ?.get("content")
                            ?.asString
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("[PlayerAgent] Custom endpoint error: ${e.message}")
                    null
                }
            },
            Executors.newVirtualThreadPerTaskExecutor(),
        )

    companion object {
        private const val MAX_OBSERVATIONS = 30
    }
}
