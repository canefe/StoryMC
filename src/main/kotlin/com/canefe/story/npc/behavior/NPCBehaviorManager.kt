package com.canefe.story.npc.behavior

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.util.NMS
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
                    nearbyNPCs.forEach { npc ->
                        try {
                            if (npc.isSpawned && npc.entity != null) {
                                updateNPCBehavior(npc)
                            }
                        } catch (e: Exception) {
                            plugin.logger.warning("Error updating NPC behavior for NPC ID ${npc.id}: ${e.message}")
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
                initializeNPCTracking(npc)
                initializedNPCs.add(npc.id)
            }
        }
    }

    private fun initializeNPCTracking(npc: NPC) {
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

    private fun updateNPCBehavior(npc: NPC) {
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
                                    CitizensAPI.getNPCRegistry().getNPC(entity),
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
                    val rot = npc.getOrAddTrait(RotationTrait::class.java)

                    // Check if the NPC is sitting
                    val isSitting =
                        npc.getOrAddTrait(EntityPoseTrait::class.java).pose == EntityPoseTrait.EntityPose.SITTING

                    // Get current head yaw - handle sitting NPCs differently
                    val currentYaw =
                        if (isSitting) {
                            // For sitting NPCs, use the entity's yaw instead of head yaw
                            npc.entity.location.yaw
                        } else {
                            net.citizensnpcs.util.NMS
                                .getHeadYaw(npc.entity)
                        }

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
                                rot.physicalSession.rotateToHave(newYaw, -10f)
                            } else {
                                rot.physicalSession.rotateToHave(newYaw, 0f)
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
        npc: NPC,
        target: Entity,
    ) {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                val rot = npc.getOrAddTrait(RotationTrait::class.java)

                if (!npc.isSpawned) return@Runnable

                // Get current yaw and calculate target yaw and pitch
                val currentYaw = NMS.getHeadYaw(npc.entity)
                val npcLocation = npc.entity.location
                val targetLocation = target.location

                // Calculate yaw to target (horizontal rotation)
                val dx = targetLocation.x - npcLocation.x
                val dz = targetLocation.z - npcLocation.z
                val targetYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()

                // Calculate pitch to target (vertical rotation)
                val dy = targetLocation.y - npcLocation.y
                val horizontalDistance = Math.sqrt(dx * dx + dz * dz)
                val targetPitch = -Math.toDegrees(Math.atan2(dy, horizontalDistance)).toFloat()

                // Calculate yaw difference (normalized to -180 to 180)
                var yawDiff = (targetYaw - currentYaw) % 360
                if (yawDiff > 180) yawDiff -= 360
                if (yawDiff < -180) yawDiff += 360

                // Limit rotation to 60 degrees max for natural movement
                val maxRotation = 60f
                val limitedYaw =
                    if (Math.abs(yawDiff) > maxRotation) {
                        currentYaw + Math.signum(yawDiff.toDouble()).toFloat() * maxRotation
                    } else {
                        targetYaw
                    }

                // Limit pitch to reasonable values (-90 to 90 degrees, but we'll use smaller range for natural look)
                val maxPitch = 45f
                val limitedPitch = Math.max(-maxPitch, Math.min(maxPitch, targetPitch))

                rot.physicalSession.rotateToHave(limitedYaw, limitedPitch)
            },
        )
    }

    /**
     * Gets all NPCs that are near active players to optimize processing
     * @return List of NPCs that are within range of at least one online player
     */
    private fun getNearbyNPCsToActivePlayers(): List<NPC> {
        val nearbyNPCs = mutableSetOf<NPC>()
        val checkRadius = plugin.config.chatRadius * 2.0 // Use a larger radius for behavior checks

        for (player in Bukkit.getOnlinePlayers()) {
            // Get NPCs near this player
            val playerNearbyNPCs = plugin.npcUtils.getNearbyNPCs(player, checkRadius)
            nearbyNPCs.addAll(playerNearbyNPCs)
        }

        return nearbyNPCs.toList()
    }

    private fun getNearbyEntities(npc: NPC): List<Entity> {
        if (!npc.isSpawned) return emptyList()

        val entities = mutableListOf<Entity>()
        val location = npc.entity.location
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
        npc: NPC,
        target: Entity,
    ): Boolean {
        // Citizens NPCs don't directly support hasLineOfSight
        // We can use raycasting to check this

        if (!npc.isSpawned) return false

        val npcEntity = npc.entity
        val npcEyes =
            npcEntity.location.add(
                0.0,
                net.citizensnpcs.util.NMS
                    .getHeadYaw(npc.entity)
                    .toDouble(),
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

    private fun showIdleHologram(npc: NPC) {
        // if in conversation, do not show idle hologram
        plugin.conversationManager.getConversation(npc)?.let { conversation ->
            if (conversation.lastSpeakingNPC == npc) {
                return
            }
        }

        val idleActions =
            listOf(
                "&7&osighs",
                "&7&oshuffles feet",
                "&7&oglances around",
                "&7&oblinks slowly",
                "&7&oyawns",
                "&7&oclears throat",
                "&7&omumbles",
                "&7&oscratches head",
                "&7&omutters",
                "&7&obreathes deeply",
                "&7&ogroans quietly",
                "&7&ofidgets",
                "&7&osniffs",
                "&7&ostretches neck",
                "&7&otilts head",
                "&7&onarrows eyes",
                "&7&onods slowly",
            )

        val randomAction = idleActions[Random.nextInt(idleActions.size)]
        val npcUUID = npc.uniqueId.toString()
        val hologramName = npcUUID

        // Use your existing hologram system to show the action
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            try {
                val npcPos =
                    npc.entity.location
                        .clone()
                        .add(0.0, 2.10, 0.0)

                // Check if the hologram already exists and remove it first
                val existingHologram =
                    eu.decentsoftware.holograms.api.DHAPI
                        .getHologram(hologramName)
                if (existingHologram != null) {
                    eu.decentsoftware.holograms.api.DHAPI
                        .removeHologram(hologramName)
                }

                // Create new hologram
                val hologram =
                    eu.decentsoftware.holograms.api.DHAPI
                        .createHologram(hologramName, npcPos)
                eu.decentsoftware.holograms.api.DHAPI
                    .addHologramLine(hologram, 0, randomAction)

                // Remove after a short delay
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    Runnable {
                        try {
                            eu.decentsoftware.holograms.api.DHAPI
                                .removeHologram(hologramName)
                        } catch (e: Exception) {
                            // Hologram might already be removed, just ignore
                        }
                    },
                    40L,
                ) // 2 seconds

                // Track when we last showed an idle hologram
                npcIdleHologramTimes[npc.id] = System.currentTimeMillis()
            } catch (e: Exception) {
                plugin.logger.warning("Error showing idle hologram: ${e.message}")
            }
        }
    }

    private fun showIdleHologram(player: Player) {
        // if in conversation, do not show idle hologram
        val impersonatedNPC = plugin.disguiseManager.getImitatedNPC(player)

        if (impersonatedNPC != null && plugin.conversationManager.isInConversation(impersonatedNPC)) {
            return
        }

        val idleActions =
            listOf(
                "&7&osighs",
                "&7&oshuffles feet",
                "&7&oglances around",
                "&7&oblinks slowly",
                "&7&oyawns",
                "&7&oclears throat",
                "&7&omumbles",
                "&7&oscratches head",
                "&7&omutters",
                "&7&obreathes deeply",
                "&7&ogroans quietly",
                "&7&ofidgets",
                "&7&osniffs",
                "&7&ostretches neck",
                "&7&otilts head",
                "&7&onarrows eyes",
                "&7&onods slowly",
            )

        val randomAction = idleActions[Random.nextInt(idleActions.size)]

        val hologramName = player.uniqueId.toString()

        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            try {
                val playerPos =
                    player.location
                        .clone()
                        .add(0.0, 2.10, 0.0)

                val existingHologram =
                    eu.decentsoftware.holograms.api.DHAPI
                        .getHologram(hologramName)
                if (existingHologram != null) {
                    eu.decentsoftware.holograms.api.DHAPI
                        .removeHologram(hologramName)
                }

                val hologram =
                    eu.decentsoftware.holograms.api.DHAPI
                        .createHologram(hologramName, playerPos)
                eu.decentsoftware.holograms.api.DHAPI
                    .addHologramLine(hologram, 0, randomAction)

                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    Runnable {
                        try {
                            eu.decentsoftware.holograms.api.DHAPI
                                .removeHologram(hologramName)
                        } catch (e: Exception) {
                            // Hologram might already be removed, just ignore
                        }
                    },
                    40L,
                ) // 2 seconds
            } catch (e: Exception) {
                e.printStackTrace()
                plugin.logger.warning("Error showing idle hologram: ${e.message}")
            }
        }
    }

    private fun updateIdleHolograms(
        npc: NPC,
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
        npc: NPC,
        inConversation: Boolean,
    ) {
        val npcId = npc.id
        if (inConversation) {
            npcsInConversation.add(npcId)
        } else {
            npcsInConversation.remove(npcId)
        }
    }

    fun cleanupNPC(npc: NPC) {
        val npcId = npc.id
        npcLastLookTimes.remove(npcId)
        npcLookIntervals.remove(npcId)
        npcIdleHologramTimes.remove(npcId)
        npcsInConversation.remove(npcId)
        initializedNPCs.remove(npcId)
    }
}
