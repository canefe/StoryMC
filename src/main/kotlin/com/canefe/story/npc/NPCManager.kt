package com.canefe.story.player

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class NPCManager private constructor(private val plugin: Story) : Listener {
	// NPC cooldowns mapping (NPC UUID to timestamp)
	private val npcCooldowns = HashMap<UUID, Long>()

	// Disabled NPCs set
	private val disabledNPCs = HashSet<Int>()

	companion object {
		private var instance: NPCManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): NPCManager {
			if (instance == null) {
				instance = NPCManager(plugin)
				Bukkit.getPluginManager().registerEvents(instance!!, plugin)
			}
			return instance!!
		}
	}

	init {
		loadData()
	}

	/**
	 * Check if an NPC is on cooldown
	 */
	fun isNPCOnCooldown(npc: NPC): Boolean {
		val uuid = npc.uniqueId
		val lastInteraction = npcCooldowns[uuid] ?: return false
		val currentTime = System.currentTimeMillis()

		// Default cooldown of 10 seconds
		return currentTime - lastInteraction < TimeUnit.SECONDS.toMillis(10)
	}

	/**
	 * Set cooldown for an NPC
	 */
	fun setNPCCooldown(npc: NPC) {
		npcCooldowns[npc.uniqueId] = System.currentTimeMillis()
	}

	/**
	 * Get remaining cooldown time in seconds
	 */
	fun getRemainingCooldown(npc: NPC): Int {
		val uuid = npc.uniqueId
		val lastInteraction = npcCooldowns[uuid] ?: return 0
		val currentTime = System.currentTimeMillis()
		val remainingMillis = TimeUnit.SECONDS.toMillis(10) - (currentTime - lastInteraction)

		return if (remainingMillis <= 0) 0 else (remainingMillis / 1000).toInt()
	}

	/**
	 * Check if NPC is disabled
	 */
	fun isNPCDisabled(npc: NPC): Boolean {
		return disabledNPCs.contains(npc.id)
	}

	/**
	 * Toggle NPC enabled/disabled status
	 */
	fun toggleNPC(
		npc: NPC,
		executor: CommandSender,
	) {
		if (disabledNPCs.contains(npc.id)) {
			disabledNPCs.remove(npc.id)
			executor.sendSuccess("Enabled NPC: ${npc.name}")
		} else {
			disabledNPCs.add(npc.id)
			executor.sendError("Disabled NPC: ${npc.name}")
		}
		saveData()
	}

	/**
	 * List all disabled NPCs
	 */
	fun listDisabledNPCs(player: Player) {
		if (disabledNPCs.isEmpty()) {
			player.sendInfo("Currently, there are no disabled NPCs.")
			return
		}

		player.sendInfo("Disabled NPCs:")
		disabledNPCs.forEach { id ->
			val npc = CitizensAPI.getNPCRegistry().getById(id)
			val name = npc?.name ?: "Unknown NPC"
			player.sendInfo(" - <gold>$name</gold> (ID: $id)")
		}
	}

	/**
	 * Makes an NPC walk to a player and speak a message
	 */
	fun eventGoToPlayerAndTalk(
		npc: NPC,
		player: Player,
		message: String,
		onCompleteAction: Runnable?,
	) {
		if (!npc.isSpawned || isNPCDisabled(npc)) return

		val navigator = npc.navigator
		navigator.setTarget(player, false)

		var taskId = -1
		taskId =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				if (!npc.isSpawned || !player.isOnline) {
					Bukkit.getScheduler().cancelTask(taskId)
					return@scheduleSyncRepeatingTask
				}

				if (isNearTarget(npc, player.location)) {
					navigator.cancelNavigation()
					Bukkit.getScheduler().cancelTask(taskId)

					// Have the NPC face the player
					makeNPCFaceLocation(npc, player.location)

					// create a list consisting of npc
					val npcs = ArrayList<NPC>()
					npcs.add(npc)

					val conversation = plugin.conversationManager.startConversation(player, npcs)

					// Send the message
					plugin.npcMessageService.broadcastNPCMessage(message, npc)

					// Add NPC message
					conversation.addNPCMessage(npc, message)

					// Set cooldown for this NPC
					setNPCCooldown(npc)

					onCompleteAction?.run()
				}
			}, 10L, 10L)
	}

	// Other methods (walkToNPC, makeNPCFaceLocation, isNearTarget, etc.) remain the same

	/**
	 * Makes an NPC walk to another NPC and initiate conversation
	 */
	fun walkToNPC(
		initiator: NPC,
		target: NPC,
		firstMessage: String,
	) {
		if (!initiator.isSpawned || !target.isSpawned || isNPCDisabled(initiator) || isNPCDisabled(target)) return

		val navigator = initiator.navigator
		navigator.setTarget(target.entity, false)

		var taskId = -1
		taskId =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				if (!initiator.isSpawned || !target.isSpawned) {
					Bukkit.getScheduler().cancelTask(taskId)
					return@scheduleSyncRepeatingTask
				}

				if (isNearTarget(initiator, target.entity.location)) {
					Bukkit.getScheduler().cancelTask(taskId)

					// Have the initiator face the target
					makeNPCFaceLocation(initiator, target.entity.location)

					// Send the message
					plugin.npcMessageService.broadcastNPCMessage(firstMessage, initiator)

					// Start a conversation between the NPCs
					val npcs = ArrayList<NPC>()
					npcs.add(initiator)
					npcs.add(target)
					plugin.conversationManager.startRadiantConversation(npcs).thenAccept { conversation ->
						// Add the first message to the conversation
						conversation.addNPCMessage(initiator, firstMessage)
						// Get A list of string only from conversation.history
						val history = conversation.history.map { it.content }
						plugin.npcResponseService.generateNPCResponse(target, history).thenAccept { response ->
							// Add the response to the conversation
							conversation.addNPCMessage(target, response)
						}
					}

					// Set cooldowns
					setNPCCooldown(initiator)
					setNPCCooldown(target)
				}
			}, 10L, 10L)
	}

	/**
	 * Makes an NPC face toward a specific location
	 */
	private fun makeNPCFaceLocation(
		npc: NPC,
		targetLocation: Location,
	) {
		if (!npc.isSpawned) return

		val npcLoc = npc.entity.location
		val direction = targetLocation.clone().subtract(npcLoc).toVector()
		val npcLocation = npc.entity.location.clone()
		npcLocation.direction = direction
		npc.entity.teleport(npcLocation)
	}

	/**
	 * Checks if an NPC is close enough to a target location
	 */
	private fun isNearTarget(
		npc: NPC,
		targetLocation: Location,
	): Boolean {
		if (!npc.isSpawned) return false

		val npcLocation = npc.entity.location
		val distance = npcLocation.distance(targetLocation)
		return distance < 3.0 // Consider "near" if within 3 blocks
	}

	/**
	 * Handle navigation complete events
	 */
	@EventHandler
	fun onNavigationComplete(event: NavigationCompleteEvent) {
		// Can be implemented to handle post-navigation actions
	}

	/**
	 * Makes an NPC walk to a specified location with progress monitoring
	 *
	 * @param npc The NPC to move
	 * @param targetLocation The location to move to
	 * @param distanceMargin How close the NPC needs to get to consider arrival (in blocks)
	 * @param speedModifier Speed multiplier for the NPC's movement
	 * @param timeout Maximum time in seconds before canceling (0 for no timeout)
	 * @param onArrival Callback to run when NPC arrives (can be null)
	 * @param onFailed Callback to run if navigation fails (can be null)
	 * @return Navigation task ID that can be used to cancel the movement
	 */
	fun walkToLocation(
		npc: NPC,
		targetLocation: Location,
		distanceMargin: Double,
		speedModifier: Float,
		timeout: Int,
		onArrival: Runnable?,
		onFailed: Runnable?,
	): Int {
		if (!npc.isSpawned) {
			onFailed?.run()
			return -1
		}

		val npcLocation = npc.entity.location
		val totalDistance = npcLocation.distance(targetLocation)

		// Direct walk for now (waypoint system commented out in original)
		return directWalkToLocation(
			npc,
			targetLocation,
			distanceMargin,
			speedModifier,
			timeout,
			onArrival,
			onFailed,
		)
	}

	/**
	 * Makes an NPC walk to an entity with progress monitoring
	 */
	fun walkToLocation(
		npc: NPC,
		target: Entity,
		distanceMargin: Double,
		speedModifier: Float,
		timeout: Int,
		onArrival: Runnable?,
		onFailed: Runnable?,
	): Int {
		if (!npc.isSpawned) {
			onFailed?.run()
			return -1
		}

		// Direct walk to entity
		return directWalkToLocation(npc, target, distanceMargin, speedModifier, timeout, onArrival, onFailed)
	}

	/**
	 * Internal method that handles navigation through a sequence of waypoints
	 */
	private fun walkToWaypoints(
		npc: NPC,
		waypoints: List<Location>,
		currentIndex: Int,
		distanceMargin: Double,
		speedModifier: Float,
		maxPathfindingRange: Float,
		timeout: Int,
		onArrival: Runnable?,
		onFailed: Runnable?,
	): Int {
		if (currentIndex >= waypoints.size || !npc.isSpawned) {
			onFailed?.run()
			return -1
		}

		val currentWaypoint = waypoints[currentIndex]
		val isLastWaypoint = (currentIndex == waypoints.size - 1)

		// Use the appropriate distance margin
		val waypointMargin = if (isLastWaypoint) distanceMargin else 2.0

		return directWalkToLocation(
			npc,
			currentWaypoint,
			waypointMargin,
			speedModifier,
			timeout,
			// On waypoint arrival
			Runnable {
				if (isLastWaypoint) {
					// Reached final destination
					onArrival?.run()
				} else {
					// Proceed to next waypoint
					walkToWaypoints(
						npc, waypoints, currentIndex + 1,
						distanceMargin, speedModifier,
						maxPathfindingRange, timeout,
						onArrival, onFailed,
					)
				}
			},
			// On failure
			onFailed,
		)
	}

	/**
	 * Direct walk method for a single segment of movement to a location
	 */
	private fun directWalkToLocation(
		npc: NPC,
		targetLocation: Location,
		distanceMargin: Double,
		speedModifier: Float,
		timeout: Int,
		onArrival: Runnable?,
		onFailed: Runnable?,
	): Int {
		if (!npc.isSpawned) {
			onFailed?.run()
			return -1
		}

		// Set up navigation parameters
		val navigator = npc.navigator
		navigator.defaultParameters
			.speedModifier(speedModifier)
			.range(100f)
			.distanceMargin(distanceMargin)

		// Start navigation
		navigator.cancelNavigation()
		navigator.setTarget(targetLocation)

		// Check if navigation actually started
		if (!navigator.isNavigating) {
			onFailed?.let {
				Bukkit.getScheduler().runTask(plugin, it)
			}
			return -1
		}

		// Variables for tracking movement
		val lastLocationHolder = arrayOf(npc.entity.location)
		val stuckCounter = intArrayOf(0)
		val taskIdHolder = intArrayOf(-1)

		// Navigation monitoring task
		taskIdHolder[0] =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				// Check if NPC is still valid and spawned
				if (!npc.isSpawned) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					onFailed?.run()
					return@scheduleSyncRepeatingTask
				}

				val currentLocation = npc.entity.location

				// Check if reached destination
				val distanceToTarget = currentLocation.distance(targetLocation)
				if (distanceToTarget <= distanceMargin * 2) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					val rot = npc.getOrAddTrait(RotationTrait::class.java)
					rot.physicalSession.rotateToFace(targetLocation)
					onArrival?.run()
					return@scheduleSyncRepeatingTask
				}

				// Check if stuck and reestablish navigation if needed
				if (!navigator.isNavigating) {
					navigator.cancelNavigation()
					navigator.setTarget(targetLocation)
				}

				// Update last location
				lastLocationHolder[0] = currentLocation
			}, 20L, 20L) // Check every second

		// Set timeout if specified
		if (timeout > 0) {
			Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
				// Check if task is still running
				if (Bukkit.getScheduler().isQueued(taskIdHolder[0]) ||
					Bukkit.getScheduler().isCurrentlyRunning(taskIdHolder[0])
				) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()

					onFailed?.run()
				}
			}, timeout * 20L) // Convert seconds to ticks
		}

		return taskIdHolder[0]
	}

	/**
	 * Direct walk method for a single segment of movement to an entity
	 */
	private fun directWalkToLocation(
		npc: NPC,
		target: Entity,
		distanceMargin: Double,
		speedModifier: Float,
		timeout: Int,
		onArrival: Runnable?,
		onFailed: Runnable?,
	): Int {
		if (!npc.isSpawned) {
			onFailed?.run()
			return -1
		}

		if (target == null || !target.isValid) {
			onFailed?.run()
			return -1
		}

		// Set up navigation parameters
		val navigator = npc.navigator
		navigator.defaultParameters
			.speedModifier(speedModifier)
			.range(100f)
			.distanceMargin(distanceMargin)

		// Start navigation
		navigator.cancelNavigation()
		navigator.setTarget(target, false)

		// Check if navigation actually started
		if (!navigator.isNavigating) {
			onFailed?.let {
				Bukkit.getScheduler().runTask(plugin, it)
			}
			return -1
		}

		// Variables for tracking movement
		val lastLocationHolder = arrayOf(npc.entity.location)
		val stuckCounter = intArrayOf(0)
		val taskIdHolder = intArrayOf(-1)

		// Navigation monitoring task
		taskIdHolder[0] =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				// Check if NPC is still valid and spawned
				if (!npc.isSpawned) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					onFailed?.run()
					return@scheduleSyncRepeatingTask
				}

				val currentLocation = npc.entity.location

				// Check if reached destination
				val distanceToTarget = currentLocation.distance(target.location)
				if (distanceToTarget <= distanceMargin) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					onArrival?.run()
					return@scheduleSyncRepeatingTask
				}

				// Check if stuck and reestablish navigation if needed
				if (!navigator.isNavigating) {
					navigator.cancelNavigation()
					navigator.setTarget(target, false)
				}

				// Update last location
				lastLocationHolder[0] = currentLocation
			}, 20L, 20L) // Check every second

		// Set timeout if specified
		if (timeout > 0) {
			Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
				// Check if task is still running
				if (Bukkit.getScheduler().isQueued(taskIdHolder[0]) ||
					Bukkit.getScheduler().isCurrentlyRunning(taskIdHolder[0])
				) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()

					onFailed?.run()
				}
			}, timeout * 20L) // Convert seconds to ticks
		}

		return taskIdHolder[0]
	}

	/**
	 * Makes an NPC walk away from a conversation
	 */
	fun makeNPCWalkAway(
		npc: NPC,
		convo: Conversation,
	) {
		if (!npc.isSpawned) return

		val npcLocation = npc.entity.location
		val targetLocation =
			findWalkableLocation(npc, convo) ?: run {
				// If we can't find a walkable location, just cancel any navigation
				npc.navigator.cancelNavigation()
				return
			}

		// Set up navigation
		val navigator = npc.navigator
		navigator.localParameters
			.speedModifier(1.0f)
			.distanceMargin(1.5)

		navigator.cancelNavigation()
		navigator.setTarget(targetLocation)

		// Create a task ID holder
		val taskIdHolder = intArrayOf(-1)

		// Create a task that periodically updates the navigation target and checks completion
		taskIdHolder[0] =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				if (!npc.isSpawned || !navigator.isNavigating) {
					// Cancel task if NPC is no longer valid or navigation has stopped
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					return@scheduleSyncRepeatingTask
				}

				// If NPC is stuck, try to find a new target
				if (!navigator.isNavigating) {
					navigator.cancelNavigation()
					navigator.setTarget(targetLocation)
				}
			}, 20L, 20L) // Check every second (20 ticks)

		// Add a timeout to cancel the task after 10 seconds
		Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
			if (taskIdHolder[0] != -1) {
				Bukkit.getScheduler().cancelTask(taskIdHolder[0])
				if (npc.isSpawned && navigator.isNavigating) {
					navigator.cancelNavigation()
				}
			}
		}, 200L) // 10 seconds (200 ticks)
	}

	/**
	 * Makes an NPC speak without walking
	 */
	fun makeNPCTalk(
		npc: NPC,
		message: String,
	) {
		if (!npc.isSpawned || isNPCDisabled(npc)) return

		// Broadcast the message to players
		plugin.npcMessageService.broadcastNPCMessage(message, npc)

		// Set cooldown for this NPC
		setNPCCooldown(npc)
	}

	/**
	 * Makes an NPC follow a player
	 */
	fun followPlayer(
		npc: NPC,
		player: Player,
	) {
		if (!npc.isSpawned || !player.isOnline || isNPCDisabled(npc)) return

		val navigator = npc.navigator
		navigator.setTarget(player, false)

		var taskId = -1
		taskId =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				if (!npc.isSpawned || !player.isOnline) {
					Bukkit.getScheduler().cancelTask(taskId)
					return@scheduleSyncRepeatingTask
				}

				val currentTarget = navigator.targetAsLocation
				if (currentTarget == null || currentTarget.distanceSquared(player.location) > 5.0) {
					navigator.setTarget(player, false)
				}
			}, 20L, 20L)
	}

	/**
	 * Stops an NPC's current navigation
	 */
	fun stopNavigation(npc: NPC) {
		if (npc.isSpawned) {
			npc.navigator.cancelNavigation()
		}
	}

	fun spawnNPC(
		npcName: String,
		location: Location?,
		player: Player,
		message: String?,
	) {
		// Check if Citizens plugin is enabled
		if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
			Bukkit.getLogger().warning("Citizens plugin is not enabled!")
			return
		}

		// Create the NPC
		val npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, npcName)

		// Set the NPC location
		npc.spawn(location)

		// Add traits to the NPC (e.g., text, skin)
		npc.name = npcName
		npc.isProtected = true // Prevent NPC from taking damage

		// Add a delay before calling eventGoToPlayerAndSay
		Bukkit.getScheduler().runTaskLater(
			plugin,
			Runnable {
				if (!npc.isSpawned) {
					plugin.logger.warning("NPC '$npcName' failed to spawn!")
					return@Runnable
				}
				eventGoToPlayerAndTalk(npc, player, message ?: "Hello!", null)
			},
			5L,
		) // 5 ticks delay (adjust as needed, 1 tick = 50ms)
	}

	/**
	 * Save NPC data
	 */
	fun saveData() {
		try {
			val disabledNpcsFile = File(plugin.dataFolder, "disabled-npcs.yml")
			val config = YamlConfiguration()

			config.set("disabled-npcs", disabledNPCs.toList())
			config.save(disabledNpcsFile)
		} catch (e: Exception) {
			plugin.logger.severe("Could not save disabled NPCs: ${e.message}")
		}
	}

	/**
	 * Load NPC data
	 */
	private fun loadData() {
		try {
			val disabledNpcsFile = File(plugin.dataFolder, "disabled-npcs.yml")
			if (disabledNpcsFile.exists()) {
				val config = YamlConfiguration.loadConfiguration(disabledNpcsFile)
				val disabledList = config.getIntegerList("disabled-npcs")

				disabledNPCs.clear()
				disabledNPCs.addAll(disabledList)
			}
		} catch (e: Exception) {
			plugin.logger.severe("Could not load disabled NPCs: ${e.message}")
		}
	}

	/**
	 * Find a walkable location for an NPC to move to away from a conversation
	 */
	private fun findWalkableLocation(
		npc: NPC,
		convo: Conversation,
	): Location? {
		if (!npc.isSpawned) return null

		val npcLocation = npc.entity.location
		val world = npcLocation.world

		// Try 5 random directions
		for (i in 0 until 5) {
			// Random angle
			val angle = Math.random() * Math.PI * 2

			// Get a point 10-15 blocks away in a random direction
			val distance = 10 + Math.random() * 5
			val x = npcLocation.x + Math.cos(angle) * distance
			val z = npcLocation.z + Math.sin(angle) * distance

			// Find ground level at this x,z position
			var y = npcLocation.y
			val targetLocation = Location(world, x, y, z)

			// Check if location is safe (not inside blocks, etc.)
			if (world.getBlockAt(targetLocation).isPassable) {
				return targetLocation
			}
		}

		// Fallback: just return a point 10 blocks behind the NPC
		val direction = npcLocation.direction.multiply(-1)
		return npcLocation.clone().add(direction.multiply(10))
	}
}
