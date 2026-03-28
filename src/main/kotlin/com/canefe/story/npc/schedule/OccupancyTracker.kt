package com.canefe.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.CitizensStoryNPC
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import java.util.concurrent.ConcurrentHashMap

class OccupancyTracker(
    private val plugin: Story,
) {
    private val locationOccupancy = ConcurrentHashMap<String, OccupancyInfo>()

    fun isLocationOccupied(
        location: StoryLocation,
        action: String,
    ): Boolean {
        val locationKey = "${location.name}:$action"
        val occupancy = locationOccupancy[locationKey] ?: return false

        val gracePeriodMs = 10000L
        val isWithinGracePeriod = (System.currentTimeMillis() - occupancy.timestamp) < gracePeriodMs

        if (isWithinGracePeriod) {
            val occupyingNPC =
                CitizensAPI
                    .getNPCRegistry()
                    .firstOrNull {
                        it.name.equals(occupancy.npcName, ignoreCase = true)
                    }?.let { CitizensStoryNPC(it) }
            if (occupyingNPC?.entity == null) {
                locationOccupancy.remove(locationKey)
                return false
            }
            return true
        }

        val occupyingNPC =
            CitizensAPI
                .getNPCRegistry()
                .firstOrNull {
                    it.name.equals(occupancy.npcName, ignoreCase = true)
                }?.let { CitizensStoryNPC(it) }
        if (occupyingNPC?.entity == null) {
            locationOccupancy.remove(locationKey)
            return false
        }

        val npcLocation =
            occupyingNPC.location ?: run {
                locationOccupancy.remove(locationKey)
                return false
            }
        val locationDistance = npcLocation.distanceSquared(location.bukkitLocation ?: return false)
        val tolerance = 25.0

        if (locationDistance > tolerance) {
            locationOccupancy.remove(locationKey)
            return false
        }

        val isStillPerformingAction =
            when (action.lowercase()) {
                "sit" -> occupyingNPC.isSitting
                "sleep" ->
                    occupyingNPC.unwrap(NPC::class.java)?.let { citizensNPC ->
                        citizensNPC.getOrAddTrait(EntityPoseTrait::class.java).pose ==
                            EntityPoseTrait.EntityPose.SLEEPING
                    } ?: false
                else -> true
            }

        if (!isStillPerformingAction) {
            locationOccupancy.remove(locationKey)
            return false
        }

        return true
    }

    fun markLocationOccupied(
        location: StoryLocation,
        npcName: String,
        action: String,
    ) {
        val locationKey = "${location.name}:$action"
        locationOccupancy[locationKey] = OccupancyInfo(npcName, action)

        if (plugin.config.debugMessages) {
            plugin.logger.info("Marked location '${location.name}' as occupied by $npcName performing '$action'")
        }
    }

    fun clearNPCOccupancy(npcName: String) {
        val toRemove = locationOccupancy.entries.filter { it.value.npcName == npcName }
        toRemove.forEach { entry ->
            locationOccupancy.remove(entry.key)
            if (plugin.config.debugMessages) {
                plugin.logger.info("Cleared occupancy for $npcName at ${entry.key}")
            }
        }
    }

    fun clearNPCOccupancyExcept(
        npcName: String,
        exceptLocationKey: String,
    ) {
        val toRemove = locationOccupancy.entries.filter { it.value.npcName == npcName && it.key != exceptLocationKey }
        toRemove.forEach { entry ->
            locationOccupancy.remove(entry.key)
            if (plugin.config.debugMessages) {
                plugin.logger.info("Cleared occupancy for $npcName at ${entry.key}, excepted $exceptLocationKey")
            }
        }
    }

    fun getOccupancy(locationKey: String): OccupancyInfo? = locationOccupancy[locationKey]
}
