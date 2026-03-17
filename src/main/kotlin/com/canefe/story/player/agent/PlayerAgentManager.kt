package com.canefe.story.player.agent

import com.canefe.story.Story
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages one [PlayerAgent] per online player.
 *
 * Lifecycle:
 * - [startAgent] — call on PlayerJoinEvent
 * - [stopAgent]  — call on PlayerQuitEvent
 * - [shutdown]   — call on plugin disable (stops all agents and the shared scheduler)
 */
class PlayerAgentManager(
    private val plugin: Story,
) {
    private val agents = ConcurrentHashMap<UUID, PlayerAgent>()

    // Single shared scheduler; agents share threads but run independently
    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "story-player-agent").also { it.isDaemon = true }
        }

    fun startAgent(player: Player) {
        if (!plugin.config.playerAgentEnabled) return
        if (agents.containsKey(player.uniqueId)) return

        val agent = PlayerAgent(plugin, player.uniqueId, scheduler)
        agents[player.uniqueId] = agent
        agent.start()
    }

    fun stopAgent(player: Player) {
        val agent = agents.remove(player.uniqueId) ?: return
        agent.stop()
    }

    fun getAgent(playerUUID: UUID): PlayerAgent? = agents[playerUUID]

    fun getAgent(player: Player): PlayerAgent? = agents[player.uniqueId]

    /** Feed an observation to the agent for this player (no-op if no agent running). */
    fun observe(
        player: Player,
        observation: String,
    ) {
        agents[player.uniqueId]?.observe(observation)
    }

    fun observe(
        playerUUID: UUID,
        observation: String,
    ) {
        agents[playerUUID]?.observe(observation)
    }

    /** All currently running agents. */
    fun allAgents(): Collection<PlayerAgent> = agents.values

    fun shutdown() {
        agents.values.forEach { it.stop() }
        agents.clear()
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        plugin.logger.info("[PlayerAgentManager] Shutdown complete.")
    }
}
