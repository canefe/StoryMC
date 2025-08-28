package com.canefe.story.npc.duty

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.scheduler.BukkitTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages and executes duty loops for NPCs
 */
class DutyLoopRunner private constructor(private val plugin: Story) {

    // Active duty states for NPCs
    private val activeDuties = ConcurrentHashMap<UUID, DutyExecutionContext>()

    // Task for running duty loops
    private var dutyTask: BukkitTask? = null

    data class DutyExecutionContext(
        val npc: NPC,
        val location: StoryLocation,
        val state: DutyState,
        var lastTickTime: Long = System.currentTimeMillis()
    )

    init {
        startDutyTicker()
    }

    /**
     * Start a duty loop for an NPC
     */
    fun start(npc: NPC, dutyScript: DutyScript, location: StoryLocation) {
        if (plugin.config.debugMessages) {
            plugin.logger.info("Starting duty '${dutyScript.name}' for ${npc.name} at ${location.name}")
        }

        val context = DutyExecutionContext(
            npc = npc,
            location = location,
            state = DutyState(dutyScript)
        )

        activeDuties[npc.uniqueId] = context

        // Execute first step immediately
        performStep(context, context.state.getCurrentStep())
    }

    /**
     * Stop duty loop for an NPC
     */
    fun stop(npc: NPC) {
        val context = activeDuties.remove(npc.uniqueId)
        if (context != null && plugin.config.debugMessages) {
            plugin.logger.info("Stopped duty for ${npc.name}")
        }
    }

    /**
     * Check if an NPC is on duty
     */
    fun isOnDuty(npc: NPC): Boolean {
        return activeDuties.containsKey(npc.uniqueId)
    }

    /**
     * Get current duty context for an NPC
     */
    fun getDutyContext(npc: NPC): DutyExecutionContext? {
        return activeDuties[npc.uniqueId]
    }

    /**
     * Start the duty ticker task
     */
    private fun startDutyTicker() {
        // Stop existing task if any
        dutyTask?.cancel()

        // Run every 2 seconds to check duty progress
        dutyTask = 		Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
            tickDuties()
        }, 40L, 40L) // 2 seconds = 40 ticks
    }

    /**
     * Tick all active duties
     */
    private fun tickDuties() {
        val now = System.currentTimeMillis()

        // Process each active duty
        activeDuties.values.forEach { context ->
            try {
                tickDuty(context, now)
            } catch (e: Exception) {
                plugin.logger.warning("Error ticking duty for ${context.npc.name}: ${e.message}")
                e.printStackTrace()
            }
        }

        // Clean up duties for NPCs that are no longer valid
        activeDuties.entries.removeIf { (_, context) ->
            !context.npc.isSpawned
        }
    }

    /**
     * Tick a single duty
     */
    private fun tickDuty(context: DutyExecutionContext, now: Long) {
        val npc = context.npc

        // Skip if NPC is not spawned
        if (!npc.isSpawned) return

        // Skip if NPC is in conversation
        if (plugin.conversationManager.isInConversation(npc)) return

        // Skip if NPC is disabled
        if (plugin.npcManager.isNPCDisabled(npc)) return

        val state = context.state
        val currentStep = state.getCurrentStep()

        // Check if current step duration has elapsed
        if (now - state.stepStartedAt >= currentStep.durationMs) {
            // Advance to next step
            state.advanceToNextStep()
            val nextStep = state.getCurrentStep()

            if (plugin.config.debugMessages) {
                plugin.logger.info("${npc.name} advancing to duty step: ${nextStep.action}")
            }

            // Perform the new step
            performStep(context, nextStep)
        }
    }

    /**
     * Perform a duty step
     */
    private fun performStep(context: DutyExecutionContext, step: DutyStep) {
        val npc = context.npc
        val location = context.location

        // Check if step has distance requirement
        if (step.ifNear != null) {
            val nearbyPlayers = plugin.getNearbyPlayers(npc, step.ifNear, ignoreY = true)
            if (nearbyPlayers.isEmpty()) {
                // Skip this step if no players nearby
                return
            }
        }

        try {
            when (step.action) {
                "stand_at" -> {
                    val workstationName = step.args["workstation"]
                    if (workstationName != null) {
                        moveToWorkstation(npc, location, workstationName)
                    }
                }

                "move_to" -> {
                    val workstationName = step.args["workstation"]
                    if (workstationName != null) {
                        moveToWorkstation(npc, location, workstationName)
                    }
                }

                "emote" -> {
                    val emoteId = step.args["id"]
                    if (emoteId != null) {
                        performEmote(npc, emoteId)
                    }
                }

                "bark" -> {
                    val poolName = step.args["pool"]
                    if (poolName != null) {
                        val barkService = BarkService.getInstance(plugin)
                        val cooldown = if (step.cooldown > 0) step.cooldown else 30
                        barkService.trySpeak(npc, poolName, location, cooldown)
                    }
                }

                "face_nearest_player" -> {
                    val range = step.ifNear ?: 5.0
                    faceNearestPlayer(npc, range)
                }

                else -> {
                    if (plugin.config.debugMessages) {
                        plugin.logger.warning("Unknown duty action: ${step.action}")
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error performing duty step '${step.action}' for ${npc.name}: ${e.message}")
        }
    }

    /**
     * Move NPC to a workstation
     */
    private fun moveToWorkstation(npc: NPC, location: StoryLocation, workstationName: String) {
        val dutyLibrary = DutyLibrary.getInstance(plugin)
        val workstationLocation = dutyLibrary.getWorkstationLocation(location, workstationName)

        if (workstationLocation == null) {
            plugin.logger.warning("Workstation '$workstationName' not found at ${location.name}")
            return
        }

        // Add small random offset to prevent clustering
        val random = java.util.concurrent.ThreadLocalRandom.current()
        val offsetX = random.nextDouble(-0.5, 0.5)
        val offsetZ = random.nextDouble(-0.5, 0.5)

        val targetLocation = workstationLocation.clone().add(offsetX, 0.0, offsetZ)

        // Check if NPC is already close to the workstation
        if (npc.entity.location.distance(targetLocation) <= 1.5) {
            return // Already at workstation
        }

        // Use the NPCManager's walkToLocation method
        plugin.npcManager.walkToLocation(
            npc = npc,
            targetLocation = targetLocation,
            distanceMargin = 1.0,
            speedModifier = 1.0f,
            timeout = 30,
            onArrival = null,
            onFailed = null
        )
    }

    /**
     * Perform an emote (placeholder for now)
     */
    private fun performEmote(npc: NPC, emoteId: String) {
        // For now, just log the emote
        // This can be expanded to use actual emote systems
        if (plugin.config.debugMessages) {
            plugin.logger.info("${npc.name} performs emote: $emoteId")
        }

        // You can integrate with your existing emote service here
        // plugin.emoteService.play(npc, emoteId)
    }

    /**
     * Make NPC face the nearest player
     */
    private fun faceNearestPlayer(npc: NPC, range: Double) {
        val nearbyPlayers = plugin.getNearbyPlayers(npc, range, ignoreY = true)

        if (nearbyPlayers.isNotEmpty()) {
            val nearestPlayer = nearbyPlayers.minByOrNull {
                it.location.distance(npc.entity.location)
            }

            if (nearestPlayer != null) {
                // Make NPC face the player
                val npcLocation = npc.entity.location
                val direction = nearestPlayer.location.clone().subtract(npcLocation).toVector()
                npcLocation.direction = direction
                npc.entity.teleport(npcLocation)
            }
        }
    }

    /**
     * Get active duty count for debugging
     */
    fun getActiveDutyCount(): Int = activeDuties.size

    /**
     * Get all active duties (for debugging)
     */
    fun getActiveDuties(): Map<UUID, DutyExecutionContext> = activeDuties.toMap()

    /**
     * Shutdown the duty loop runner
     */
    fun shutdown() {
        dutyTask?.cancel()
        activeDuties.clear()
    }

    companion object {
        private var instance: DutyLoopRunner? = null

        fun getInstance(plugin: Story): DutyLoopRunner {
            if (instance == null) {
                instance = DutyLoopRunner(plugin)
            }
            return instance!!
        }
    }
}
