package com.canefe.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.duty.DutyLibrary
import com.canefe.story.npc.duty.DutyLoopRunner
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

class ScheduleExecutor(
    private val plugin: Story,
    private val movementService: MovementService,
    private val occupancyTracker: OccupancyTracker,
    private val dutyLoopRunner: DutyLoopRunner,
    private val dutyLibrary: DutyLibrary,
) {
    private val dialogueCooldowns = ConcurrentHashMap<String, Long>()

    fun executeScheduleEntry(
        npcName: String,
        entry: ScheduleEntry,
        schedules: Map<String, NPCSchedule>,
    ) {
        val npc =
            CitizensAPI
                .getNPCRegistry()
                .firstOrNull {
                    it.name.equals(
                        npcName,
                        ignoreCase = true,
                    )
                }?.let { CitizensStoryNPC(it) }
                ?: return
        val npcEntity = npc.entity ?: return

        if (npc.isFollowing) {
            plugin.logger.info("${npc.name} is following someone, skipping schedule entry.")
            return
        }

        fun selectRandomDialogue(): String? = if (entry.dialogue.isNullOrEmpty()) null else entry.dialogue.random()

        val locationName = entry.locationName
        if (!locationName.isNullOrEmpty()) {
            val location = plugin.locationManager.getLocation(locationName)
            if (location != null) {
                val isInConversation = plugin.conversationManager.isInConversation(npc)

                val shouldMove =
                    location.bukkitLocation?.let { mainLocation ->
                        val npcLocation = npcEntity.location
                        val distanceToMain = npcLocation.distanceSquared(mainLocation)
                        val tolerance =
                            plugin.config.scheduleDestinationTolerance * plugin.config.scheduleDestinationTolerance

                        if (distanceToMain <= tolerance) {
                            false
                        } else {
                            val dutyData = dutyLibrary.loadLocationDutyData(location)
                            val isAtWorkstation =
                                dutyData.workstations.values.any { workstation ->
                                    val workstationLocation =
                                        location.bukkitLocation?.world?.let { world ->
                                            Location(world, workstation.x, workstation.y, workstation.z)
                                        }
                                    workstationLocation?.let { wsLoc ->
                                        npcLocation.distanceSquared(wsLoc) <= tolerance
                                    } ?: false
                                }
                            !isAtWorkstation
                        }
                    } ?: false

                if (shouldMove) {
                    dutyLoopRunner.stop(npc)

                    if ((entry.action == "work" || entry.duty != null) && isInConversation) {
                        val goodbyeContext =
                            mutableListOf(
                                "\"You have a work to do at ${location.name}. Tell the people in the conversation that you are leaving.\"",
                            )
                        npc.stopFollowing()
                        plugin.conversationManager.endConversationWithGoodbye(npc, goodbyeContext)
                    }

                    val moveCallback =
                        Runnable {
                            handleDestinationArrival(npc, entry, location, dutyLibrary, dutyLoopRunner)
                            handleDialogueAfterArrival(npc, npcName, entry, ::selectRandomDialogue)
                        }

                    val baseLocation = location.bukkitLocation!!
                    val requiresPrecisePositioning = location.randomPathingAction?.lowercase() in listOf("sit", "sleep")

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
                    if (safeLocation != null && entry.random) {
                        movementService.moveNPCToLocation(npc, safeLocation, moveCallback)
                    } else {
                        movementService.moveNPCToLocation(npc, baseLocation, moveCallback)
                    }
                } else {
                    handleDestinationArrival(npc, entry, location, dutyLibrary, dutyLoopRunner)
                    handleDialogueAfterArrival(npc, npcName, entry, ::selectRandomDialogue)
                }
            }
        } else {
            if (plugin.conversationManager.isInConversation(npc)) return
            if (isInDialogueCooldown(npc.name)) return

            val randomDialogue = selectRandomDialogue()
            if (randomDialogue != null) {
                updateDialogueTime(npcName)
                val randomDelay = (1..6).random() * 20L
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    Runnable {
                        plugin.npcMessageService.broadcastNPCMessage(randomDialogue, npc, shouldBroadcast = false)
                    },
                    randomDelay,
                )
            }
        }
    }

    private fun handleDialogueAfterArrival(
        npc: StoryNPC,
        npcName: String,
        entry: ScheduleEntry,
        selectRandomDialogue: () -> String?,
    ) {
        if (entry.dialogue.isNullOrEmpty()) return
        if (plugin.conversationManager.isInConversation(npc)) return
        if (isInDialogueCooldown(npc.name)) return

        val randomDialogue = selectRandomDialogue()
        updateDialogueTime(npcName)
        val randomDelay = (1..6).random() * 20L
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (randomDialogue != null) {
                    plugin.npcMessageService.broadcastNPCMessage(randomDialogue, npc, shouldBroadcast = false)
                }
            },
            randomDelay,
        )
    }

    private fun handleDestinationArrival(
        npc: StoryNPC,
        entry: ScheduleEntry,
        location: StoryLocation,
        dutyLibrary: DutyLibrary,
        dutyLoopRunner: DutyLoopRunner,
    ) {
        if (!entry.random) {
            npc.teleport(location.bukkitLocation!!)
        }

        val dutyToStart =
            when {
                entry.duty != null -> entry.duty
                entry.action == "work" -> dutyLibrary.getDefaultDuty(location)
                else -> null
            }

        if (dutyToStart != null) {
            val dutyScript = dutyLibrary.getDutyScript(location, dutyToStart)
            if (dutyScript != null) {
                dutyLoopRunner.start(npc, dutyScript, location)
            } else {
                plugin.logger.warning("Duty script '$dutyToStart' not found for location ${location.name}")
                if (entry.action != null) executeAction(npc, entry.action)
            }
        } else {
            dutyLoopRunner.stop(npc)
            if (entry.action != null) executeAction(npc, entry.action)
        }
    }

    fun executeAction(
        npc: StoryNPC,
        action: String,
        location: StoryLocation? = null,
    ) {
        location?.let { targetLocation ->
            val targetLocationKey = "${targetLocation.name}:$action"
            occupancyTracker.clearNPCOccupancyExcept(npc.name, targetLocationKey)
        } ?: occupancyTracker.clearNPCOccupancy(npc.name)

        when (action.lowercase()) {
            "sit" -> {
                if (!npc.isSitting) {
                    npc.sit(npc.entity?.location)
                    location?.let { loc ->
                        val locationKey = "${loc.name}:$action"
                        val currentOccupancy = occupancyTracker.getOccupancy(locationKey)
                        if (currentOccupancy == null || currentOccupancy.npcName != npc.name) {
                            occupancyTracker.markLocationOccupied(loc, npc.name, action)
                        }
                    }
                }
            }
            "work" -> npc.stand()
            "sleep" -> {
                npc.unwrap(NPC::class.java)?.let { citizensNPC ->
                    citizensNPC.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.SLEEPING
                }
                location?.let { loc ->
                    val locationKey = "${loc.name}:$action"
                    val currentOccupancy = occupancyTracker.getOccupancy(locationKey)
                    if (currentOccupancy == null || currentOccupancy.npcName != npc.name) {
                        occupancyTracker.markLocationOccupied(loc, npc.name, action)
                    }
                }
            }
            "idle" -> npc.stand()
            else -> plugin.logger.warning("Unknown action: $action for NPC: ${npc.name}")
        }
    }

    // Dialogue cooldown management

    fun isInDialogueCooldown(npcName: String): Boolean {
        val lastDialogueTime = dialogueCooldowns[npcName.lowercase()] ?: return false
        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L
        return System.currentTimeMillis() - lastDialogueTime < cooldownMs
    }

    private fun updateDialogueTime(npcName: String) {
        dialogueCooldowns[npcName.lowercase()] = System.currentTimeMillis()
    }

    fun getDialogueCooldownRemaining(npcName: String): Int {
        val lastDialogueTime = dialogueCooldowns[npcName.lowercase()] ?: return 0
        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L
        val remainingMs = cooldownMs - (System.currentTimeMillis() - lastDialogueTime)
        return if (remainingMs > 0) (remainingMs / 1000).toInt() else 0
    }

    fun clearDialogueCooldown(npcName: String) {
        dialogueCooldowns.remove(npcName.lowercase())
    }

    fun clearAllDialogueCooldowns() {
        dialogueCooldowns.clear()
    }

    fun cleanupExpiredDialogueCooldowns() {
        val currentTime = System.currentTimeMillis()
        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L
        val expired = dialogueCooldowns.entries.filter { currentTime - it.value > cooldownMs * 2 }
        expired.forEach { dialogueCooldowns.remove(it.key) }
    }
}
