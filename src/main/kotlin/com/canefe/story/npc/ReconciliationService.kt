package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.IntentExecutor
import com.canefe.story.bridge.NpcSpawnQueryEvent
import com.canefe.story.bridge.NpcSpawnQueryResponseEvent
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends NpcSpawnQueryEvents to story-go and applies the resulting
 * NpcSpawnIntents. Tracks pending requests in a concurrent map keyed by
 * requestId; entries time out after [com.canefe.story.config.ConfigService.reconcileResponseTimeoutMillis].
 */
class ReconciliationService(private val plugin: Story) {

    private data class PendingRequest(val timeoutTaskId: Int)

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    /** Set when at least one response has been applied; read by FrontendReadinessTracker. */
    @Volatile
    var hasAppliedAnyResponse: Boolean = false
        private set

    fun start() {
        plugin.eventBus.on<NpcSpawnQueryResponseEvent> { handleResponse(it) }
    }

    fun stop() {
        // ConcurrentHashMap is left for GC; no listener teardown needed.
    }

    fun requestNearby(world: String, x: Double, y: Double, z: Double, radius: Double, source: String) {
        val requestId = UUID.randomUUID().toString()
        plugin.logger.info(
            "[Reconcile] requestNearby src=$source requestId=$requestId world=$world " +
                "pos=($x,$y,$z) radius=$radius"
        )
        val timeoutMs = plugin.configService.reconcileResponseTimeoutMillis
        val timeoutTaskId = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (pending.remove(requestId) != null) {
                    plugin.logger.warning("[Reconcile] timeout requestId=$requestId src=$source")
                }
            },
            (timeoutMs / 50L),
        ).taskId
        pending[requestId] = PendingRequest(timeoutTaskId)

        plugin.eventBus.emit(
            NpcSpawnQueryEvent(
                requestId = requestId,
                world = world,
                x = x,
                y = y,
                z = z,
                radius = radius,
            ),
        )
    }

    private fun handleResponse(event: NpcSpawnQueryResponseEvent) {
        plugin.logger.info("[Reconcile] handleResponse entered requestId=${event.requestId} pending=${pending.size}")
        val entry = pending.remove(event.requestId)
        if (entry == null) {
            plugin.logger.warning("[Reconcile] response for unknown requestId=${event.requestId}, dropping")
            return
        }
        Bukkit.getScheduler().cancelTask(entry.timeoutTaskId)

        var applied = 0
        var skipped = 0
        for (intent in event.intents) {
            val existing = if (plugin.isNpcRegistryReady) {
                plugin.npcRegistry.all().firstOrNull {
                    plugin.characterRegistry.getCharacterIdForNPC(it) == intent.characterId
                }
            } else null
            if (existing?.isSpawned == true) {
                skipped++
                continue
            }
            IntentExecutor.executeNpcSpawnIntent(plugin, intent)
            applied++
        }
        hasAppliedAnyResponse = true
        plugin.logger.info(
            "[Reconcile] response requestId=${event.requestId} total=${event.intents.size} " +
                "applied=$applied alreadySpawned=$skipped"
        )
        try {
            plugin.frontendReadinessTracker.markReconcileApplied()
        } catch (_: UninitializedPropertyAccessException) {
            // ignore in tests
        }
    }
}
