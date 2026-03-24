package com.canefe.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

class RandomPathingService(
    private val plugin: Story,
    private val movementService: MovementService,
    private val occupancyTracker: OccupancyTracker,
    private val actionExecutor: (StoryNPC, String, com.canefe.story.location.data.StoryLocation?) -> Unit,
) {
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun processRandomPathing(
        nearbyNPCs: List<StoryNPC>,
        hour: Int,
        schedules: Map<String, NPCSchedule>,
    ) {
        val debugMessages = plugin.config.debugMessages
        cleanupExpiredCooldowns()

        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
        val now = System.currentTimeMillis()

        val candidateNPCs =
            nearbyNPCs.filter { npc ->
                if (npc.location == null) return@filter false
                if (plugin.npcManager.isNPCDisabled(npc)) return@filter false
                if (npc.isFollowing) return@filter false
                if (plugin.npcDataManager.getNPCData(npc)?.randomPathing == false) return@filter false

                val lastMoved = cooldowns[npc.name.lowercase()]
                if (lastMoved != null && now - lastMoved < cooldownMs) return@filter false

                val schedule = schedules[npc.name.lowercase()]
                val hasScheduledLocation = schedule?.getEntryForTime(hour)?.locationName?.isNotEmpty() == true
                !hasScheduledLocation
            }

        if (debugMessages) {
            plugin.logger.info(
                "Random pathing: ${candidateNPCs.size} candidates from ${nearbyNPCs.size} nearby NPCs",
            )
        }

        if (candidateNPCs.isEmpty()) return

        val shuffled = candidateNPCs.shuffled()
        val staggerTicks = 4L

        for ((index, npc) in shuffled.withIndex()) {
            val delay = index * staggerTicks

            if (delay == 0L) {
                moveNPCToRandomSublocation(npc)
                cooldowns[npc.name.lowercase()] = now
            } else {
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    Runnable {
                        moveNPCToRandomSublocation(npc)
                        cooldowns[npc.name.lowercase()] = System.currentTimeMillis()
                    },
                    delay,
                )
            }

            if (debugMessages) {
                plugin.logger.info("Queued ${npc.name} for random pathing (delay: $delay ticks)")
            }
        }
    }

    private fun moveNPCToRandomSublocation(npc: StoryNPC) {
        val debugMessages = plugin.config.debugMessages
        val currentLocation = npc.location ?: return

        if (plugin.conversationManager.isInConversation(npc)) {
            if (debugMessages) plugin.logger.info("NPC ${npc.name} is in conversation, skipping random movement.")
            return
        }

        if (npc.isFollowing) {
            if (debugMessages) plugin.logger.info("NPC ${npc.name} is following someone, skipping random movement.")
            return
        }

        val currentStoryLocation = plugin.locationManager.getLocationByPosition2D(currentLocation, 200.0)
        if (currentStoryLocation == null) {
            if (debugMessages) plugin.logger.info("NPC ${npc.name} is not in a valid story location, skipping.")
            return
        }

        val allSublocations =
            when {
                currentStoryLocation.hasParent() -> {
                    var tempLocation = currentStoryLocation
                    while (tempLocation?.hasParent() == true) {
                        tempLocation = plugin.locationManager.getLocation(tempLocation.parentLocationName!!)
                    }
                    tempLocation?.let { plugin.locationManager.getSublocations(it.name) } ?: emptyList()
                }
                else -> plugin.locationManager.getSublocations(currentStoryLocation.name)
            }

        var eligibleLocations =
            allSublocations
                .filter {
                    it.bukkitLocation != null &&
                        (it.allowedNPCs.isEmpty() || it.allowedNPCs.contains(npc.name))
                }.filter { it != currentStoryLocation }

        if (eligibleLocations.isEmpty()) return

        val randomSublocation =
            synchronized(occupancyTracker) {
                val currentlyEligible =
                    eligibleLocations.filter { location ->
                        val action = location.randomPathingAction
                        if (action != null && (action.lowercase() == "sit" || action.lowercase() == "sleep")) {
                            !occupancyTracker.isLocationOccupied(location, action)
                        } else {
                            true
                        }
                    }

                if (currentlyEligible.isEmpty()) {
                    null
                } else {
                    val seed = npc.uniqueId.hashCode()
                    val randomGenerator = Random(seed + System.currentTimeMillis() / 30000)
                    val selected = currentlyEligible[randomGenerator.nextInt(currentlyEligible.size)]

                    val locationAction = selected.randomPathingAction
                    if (locationAction != null &&
                        (locationAction.lowercase() == "sit" || locationAction.lowercase() == "sleep")
                    ) {
                        occupancyTracker.markLocationOccupied(selected, npc.name, locationAction)
                    }

                    selected
                }
            }

        if (randomSublocation == null) {
            plugin.logger.warning("No eligible sublocations found for ${npc.name}")
            return
        }

        val baseLocation = randomSublocation.bukkitLocation!!
        val requiresPrecisePositioning = randomSublocation.randomPathingAction?.lowercase() in listOf("sit", "sleep")

        val targetLocation =
            if (requiresPrecisePositioning) {
                baseLocation.clone()
            } else {
                val offset = plugin.config.randomLocationOffset
                val random =
                    java.util.concurrent.ThreadLocalRandom
                        .current()
                Location(
                    baseLocation.world,
                    baseLocation.x + random.nextDouble(-offset, offset),
                    baseLocation.y,
                    baseLocation.z + random.nextDouble(-offset, offset),
                    random.nextFloat() * 360f,
                    baseLocation.pitch,
                )
            }

        val safeLocation = movementService.findNearbyGround(targetLocation, maxBlocksCheck = 3)
        if (safeLocation != null) {
            movementService.moveNPCToLocation(npc, safeLocation)
        } else {
            movementService.moveNPCToLocation(npc, baseLocation)
        }

        if (debugMessages) {
            plugin.logger.info("Moving ${npc.name} to random sublocation: ${randomSublocation.name}")
        }

        randomSublocation.randomPathingAction?.let { action ->
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    val npcLocation = npc.entity?.location ?: return@Runnable
                    val distanceToTarget = npcLocation.distanceSquared(randomSublocation.bukkitLocation!!)
                    if (distanceToTarget > 4.0) return@Runnable
                    npc.teleport(randomSublocation.bukkitLocation!!)
                    actionExecutor(npc, action, randomSublocation)
                },
                20L,
            )
        }
    }

    fun getCooldownRemaining(npcName: String): Int {
        val lastProcessedTime = cooldowns[npcName.lowercase()] ?: return 0
        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
        val remainingMs = cooldownMs - (System.currentTimeMillis() - lastProcessedTime)
        return if (remainingMs > 0) (remainingMs / 1000).toInt() else 0
    }

    fun clearCooldown(npcName: String) {
        cooldowns.remove(npcName.lowercase())
    }

    fun clearAllCooldowns() {
        cooldowns.clear()
    }

    private fun cleanupExpiredCooldowns() {
        val currentTime = System.currentTimeMillis()
        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
        val expired = cooldowns.entries.filter { currentTime - it.value > cooldownMs * 2 }
        expired.forEach { cooldowns.remove(it.key) }
    }
}
