package com.canefe.story.player

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.text.set

class NPCManager private constructor(private val plugin: Story) : Listener {
	// NPC cooldowns mapping (NPC UUID to timestamp)
	private val npcCooldowns = HashMap<UUID, Long>()

	// Disabled NPCs set
	private val disabledNPCs = HashSet<Int>()

	// Scaled NPCs Mapping (NPC UUID to scale)
	val scaledNPCs = HashMap<UUID, Double>()

	// Navigation tracking system
	private val activeNavigationTasks = ConcurrentHashMap<String, NavigationTask>()
	private val navigationHistory = LinkedList<NavigationEvent>()
	private val maxHistorySize = 100

	// Navigation task data class
	data class NavigationTask(
		val npcName: String,
		val npcId: Int,
		val startTime: Long,
		var targetLocation: Location,
		val targetType: String, // "location", "entity", "waypoint"
		val distanceMargin: Double,
		val timeout: Int,
		var taskId: Int,
		var lastPosition: Location,
		var stuckCounter: Int = 0,
		var retryAttempts: Int = 0,
		var status: NavigationStatus = NavigationStatus.ACTIVE
	)

	// Navigation event for history tracking
	data class NavigationEvent(
		val timestamp: Long,
		val npcName: String,
		val event: String, // "started", "completed", "failed", "stuck", "timeout"
		val details: String
	)

	enum class NavigationStatus {
		ACTIVE,
		STUCK,
		COMPLETED,
		FAILED,
		CANCELLED,
		TIMEOUT
	}

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

	fun loadConfig() {
		loadData()
	}

	fun applyScalesToNPCs() {
		// Skip if Citizens isn't enabled
		if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
			plugin.logger.warning("Citizens plugin not enabled, couldn't apply NPC scales")
			return
		}

		// Apply scales to all NPCs that have saved scale values
		scaledNPCs.forEach { (uuid, scale) ->
			// Find the NPC by UUID
			val npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid)
			if (npc != null && npc.isSpawned && npc.entity is LivingEntity) {
				val livingEntity = npc.entity as LivingEntity
				livingEntity.getAttribute(Attribute.GENERIC_SCALE)?.baseValue = scale
				plugin.logger.info("Applied scale $scale to NPC ${npc.name}")
			}
		}
	}

	/**
	 * Check if an NPC is on cooldown
	 */
	fun isNPCOnCooldown(npc: NPC): Boolean {
		val uuid = npc.uniqueId
		val lastInteraction = npcCooldowns[uuid] ?: return false
		val currentTime = System.currentTimeMillis()
		val cooldown = plugin.config.radiantCooldown.toLong()

		// Default cooldown of 10 seconds
		return currentTime - lastInteraction < TimeUnit.SECONDS.toMillis(cooldown)
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
		val cooldown = plugin.config.radiantCooldown.toLong()
		val remainingMillis = TimeUnit.SECONDS.toMillis(cooldown) - (currentTime - lastInteraction)

		return if (remainingMillis <= 0) 0 else (remainingMillis / 1000).toInt()
	}

	/**
	 * Check if NPC is disabled
	 */
	fun isNPCDisabled(npc: NPC): Boolean = disabledNPCs.contains(npc.id)

	/**
	 * Toggle NPC enabled/disabled status
	 */
	fun toggleNPC(npc: NPC, executor: CommandSender) {
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
	fun eventGoToPlayerAndTalk(npc: NPC, player: Player, message: String, onCompleteAction: Runnable?) {
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

	/**
	 * Makes an NPC walk to another NPC and initiate conversation
	 */
	fun walkToNPC(initiator: NPC, target: NPC, firstMessage: String, radiant: Boolean = false) {
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
					navigator.cancelNavigation()

					// Have the initiator face the target
					makeNPCFaceLocation(initiator, target.entity.location)

					// Send the message
					plugin.npcMessageService.broadcastNPCMessage(firstMessage, initiator)

					// Start a conversation between the NPCs
					val npcs = ArrayList<NPC>()
					npcs.add(initiator)
					npcs.add(target)

					val responseDelay = 4.toLong() // Delay in seconds before NPC responds

					if (radiant) {
						plugin.conversationManager.startRadiantConversation(npcs).thenAccept { conversation ->
							// Add the first message to the conversation
							conversation.addNPCMessage(initiator, firstMessage)
							// Get A list of string only from conversation.history
							val history = conversation.history.map { it.content }
							plugin.conversationManager.handleHolograms(conversation, target.name)
							// Generate the NPC response after a delay
							Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
								// Generate the NPC response
								plugin.npcResponseService.generateNPCResponse(target, history, broadcast = false)
									.thenAccept { response ->
										// Add the response to the conversation
										conversation.addNPCMessage(target, response)
										plugin.conversationManager.cleanupHolograms(conversation)
										// Broadcast the response to players
										plugin.npcMessageService.broadcastNPCMessage(response, target)
									}
							}, responseDelay * 20L) // Convert seconds to ticks
						}
					} else {
						plugin.conversationManager.startConversation(npcs).thenAccept { conversation ->
							// Add the first message to the conversation
							conversation.addNPCMessage(initiator, firstMessage)
							// Get A list of string only from conversation.history
							val history = conversation.history.map { it.content }
							plugin.npcResponseService.generateNPCResponse(target, history).thenAccept { response ->
								// Add the response to the conversation
								conversation.addNPCMessage(target, response)
							}
						}
					}

					// Set cooldowns
					setNPCCooldown(initiator)
					setNPCCooldown(target)
				}
			}, 10L, 10L)
	}

	/**
	 * Scales an NPC
	 */
	fun scaleNPC(npc: NPC, scale: Double): Boolean {
		scaledNPCs [npc.uniqueId] = scale
		saveData()

		val livingEntity: LivingEntity?
		val entity = npc.entity
		if (npc.isSpawned && entity is LivingEntity) {
			livingEntity = entity
		} else {
			return false
		}
		livingEntity.getAttribute(Attribute.GENERIC_SCALE)!!.setBaseValue(scale)
		return true
	}

	/**
	 * Makes an NPC face toward a specific location
	 */
	private fun makeNPCFaceLocation(npc: NPC, targetLocation: Location) {
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
	private fun isNearTarget(npc: NPC, targetLocation: Location): Boolean {
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
		// check if it is in the same world
		if (npcLocation.world != targetLocation.world) {
			onFailed?.run()
			return -1
		}
		val totalDistance = npcLocation.distanceSquared(targetLocation)

		// If total distance larger than 75, use waypoints
		if (totalDistance > 50 * 50) { // Using distanceSquared, so compare with square of 75
			// Generate waypoints between current location and target
			val waypoints = generateWaypoints(npcLocation, targetLocation)

			// Start waypoint navigation
			return walkToWaypoints(
				npc,
				waypoints,
				0, // Start with first waypoint
				distanceMargin,
				speedModifier,
				100f, // Max pathfinding range
				timeout,
				onArrival,
				onFailed,
			)
		} else {
			// For shorter distances, use direct navigation
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
	}

	/**
	 * Generates a series of waypoints between two locations
	 * @param start Starting location
	 * @param end Target location
	 * @return List of waypoints including start and end points
	 */
	private fun generateWaypoints(start: Location, end: Location): List<Location> {
		val waypoints = mutableListOf<Location>()

		// Always include start point
		waypoints.add(start.clone())

		// Get total distance
		val distance = start.distance(end)

		// Calculate number of waypoints (1 waypoint per 50 blocks, minimum 2)
		val segments = maxOf(2, (distance / 50).toInt())

		// Create waypoints at regular intervals
		for (i in 1 until segments) {
			val fraction = i.toDouble() / segments
			val x = start.x + (end.x - start.x) * fraction
			val y = start.y + (end.y - start.y) * fraction
			val z = start.z + (end.z - start.z) * fraction

			val waypoint = Location(start.world, x, y, z)

			// Ensure waypoint is safe for navigation
			adjustWaypointHeight(waypoint)

			waypoints.add(waypoint)
		}

		// Always include end point
		waypoints.add(end.clone())

		return waypoints
	}

	/**
	 * Adjusts the height of a waypoint to ensure it's on solid ground
	 */
	private fun adjustWaypointHeight(waypoint: Location) {
		val world = waypoint.world ?: return
		val x = waypoint.blockX
		val z = waypoint.blockZ
		val startY = waypoint.blockY

		// Try to find solid ground within a reasonable range
		for (yOffset in -5..5) {
			val y = startY + yOffset

			// Don't check below world or above world height
			if (y <= 0 || y >= world.maxHeight - 2) continue

			// Check if there's solid ground to stand on (block below feet)
			val groundBlock = world.getBlockAt(x, y - 1, z)

			// Check if there's space for the NPC's body (at feet and head level)
			val feetBlock = world.getBlockAt(x, y, z)
			val headBlock = world.getBlockAt(x, y + 1, z)

			// Valid if: solid ground below, passable space for body
			if (groundBlock.type.isSolid && !feetBlock.type.isSolid && !headBlock.type.isSolid) {
				waypoint.y = y.toDouble()
				return
			}
		}

		// If no valid position found, try to find the highest solid block
		for (y in world.maxHeight - 1 downTo 1) {
			val block = world.getBlockAt(x, y, z)
			if (block.type.isSolid) {
				waypoint.y = (y + 1).toDouble() // Stand on top of the solid block
				return
			}
		}
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
						npc,
						waypoints,
						currentIndex + 1,
						distanceMargin,
						speedModifier,
						maxPathfindingRange,
						timeout,
						onArrival,
						onFailed,
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

		// Validate path before starting
		if (!validatePath(npc, targetLocation)) {
			addNavigationEvent(npc.name, "failed", "Path validation failed")
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
			addNavigationEvent(npc.name, "failed", "Navigation failed to start")
			onFailed?.let {
				Bukkit.getScheduler().runTask(plugin, it)
			}
			return -1
		}

		// Variables for tracking movement
		var lastLocation = npc.entity.location
		var stuckCounter = 0
		var retryAttempts = 0
		val maxRetries = 3
		val taskIdHolder = intArrayOf(-1)

		// Register navigation task for tracking
		val task = registerNavigationTask(npc, targetLocation, "location", distanceMargin, timeout, -1)

		// Navigation monitoring task
		taskIdHolder[0] =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				// Check if NPC is still valid and spawned
				if (!npc.isSpawned) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					completeNavigationTask(npc.name, false, "NPC despawned")
					onFailed?.run()
					return@scheduleSyncRepeatingTask
				}

				// Check if NPC is in a conversation
				if (plugin.conversationManager.isInConversation(npc)) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, false, "NPC entered conversation")
					return@scheduleSyncRepeatingTask
				}

				val currentLocation = npc.entity.location

				// Check if reached destination (use consistent distance margin)
				val distanceToTarget = currentLocation.distance(targetLocation)
				if (distanceToTarget <= distanceMargin) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, true, "Reached destination")
					onArrival?.run()
					return@scheduleSyncRepeatingTask
				}

				// Improved stuck detection
				val hasMovedSignificantly = currentLocation.distance(lastLocation) >= 0.5
				if (!hasMovedSignificantly) {
					stuckCounter++
					updateNavigationTask(npc.name, currentLocation, stuck = true)

					if (stuckCounter >= 5) { // If stuck for 5 seconds
						retryAttempts++

						if (retryAttempts >= maxRetries) {
							// Max retries reached, fail the navigation
							Bukkit.getScheduler().cancelTask(taskIdHolder[0])
							navigator.cancelNavigation()
							completeNavigationTask(npc.name, false, "Max retries reached (${maxRetries})")
							onFailed?.run()
							return@scheduleSyncRepeatingTask
						}

						// Try to reestablish navigation with slightly adjusted target
						navigator.cancelNavigation()

						// Create a slightly offset target to avoid exact same path
						val offsetTarget = targetLocation.clone().add(
							(Math.random() - 0.5) * 2, // ±1 block X
							0.0,
							(Math.random() - 0.5) * 2  // ±1 block Z
						)

						// Find safe ground for offset target
						val safeTarget = findSafeLocation(offsetTarget) ?: targetLocation
						navigator.setTarget(safeTarget)

						stuckCounter = 0 // Reset counter after attempting to fix
						addNavigationEvent(npc.name, "retry", "Attempt ${retryAttempts}/${maxRetries}")
					}
				} else {
					stuckCounter = 0 // Reset counter if NPC is moving
					updateNavigationTask(npc.name, currentLocation, stuck = false)
				}

				// Check if navigation stopped and reestablish if needed
				if (!navigator.isNavigating && stuckCounter < 3) {
					navigator.cancelNavigation()
					navigator.setTarget(targetLocation)
					addNavigationEvent(npc.name, "restarted", "Navigation stopped, restarting")
				}

				// Update last location
				lastLocation = currentLocation
			}, 20L, 20L) // Check every second

		// Update task with actual task ID
		task.taskId = taskIdHolder[0]

		// Set timeout if specified
		if (timeout > 0) {
			Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
				// Check if task is still running
				if (Bukkit.getScheduler().isQueued(taskIdHolder[0]) ||
					Bukkit.getScheduler().isCurrentlyRunning(taskIdHolder[0])
				) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, false, "Timeout after ${timeout}s")
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
			addNavigationEvent(npc.name, "failed", "Target entity is invalid")
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
			addNavigationEvent(npc.name, "failed", "Navigation to entity failed to start")
			onFailed?.let {
				Bukkit.getScheduler().runTask(plugin, it)
			}
			return -1
		}

		// Variables for tracking movement
		var lastLocation = npc.entity.location
		var stuckCounter = 0
		var retryAttempts = 0
		val maxRetries = 3
		val taskIdHolder = intArrayOf(-1)

		// Register navigation task for tracking
		val task = registerNavigationTask(npc, target.location, "entity", distanceMargin, timeout, -1)

		// Navigation monitoring task
		taskIdHolder[0] =
			Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
				// Check if NPC is still valid and spawned
				if (!npc.isSpawned) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					completeNavigationTask(npc.name, false, "NPC despawned")
					onFailed?.run()
					return@scheduleSyncRepeatingTask
				}

				// Check if NPC is in a conversation
				if (plugin.conversationManager.isInConversation(npc)) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, false, "NPC entered conversation")
					return@scheduleSyncRepeatingTask
				}

				// Check if target is still valid
				if (!target.isValid) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, false, "Target entity became invalid")
					onFailed?.run()
					return@scheduleSyncRepeatingTask
				}

				val currentLocation = npc.entity.location

				// Update task target location to entity's current position
				task.targetLocation = target.location.clone()

				// Check if reached destination (use consistent distance margin)
				val distanceToTarget = currentLocation.distance(target.location)
				if (distanceToTarget <= distanceMargin) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, true, "Reached entity target")
					onArrival?.run()
					return@scheduleSyncRepeatingTask
				}

				// Improved stuck detection
				val hasMovedSignificantly = currentLocation.distance(lastLocation) >= 0.5
				if (!hasMovedSignificantly) {
					stuckCounter++
					updateNavigationTask(npc.name, currentLocation, stuck = true)

					if (stuckCounter >= 5) { // If stuck for 5 seconds
						retryAttempts++

						if (retryAttempts >= maxRetries) {
							// Max retries reached, fail the navigation
							Bukkit.getScheduler().cancelTask(taskIdHolder[0])
							navigator.cancelNavigation()
							completeNavigationTask(npc.name, false, "Max retries reached for entity target")
							onFailed?.run()
							return@scheduleSyncRepeatingTask
						}

						// Try to reestablish navigation
						navigator.cancelNavigation()
						navigator.setTarget(target, false)

						stuckCounter = 0 // Reset counter after attempting to fix
						addNavigationEvent(npc.name, "retry", "Entity target attempt ${retryAttempts}/${maxRetries}")
					}
				} else {
					stuckCounter = 0 // Reset counter if NPC is moving
					updateNavigationTask(npc.name, currentLocation, stuck = false)
				}

				// Check if navigation stopped and reestablish if needed
				if (!navigator.isNavigating && stuckCounter < 3) {
					navigator.cancelNavigation()
					navigator.setTarget(target, false)
					addNavigationEvent(npc.name, "restarted", "Entity navigation stopped, restarting")
				}

				// Update last location
				lastLocation = currentLocation
			}, 20L, 20L) // Check every second

		// Update task with actual task ID
		task.taskId = taskIdHolder[0]

		// Set timeout if specified
		if (timeout > 0) {
			Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
				// Check if task is still running
				if (Bukkit.getScheduler().isQueued(taskIdHolder[0]) ||
					Bukkit.getScheduler().isCurrentlyRunning(taskIdHolder[0])
				) {
					Bukkit.getScheduler().cancelTask(taskIdHolder[0])
					navigator.cancelNavigation()
					completeNavigationTask(npc.name, false, "Entity navigation timeout after ${timeout}s")
					onFailed?.run()
				}
			}, timeout * 20L) // Convert seconds to ticks
		}

		return taskIdHolder[0]
	}

	/**
	 * Makes an NPC walk away from a conversation
	 */
	fun makeNPCWalkAway(npc: NPC, convo: Conversation) {
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
	fun makeNPCTalk(npc: NPC, message: String) {
		if (!npc.isSpawned || isNPCDisabled(npc)) return

		// Broadcast the message to players
		plugin.npcMessageService.broadcastNPCMessage(message, npc)

		// Set cooldown for this NPC
		setNPCCooldown(npc)
	}

	/**
	 * Makes an NPC follow a player
	 */
	fun followPlayer(npc: NPC, player: Player) {
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

	fun spawnNPC(npcName: String, location: Location?, player: Player, message: String?) {
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
			// Disabled NPCs file
			val disabledNpcsFile = File(plugin.dataFolder, "disabled-npcs.yml")
			val config = YamlConfiguration()

			config.set("disabled-npcs", disabledNPCs.toList())
			config.save(disabledNpcsFile)

			// Scaled NPCs file
			val scaledNpcsFile = File(plugin.dataFolder, "scaled-npcs.yml")
			val scaledConfig = YamlConfiguration()

			// Create a proper configuration section for each UUID
			scaledNPCs.forEach { (uuid, scale) ->
				scaledConfig.set("scaled-npcs.$uuid", scale)
			}

			scaledConfig.save(scaledNpcsFile)
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

			// Load scaled NPCs
			val scaledNpcsFile = File(plugin.dataFolder, "scaled-npcs.yml")
			if (scaledNpcsFile.exists()) {
				val scaledConfig = YamlConfiguration.loadConfiguration(scaledNpcsFile)
				val scaledList = scaledConfig.getConfigurationSection("scaled-npcs")?.getKeys(false) ?: emptySet()

				scaledNPCs.clear()
				for (key in scaledList) {
					val scale = scaledConfig.getDouble("scaled-npcs.$key")
					scaledNPCs[UUID.fromString(key)] = scale
				}
			}
		} catch (e: Exception) {
			plugin.logger.severe("Could not load disabled NPCs: ${e.message}")
		}
	}

	/**
	 * Find a walkable location for an NPC to move to away from a conversation
	 */
	private fun findWalkableLocation(npc: NPC, convo: Conversation): Location? {
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

	/**
	 * Navigation Tracking and Debugging Methods
	 */

	/**
	 * Get all currently active navigation tasks
	 */
	fun getActiveNavigationTasks(): Map<String, NavigationTask> {
		return activeNavigationTasks.toMap()
	}

	/**
	 * Get navigation history
	 */
	fun getNavigationHistory(): List<NavigationEvent> {
		return navigationHistory.toList()
	}

	/**
	 * Print all active navigation tasks for debugging
	 */
	fun printActiveNavigationTasks(player: CommandSender) {
		if (activeNavigationTasks.isEmpty()) {
			player.sendInfo("No active navigation tasks.")
			return
		}

		player.sendInfo("Active Navigation Tasks (${activeNavigationTasks.size}):")
		activeNavigationTasks.values.sortedBy { it.startTime }.forEach { task ->
			val duration = (System.currentTimeMillis() - task.startTime) / 1000

			// Use NPCUtils to get NPC asynchronously
			val npcUtils = com.canefe.story.npc.util.NPCUtils.getInstance(plugin)
			npcUtils.getNPCByNameAsync(task.npcName).thenAccept { npc ->
				val distance = if (npc?.isSpawned == true) {
					val currentDistance = npc.entity.location.distance(task.targetLocation)
					String.format("%.1f", currentDistance)
				} else "N/A"

				player.sendInfo(" • ${task.npcName} (${task.status})")
				player.sendInfo("   Target: ${task.targetType} at ${formatLocation(task.targetLocation)}")
				player.sendInfo("   Duration: ${duration}s, Distance: ${distance}m, Retries: ${task.retryAttempts}")
				if (task.status == NavigationStatus.STUCK) {
					player.sendInfo("   Stuck counter: ${task.stuckCounter}/5")
				}
			}
		}
	}

	/**
	 * Print navigation history for debugging
	 */
	fun printNavigationHistory(player: Player, limit: Int = 10) {
		if (navigationHistory.isEmpty()) {
			player.sendInfo("No navigation history.")
			return
		}

		player.sendInfo("Recent Navigation History (last $limit events):")
		navigationHistory.takeLast(limit).forEach { event ->
			val timeAgo = (System.currentTimeMillis() - event.timestamp) / 1000
			player.sendInfo(" • ${event.npcName}: ${event.event} (${timeAgo}s ago)")
			if (event.details.isNotEmpty()) {
				player.sendInfo("   ${event.details}")
			}
		}
	}

	/**
	 * Cancel all navigation tasks for a specific NPC
	 */
	fun cancelNavigationForNPC(npcName: String): Boolean {
		val task = activeNavigationTasks[npcName.lowercase()]
		if (task != null) {
			// Cancel the Bukkit task
			Bukkit.getScheduler().cancelTask(task.taskId)

			// Cancel the NPC navigation using NPCUtils
			val npcUtils = com.canefe.story.npc.util.NPCUtils.getInstance(plugin)
			npcUtils.getNPCByNameAsync(npcName).thenAccept { npc ->
				if (npc?.isSpawned == true) {
					npc.navigator.cancelNavigation()
				}
			}

			// Update task status and remove from active tasks
			task.status = NavigationStatus.CANCELLED
			activeNavigationTasks.remove(npcName.lowercase())

			// Add to history
			addNavigationEvent(npcName, "cancelled", "Navigation cancelled manually")

			return true
		}
		return false
	}

	/**
	 * Cancel all active navigation tasks
	 */
	fun cancelAllNavigationTasks(): Int {
		val count = activeNavigationTasks.size
		val npcUtils = com.canefe.story.npc.util.NPCUtils.getInstance(plugin)

		activeNavigationTasks.values.forEach { task ->
			// Cancel the Bukkit task
			Bukkit.getScheduler().cancelTask(task.taskId)

			// Cancel the NPC navigation using NPCUtils
			npcUtils.getNPCByNameAsync(task.npcName).thenAccept { npc ->
				if (npc?.isSpawned == true) {
					npc.navigator.cancelNavigation()
				}
			}

			// Add to history
			addNavigationEvent(task.npcName, "cancelled", "Navigation cancelled (bulk cancel)")
		}

		activeNavigationTasks.clear()
		return count
	}

	/**
	 * Add a navigation event to history
	 */
	private fun addNavigationEvent(npcName: String, event: String, details: String = "") {
		navigationHistory.add(NavigationEvent(System.currentTimeMillis(), npcName, event, details))

		// Keep history size manageable
		while (navigationHistory.size > maxHistorySize) {
			navigationHistory.removeFirst()
		}
	}

	/**
	 * Register a navigation task for tracking
	 */
	private fun registerNavigationTask(
		npc: NPC,
		targetLocation: Location,
		targetType: String,
		distanceMargin: Double,
		timeout: Int,
		taskId: Int
	): NavigationTask {
		// Cancel any existing navigation for this NPC
		cancelNavigationForNPC(npc.name)

		val task = NavigationTask(
			npcName = npc.name,
			npcId = npc.id,
			startTime = System.currentTimeMillis(),
			targetLocation = targetLocation.clone(),
			targetType = targetType,
			distanceMargin = distanceMargin,
			timeout = timeout,
			taskId = taskId,
			lastPosition = npc.entity.location.clone()
		)

		activeNavigationTasks[npc.name.lowercase()] = task
		addNavigationEvent(npc.name, "started", "Target: $targetType at ${formatLocation(targetLocation)}")

		return task
	}

	/**
	 * Complete a navigation task
	 */
	private fun completeNavigationTask(npcName: String, success: Boolean, reason: String = "") {
		val task = activeNavigationTasks.remove(npcName.lowercase())
		if (task != null) {
			task.status = if (success) NavigationStatus.COMPLETED else NavigationStatus.FAILED
			val event = if (success) "completed" else "failed"
			addNavigationEvent(npcName, event, reason)
		}
	}

	/**
	 * Update navigation task status
	 */
	private fun updateNavigationTask(npcName: String, newPosition: Location, stuck: Boolean = false) {
		val task = activeNavigationTasks[npcName.lowercase()]
		if (task != null) {
			task.lastPosition = newPosition.clone()
			if (stuck) {
				task.stuckCounter++
				task.status = NavigationStatus.STUCK
				if (task.stuckCounter >= 5) { // After 5 stuck attempts, mark as failed
					addNavigationEvent(npcName, "failed", "NPC stuck for too long (${task.stuckCounter} attempts)")
					completeNavigationTask(npcName, false, "Stuck for too long")
				}
			} else {
				task.stuckCounter = 0
				task.status = NavigationStatus.ACTIVE
			}
		}
	}

	/**
	 * Format location for display
	 */
	private fun formatLocation(loc: Location): String {
		return "${loc.world?.name}(${loc.blockX}, ${loc.blockY}, ${loc.blockZ})"
	}

	/**
	 * Improved pathfinding validation
	 */
	private fun validatePath(npc: NPC, targetLocation: Location): Boolean {
		if (!npc.isSpawned) return false

		val startLoc = npc.entity.location
		val distance = startLoc.distance(targetLocation)

		// Check if target is too far
		if (distance > 200) {
			return false
		}

		// Check if target is in same world
		if (startLoc.world != targetLocation.world) {
			return false
		}

		// Check if target location is safe using proper ground validation
		val world = targetLocation.world ?: return false
		val x = targetLocation.blockX
		val y = targetLocation.blockY
		val z = targetLocation.blockZ

		// Check if there's solid ground to stand on (block below feet)
		val groundBlock = world.getBlockAt(x, y - 1, z)

		// Check if there's space for the NPC's body (at feet and head level)
		val feetBlock = world.getBlockAt(x, y, z)
		val headBlock = world.getBlockAt(x, y + 1, z)

		// Valid if: solid ground below, passable space for body
		return groundBlock.type.isSolid && !feetBlock.type.isSolid && !headBlock.type.isSolid
	}

	/**
	 * Find a safe location near the target for navigation
	 */
	private fun findSafeLocation(location: Location): Location? {
		val world = location.world ?: return null
		val x = location.blockX
		val z = location.blockZ

		// Check the original location first
		for (y in (location.blockY - 2)..(location.blockY + 2)) {
			val testLoc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
			val block = world.getBlockAt(testLoc)
			val blockAbove = world.getBlockAt(x, y + 1, z)

			if (block.type.isSolid && blockAbove.isPassable) {
				return testLoc.add(0.5, 1.0, 0.5) // Center and raise to standing position
			}
		}

		// Try nearby blocks
		for (xOffset in -1..1) {
			for (zOffset in -1..1) {
				if (xOffset == 0 && zOffset == 0) continue

				for (y in (location.blockY - 2)..(location.blockY + 2)) {
					val testLoc = Location(world, (x + xOffset).toDouble(), y.toDouble(), (z + zOffset).toDouble())
					val block = world.getBlockAt(testLoc)
					val blockAbove = world.getBlockAt(x + xOffset, y + 1, z + zOffset)

					if (block.type.isSolid && blockAbove.isPassable) {
						return testLoc.add(0.5, 1.0, 0.5)
					}
				}
			}
		}

		return null
	}
}
