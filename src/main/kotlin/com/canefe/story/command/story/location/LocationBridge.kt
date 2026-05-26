package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.bridge.LocationCreateRequest
import com.canefe.story.bridge.StoryEventBus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Outcome of a location.create request handled by story-go. */
data class LocationCreateResult(
    val ok: Boolean,
    val locationId: String = "",
    val name: String = "",
    val tags: List<String> = emptyList(),
    val radius: Double = 0.0,
    val error: String = "",
)

/**
 * Plugin-side client for the story-go location.create request/reply.
 * The Kotlin plugin no longer writes to mongo directly — it asks story-go to
 * create the location, which (a) looks up the template via the sim, (b) merges
 * defaults, (c) persists to mongo, (d) spawns the location in the sim, then
 * (e) replies here.
 */
class LocationBridge(
    private val plugin: Story,
    private val eventBus: StoryEventBus,
) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<LocationCreateResult>>()

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private const val RESPONSE_TYPE = "location.create.response"
    }

    init {
        eventBus.onType(RESPONSE_TYPE) { event ->
            val data = event.toWireData() ?: return@onType
            val requestId = (data["requestId"] as? JsonPrimitive)?.content ?: return@onType
            val future = pending.remove(requestId) ?: return@onType
            future.complete(parseResult(data))
        }
    }

    fun createLocation(
        name: String,
        template: String,
        world: String,
        x: Double,
        y: Double,
        z: Double,
    ): CompletableFuture<LocationCreateResult> {
        val requestId = UUID.randomUUID().toString()
        val future = CompletableFuture<LocationCreateResult>()
        pending[requestId] = future

        eventBus.emit(
            LocationCreateRequest(
                requestId = requestId,
                name = name,
                template = template,
                world = world,
                x = x,
                y = y,
                z = z,
            ),
        )

        return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally { e ->
            pending.remove(requestId)
            plugin.logger.warning("location.create request $requestId failed: ${e.message}")
            LocationCreateResult(ok = false, error = e.message ?: "timeout")
        }
    }

    private fun parseResult(data: JsonObject): LocationCreateResult {
        val ok = (data["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!ok) {
            val err = (data["error"] as? JsonPrimitive)?.content.orEmpty()
            return LocationCreateResult(ok = false, error = err)
        }
        val locationId = (data["locationId"] as? JsonPrimitive)?.content.orEmpty()
        val name = (data["name"] as? JsonPrimitive)?.content.orEmpty()
        val radius = (data["radius"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val tags =
            (data["tags"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            } ?: emptyList()
        return LocationCreateResult(
            ok = true,
            locationId = locationId,
            name = name,
            tags = tags,
            radius = radius,
        )
    }
}
