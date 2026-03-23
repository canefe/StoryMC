package com.canefe.story.npc.behavior

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.util.NPCUtils
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class NPCBehaviorManager(
    private val plugin: Story,
) {
    // Replace name-based maps with ID-based maps
    private val npcLastLookTimes: MutableMap<Int, Long> = ConcurrentHashMap()
    private val npcLookIntervals: MutableMap<Int, Int> = ConcurrentHashMap()
    private val npcIdleHologramTimes: MutableMap<Int, Long> = ConcurrentHashMap()

    // Add a debug counter to monitor updates
    private var updateCounter = 0

    private val npcsInConversation: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val initializedNPCs: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    init {
        startGlobalBehaviorTask()
    }

    private fun startGlobalBehaviorTask() {
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                try {
                    if (System.getProperty("mockbukkit") == "true") {
                        return@Runnable
                    }

                    // Track update count for debugging
                    updateCounter++

                    // Every 100 updates (~5 seconds), check for NPCs that need initialization
                    if (updateCounter % 100 == 0) {
                        if (plugin.config.debugMessages) {
                            plugin.logger.info("NPC Behavior Manager performing periodic check")
                        }
                        reinitializeAllNPCs()
                    }

                    // Update behavior for NPCs that have players nearby (performance optimization)
                    val nearbyNPCs = getNearbyNPCsToActivePlayers()
                    nearbyNPCs.forEach { storyNpc ->
                        try {
                            if (storyNpc.isSpawned && storyNpc.entity != null) {
                                updateNPCBehavior(storyNpc)
                            }
                        } catch (e: Exception) {
                            plugin.logger.warning("Error updating NPC behavior for NPC ID ${storyNpc.id}: ${e.message}")
                        }
                    }

                    // Handle disguised players
                    for (player in Bukkit.getOnlinePlayers()) {
                        if (plugin.disguiseManager.isDisguisedAsNPC(player)) {
                            showIdleHologram(player)
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("Error in global NPC behavior task: ${e.message}")
                    e.printStackTrace()
                }
            },
            0L,
            10L,
        )
    }

    private fun reinitializeAllNPCs() {
        CitizensAPI.getNPCRegistry().forEach { npc ->
            if (npc.isSpawned && !initializedNPCs.contains(npc.id)) {
                // Initialize tracking data for any NPC that doesn't have it
                initializeNPCTracking(CitizensStoryNPC(npc))
                initializedNPCs.add(npc.id)
            }
        }
    }

    private fun initializeNPCTracking(npc: StoryNPC) {
        val currentTime = System.currentTimeMillis()
        val npcId = npc.id

        if (!npcLastLookTimes.containsKey(npcId)) {
            npcLastLookTimes[npcId] = currentTime
        }

        if (!npcLookIntervals.containsKey(npcId)) {
            npcLookIntervals[npcId] = Random.nextInt(plugin.config.headRotationDelay * 1000) + 1000
        }

        if (!npcIdleHologramTimes.containsKey(npcId)) {
            npcIdleHologramTimes[npcId] = currentTime
        }
    }

    private fun updateNPCBehavior(npc: StoryNPC) {
        val npcId = npc.id
        val currentTime = System.currentTimeMillis()
        val headRotationDelay = plugin.config.headRotationDelay

        // Initialize trackers if not set
        initializeNPCTracking(npc)

        // Check if it's time to look at something new
        val lastLookTime = npcLastLookTimes[npcId] ?: currentTime
        val lookInterval = npcLookIntervals[npcId] ?: 3000

        if (currentTime - lastLookTime > lookInterval) {
            // Reset timer and set new interval
            npcLastLookTimes[npcId] = currentTime
            npcLookIntervals[npcId] = Random.nextInt(headRotationDelay * 1000) + 1000

            // Get nearby entities to potentially look at
            val nearbyEntities = getNearbyEntities(npc)

            // Split entities into conversation participants and others
            val entitiesInConversation =
                nearbyEntities.filter { entity ->
                    (entity is Player && plugin.conversationManager.isPlayerInConversationWith(entity, npc)) ||
                        (
                            CitizensAPI.getNPCRegistry().isNPC(entity) &&
                                plugin.conversationManager.isNPCInConversationWith(
                                    CitizensStoryNPC(CitizensAPI.getNPCRegistry().getNPC(entity)),
                                    npc,
                                )
                        )
                }

            // return if npc.entity is not spawned
            if (!npc.isSpawned) return

            // Decide what to do based on probability
            val decision = Random.nextDouble()

            when {
                // 50% chance to look at someone nearby if entities are present
                nearbyEntities.isNotEmpty() && decision < 0.5 -> {
                    val target =
                        if (entitiesInConversation.isNotEmpty() && Random.nextDouble() < 0.9) {
                            // 90% chance to pick someone from the conversation
                            entitiesInConversation[Random.nextInt(entitiesInConversation.size)]
                        } else {
                            // 10% chance to pick anyone nearby (including those not in conversation)
                            nearbyEntities[Random.nextInt(nearbyEntities.size)]
                        }

                    turnHead(npc, target)

                    // Show idle hologram sometimes when looking at entity
                    if (Random.nextDouble() < 0.2) { // 20% chance
                        showIdleHologram(npc)
                    }
                }
                // 10% chance to look in a random direction
                decision < 0.6 -> {
                    // Check if the NPC is sitting
                    val isSitting = npc.isSitting

                    // Get current head yaw - use entity's yaw
                    val currentYaw = npc.entity?.location?.yaw ?: 0f

                    // Generate a more natural head movement for sitting NPCs
                    val yawChange =
                        if (isSitting) {
                            // More limited range for sitting NPCs to avoid unnatural poses
                            Random.nextFloat() * 60 - 30 // Range from -30 to +30 degrees
                        } else {
                            Random.nextFloat() * 90 - 45 // Range from -45 to +45 degrees
                        }
                    // Calculate new yaw based on current + change
                    val newYaw = currentYaw + yawChange

                    // Apply the new rotation with a slightly elevated pitch for sitting NPCs
                    Bukkit.getScheduler().runTask(
                        plugin,
                        Runnable {
                            if (isSitting) {
                                // Sitting NPCs should look slightly upward (negative pitch)
                                // for a more natural appearance
                                npc.rotateTo(newYaw, -10f)
                            } else {
                                npc.rotateTo(newYaw, 0f)
                            }
                        },
                    )
                }
                // 40% chance to do nothing (keep current position)
            }
        }

        // Handle idle holograms independent of looking behavior
        updateIdleHolograms(npc, currentTime)
    }

    fun turnHead(
        npc: StoryNPC,
        target: Entity,
    ) {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                if (!npc.isSpawned) return@Runnable

                npc.lookAt(target)
            },
        )
    }

    /**
     * Gets all NPCs that are near active players to optimize processing
     * @return List of NPCs that are within range of at least one online player
     */
    private fun getNearbyNPCsToActivePlayers(): List<StoryNPC> {
        val nearbyNPCs = mutableSetOf<StoryNPC>()
        val checkRadius = plugin.config.chatRadius * 2.0 // Use a larger radius for behavior checks

        for (player in Bukkit.getOnlinePlayers()) {
            // Get NPCs near this player and wrap them as StoryNPCs
            val playerNearbyNPCs = NPCUtils.getNearbyNPCs(player, checkRadius)
            nearbyNPCs.addAll(playerNearbyNPCs)
        }

        return nearbyNPCs.toList()
    }

    private fun getNearbyEntities(npc: StoryNPC): List<Entity> {
        if (!npc.isSpawned) return emptyList()

        val entities = mutableListOf<Entity>()
        val location = npc.entity?.location ?: return emptyList()
        val range = plugin.config.chatRadius
        val nearbyEntities = location.world.getNearbyEntities(location, range, range, range)

        for (entity in nearbyEntities) {
            // Only consider players and other NPCs
            if ((entity is Player || entity.hasMetadata("NPC")) && entity != npc.entity) {
                // Check if vanished
                if (entity is Player && entity.hasMetadata("vanished")) continue

                // Check line of sight
                entities.add(entity)
            }
        }

        return entities
    }

    /**
     * Checks if the NPC has a clear line of sight to the target entity
     */
    private fun hasLineOfSight(
        npc: StoryNPC,
        target: Entity,
    ): Boolean {
        if (!npc.isSpawned) return false

        val npcEntity = npc.entity ?: return false
        val npcEyes =
            npcEntity.location.add(
                0.0,
                npcEntity.height * 0.85,
                0.0,
            )
        val targetEyes =
            if (target is Player) {
                target.eyeLocation
            } else {
                target.location.add(0.0, target.height / 2, 0.0)
            }

        // Get direction vector from NPC to target
        val direction = targetEyes.toVector().subtract(npcEyes.toVector())
        val distance = direction.length()

        // Check if any block obstructs the view
        val ray =
            npcEntity.world.rayTraceBlocks(
                npcEyes,
                direction.normalize(),
                distance,
                org.bukkit.FluidCollisionMode.NEVER,
                true,
            )

        // If ray is null or hit location is very close to target, there's line of sight
        return ray == null || (ray.hitPosition?.toLocation(npcEntity.world)?.distance(targetEyes) ?: 0.0) < 0.5
    }

    private fun showIdleHologram(npc: StoryNPC) {
        // Idle holograms removed — now handled by client-side action text
    }

    private fun showIdleHologram(player: Player) {
        // Idle holograms removed — now handled by client-side action text
    }

    private fun updateIdleHolograms(
        npc: StoryNPC,
        currentTime: Long,
    ) {
        val npcId = npc.id
        val lastIdleTime = npcIdleHologramTimes[npcId] ?: 0L

        if (currentTime - lastIdleTime > Random.nextInt(20000) + 20000) {
            if (Random.nextDouble() < 0.5) {
                showIdleHologram(npc)
            }
            npcIdleHologramTimes[npcId] = currentTime
        }
    }

    // Called when an NPC enters a conversation
    fun setNPCInConversation(
        npc: StoryNPC,
        inConversation: Boolean,
    ) {
        val npcId = npc.id
        if (inConversation) {
            npcsInConversation.add(npcId)
        } else {
            npcsInConversation.remove(npcId)
        }
    }

    fun cleanupNPC(npc: StoryNPC) {
        val npcId = npc.id
        npcLastLookTimes.remove(npcId)
        npcLookIntervals.remove(npcId)
        npcIdleHologramTimes.remove(npcId)
        npcsInConversation.remove(npcId)
        initializedNPCs.remove(npcId)
    }
}
