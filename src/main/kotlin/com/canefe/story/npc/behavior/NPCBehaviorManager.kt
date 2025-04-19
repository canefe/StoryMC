package com.canefe.story.npc.behavior

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class NPCBehaviorManager(private val plugin: Story) {

    private val npcBehaviorTasks: MutableMap<String, Int> = ConcurrentHashMap()
    private val npcLastLookTimes: MutableMap<String, Long> = ConcurrentHashMap()
    private val npcLookIntervals: MutableMap<String, Int> = ConcurrentHashMap()
    private val npcIdleHologramTimes: MutableMap<String, Long> = ConcurrentHashMap()

    // Track which NPCs are currently in conversation
    private val npcsInConversation: MutableSet<String> = mutableSetOf()

    init {
        // Start a task to regularly update NPC behaviors for all NPCs
        startGlobalBehaviorTask()
    }

    private fun startGlobalBehaviorTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Update behavior for all spawned NPCs
            CitizensAPI.getNPCRegistry().forEach { npc ->
                if (npc.isSpawned && npc.name != null) {
                    updateNPCBehavior(npc)
                }
            }
        }, 0L, 10L) // Every half-second
    }

    private fun updateNPCBehavior(npc: NPC) {
        val npcName = npc.name ?: return
        val currentTime = System.currentTimeMillis()

        // If NPC is in conversation, let the conversation manager handle behaviors
        if (npcsInConversation.contains(npcName)) return

        // Initialize timers if not set
        if (!npcLastLookTimes.containsKey(npcName)) {
            npcLastLookTimes[npcName] = currentTime
            npcLookIntervals[npcName] = Random.nextInt(1000) + 500 // 2-5 seconds
        }

        // Check if it's time to look at something new
        val lastLookTime = npcLastLookTimes[npcName] ?: currentTime
        val lookInterval = npcLookIntervals[npcName] ?: 3000

        if (currentTime - lastLookTime > lookInterval) {
            // Reset timer and set new interval
            npcLastLookTimes[npcName] = currentTime
            npcLookIntervals[npcName] = Random.nextInt(1000) + 500

            // Get nearby entities to potentially look at
            val nearbyEntities = getNearbyEntities(npc)

            // Decide what to do based on probability
            val decision = Random.nextDouble()

            when {
                // 60% chance to look at someone nearby if entities are present
                nearbyEntities.isNotEmpty() && decision < 0.6 -> {
                    val target = nearbyEntities[Random.nextInt(nearbyEntities.size)]
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        val rot = npc.getOrAddTrait(RotationTrait::class.java)
                        rot.physicalSession.rotateToFace(target)
                    })

                    // Show idle hologram sometimes when looking at entity
                    if (Random.nextDouble() < 0.2) { // 20% chance
                        showIdleHologram(npc)
                    }
                }
                // 25% chance to look in a random direction
                decision < 0.85 -> {
                    val rot = npc.getOrAddTrait(RotationTrait::class.java)
                    // Get current head yaw
                    val currentYaw = net.citizensnpcs.util.NMS.getHeadYaw(npc.entity)

                    // Generate a random angle change within a natural range (-45 to +45 degrees)
                    val yawChange = Random.nextFloat() * 90 - 45 // Range from -45 to +45 degrees

                    // Calculate new yaw based on current + change
                    val newYaw = currentYaw + yawChange

                    // Apply the new rotation, keeping pitch at 0 (looking straight)
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        rot.physicalSession.rotateToHave(newYaw, 0f)
                    })
                }
                // 15% chance to do nothing (keep current position)
            }
        }

        // Handle idle holograms independent of looking behavior
        updateIdleHolograms(npc, currentTime)
    }

    private fun getNearbyEntities(npc: NPC): List<Entity> {
        if (!npc.isSpawned) return emptyList()

        val entities = mutableListOf<Entity>()
        val location = npc.entity.location
        val nearbyEntities = location.world.getNearbyEntities(location, 10.0, 10.0, 10.0)

        for (entity in nearbyEntities) {
            // Only consider players and other NPCs
            if ((entity is Player || entity.hasMetadata("NPC")) && entity != npc.entity) {
                entities.add(entity)
            }
        }

        return entities
    }

    private fun showIdleHologram(npc: NPC) {
        val idleActions = listOf(
            "&7&osighs",
            "&7&oshuffles feet",
            "&7&oglances around",
            "&7&oblinks slowly",
            "&7&oyawns",
            "&7&oclears throat",
            "&7&omumbles something",
            "&7&oscratches head",
            "&7&orolls shoulders",
            "&7&omutters under breath",
            "&7&obreathes deeply",
            "&7&ogroans quietly",
            "&7&ofidgets",
            "&7&osniffs",
            "&7&ostretches neck",
            "&7&olooks up at the sky",
            "&7&otilts head",
            "&7&onarrows eyes",
            "&7&onods slowly",
            "&7&ostares into the distance"
        )

        val randomAction = idleActions[Random.nextInt(idleActions.size)]
        val npcUUID = npc.uniqueId.toString()
        val hologramName = "idle_${npcUUID}"

        // Use your existing hologram system to show the action
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            try {
                val npcPos = npc.entity.location.clone().add(0.0, 2.10, 0.0)

                // Check if the hologram already exists and remove it first
                val existingHologram = eu.decentsoftware.holograms.api.DHAPI.getHologram(hologramName)
                if (existingHologram != null) {
                    eu.decentsoftware.holograms.api.DHAPI.removeHologram(hologramName)
                }

                // Create new hologram
                val hologram = eu.decentsoftware.holograms.api.DHAPI.createHologram(hologramName, npcPos)
                eu.decentsoftware.holograms.api.DHAPI.addHologramLine(hologram, 0, randomAction)

                // Remove after a short delay
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    try {
                        eu.decentsoftware.holograms.api.DHAPI.removeHologram(hologramName)
                    } catch (e: Exception) {
                        // Hologram might already be removed, just ignore
                    }
                }, 40L) // 2 seconds

                // Track when we last showed an idle hologram
                npcIdleHologramTimes[npc.name ?: return] = System.currentTimeMillis()
            } catch (e: Exception) {
                plugin.logger.warning("Error showing idle hologram: ${e.message}")
            }
        }
    }

    private fun updateIdleHolograms(npc: NPC, currentTime: Long) {
        val npcName = npc.name ?: return
        val lastIdleTime = npcIdleHologramTimes[npcName] ?: 0L

        // Show idle holograms occasionally (every 20-40 seconds)
        if (currentTime - lastIdleTime > Random.nextInt(20000) + 20000) {
            if (Random.nextDouble() < 0.5) { // 50% chance to actually show it
                showIdleHologram(npc)
            }
            npcIdleHologramTimes[npcName] = currentTime
        }
    }

    // Called when an NPC enters a conversation
    fun setNPCInConversation(npcName: String, inConversation: Boolean) {
        if (inConversation) {
            npcsInConversation.add(npcName)
        } else {
            npcsInConversation.remove(npcName)
        }
    }

    fun cleanupNPC(npcName: String) {
        npcLastLookTimes.remove(npcName)
        npcLookIntervals.remove(npcName)
        npcIdleHologramTimes.remove(npcName)
        npcsInConversation.remove(npcName)
    }
}