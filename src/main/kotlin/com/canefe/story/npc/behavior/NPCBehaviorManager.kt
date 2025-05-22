package com.canefe.story.npc.behavior

import com.canefe.story.Story
import com.canefe.story.util.PluginUtils
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class NPCBehaviorManager(
	private val plugin: Story,
) {
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
		Bukkit.getScheduler().runTaskTimer(
			plugin,
			Runnable {
				// Update behavior for all spawned NPCs
				CitizensAPI.getNPCRegistry().forEach { npc ->
					if (npc.isSpawned && npc.name != null) {
						updateNPCBehavior(npc)
					}
				}
				// We also do the same for Disguised Players
				for (player in Bukkit.getOnlinePlayers()) {
					if (plugin.disguiseManager.isDisguisedAsNPC(player)) {
						showIdleHologram(player)
					}
				}
			},
			0L,
			10L,
		) // Every half-second
	}

	private fun updateNPCBehavior(npc: NPC) {
		val npcName = npc.name ?: return
		val currentTime = System.currentTimeMillis()
		val headRotationDelay = plugin.config.headRotationDelay

		// If NPC is in conversation, let the conversation manager handle behaviors
		if (npcsInConversation.contains(npcName)) return

		// Initialize timers if not set
		if (!npcLastLookTimes.containsKey(npcName)) {
			npcLastLookTimes[npcName] = currentTime
			npcLookIntervals[npcName] = Random.nextInt(headRotationDelay * 1000) + 1000 // 2-5 seconds
		}

		// Check if it's time to look at something new
		val lastLookTime = npcLastLookTimes[npcName] ?: currentTime
		val lookInterval = npcLookIntervals[npcName] ?: 3000

		if (currentTime - lastLookTime > lookInterval) {
			// Reset timer and set new interval
			npcLastLookTimes[npcName] = currentTime
			npcLookIntervals[npcName] = Random.nextInt(headRotationDelay * 1000) + 1000

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

					Bukkit.getScheduler().runTask(
						plugin,
						Runnable {
							val rot = npc.getOrAddTrait(RotationTrait::class.java)

							if (!npc.isSpawned) return@Runnable

							// Get current yaw and calculate target yaw
							val currentYaw =
								net.citizensnpcs.util.NMS
									.getHeadYaw(npc.entity)
							val targetLocation = target.location

							// Calculate yaw to target
							val dx = targetLocation.x - npc.entity.location.x
							val dz = targetLocation.z - npc.entity.location.z
							val targetYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()

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

							rot.physicalSession.rotateToHave(limitedYaw, 0f)
						},
					)

					// Show idle hologram sometimes when looking at entity
					if (Random.nextDouble() < 0.2) { // 20% chance
						showIdleHologram(npc)
					}
				}
				// 10% chance to look in a random direction
				decision < 0.6 -> {
					val rot = npc.getOrAddTrait(RotationTrait::class.java)

					// Check if the NPC is sitting
					val isSitting = npc.getOrAddTrait(EntityPoseTrait::class.java).pose == EntityPoseTrait.EntityPose.SITTING

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
				npcIdleHologramTimes[npc.name ?: return] = System.currentTimeMillis()
			} catch (e: Exception) {
				plugin.logger.warning("Error showing idle hologram: ${e.message}")
			}
		}
	}

	private fun showIdleHologram(player: Player) {
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
	fun setNPCInConversation(
		npcName: String,
		inConversation: Boolean,
	) {
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
