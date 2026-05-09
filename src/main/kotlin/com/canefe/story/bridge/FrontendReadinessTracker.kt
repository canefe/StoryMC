package com.canefe.story.bridge

import com.canefe.story.Story
import org.bukkit.Bukkit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when StoryMC has reached a state from which story-sim can safely
 * tick on real positions. Three flags must all be true, after which a single
 * [FrontendReadyEvent] is emitted per sim session:
 *
 * 1. simRunning — last [SimStatusEvent] was running=true
 * 2. positionsTickedSinceSim — at least one [com.canefe.story.npc.PositionBroadcaster]
 *    tick has completed after simRunning became true
 * 3. reconcileResponseApplied OR fallback timer fired — at least one
 *    [com.canefe.story.npc.ReconciliationService] response has been applied,
 *    OR [com.canefe.story.config.ConfigService.frontendReadyFallbackMillis]
 *    has elapsed since simRunning became true (covers headless / no-player
 *    scenarios).
 *
 * Reset on simRunning=false so a sim restart re-arms the handshake.
 */
class FrontendReadinessTracker(private val plugin: Story) {

    @Volatile private var simRunning: Boolean = false
    @Volatile private var positionsTicked: Boolean = false
    @Volatile private var reconcileApplied: Boolean = false
    private val emitted = AtomicBoolean(false)
    private var fallbackTaskId: Int = -1

    fun start() {
        plugin.eventBus.on<SimStatusEvent> { onSimStatus(it.running) }
    }

    private fun onSimStatus(running: Boolean) {
        if (!running) {
            simRunning = false
            positionsTicked = false
            reconcileApplied = false
            emitted.set(false)
            cancelFallback()
            return
        }
        if (simRunning) return
        simRunning = true
        scheduleFallback()
    }

    private fun scheduleFallback() {
        cancelFallback()
        val ticks = plugin.configService.frontendReadyFallbackMillis / 50L
        fallbackTaskId = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                plugin.logger.info("[FrontendReadiness] fallback fired; treating reconcile as satisfied")
                reconcileApplied = true
                tryEmit()
            },
            ticks,
        ).taskId
    }

    private fun cancelFallback() {
        if (fallbackTaskId != -1) {
            Bukkit.getScheduler().cancelTask(fallbackTaskId)
            fallbackTaskId = -1
        }
    }

    fun markPositionsTicked() {
        if (!simRunning) return
        if (positionsTicked) return
        positionsTicked = true
        tryEmit()
    }

    fun markReconcileApplied() {
        if (!simRunning) return
        if (reconcileApplied) return
        reconcileApplied = true
        tryEmit()
    }

    private fun tryEmit() {
        if (!simRunning || !positionsTicked || !reconcileApplied) return
        if (!emitted.compareAndSet(false, true)) return
        cancelFallback()
        val world = Bukkit.getWorlds().firstOrNull()?.name ?: ""
        plugin.logger.info("[FrontendReadiness] emitting FrontendReadyEvent world=$world")
        plugin.eventBus.emit(FrontendReadyEvent(world = world))
    }
}
