package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.CurrentLocation
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.FollowTrait
import net.citizensnpcs.trait.SitTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.mcmonkey.sentinel.SentinelTrait
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.text.toInt

class NPCScheduleManager private constructor(
	private val plugin: Story,
) {
	val schedules = ConcurrentHashMap<String, NPCSchedule>()
	private val scheduleFolder: File =
		File(plugin.dataFolder, "schedules").apply {
			if (!exists()) {
				mkdirs()
			}
		}
	private var scheduleTask: BukkitTask? = null

	private val npcMovementQueue = LinkedList<NPC>()

	init {
		loadAllSchedules()
		startScheduleRunner()
	}

	fun reloadSchedules() {
		// Stop the current task
		scheduleTask?.cancel()

		// Reload all schedules
		loadAllSchedules()

		// Restart the schedule runner
		startScheduleRunner()
	}

	fun loadAllSchedules() {
		schedules.clear()
		val files = scheduleFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return

		for (file in files) {
			try {
				val npcName = file.name.replace(".yml", "")
				val schedule = loadSchedule(npcName)
				if (schedule != null) {
					schedules[npcName.lowercase()] = schedule
					plugin.logger.info("Loaded schedule for NPC: $npcName")
				}
			} catch (e: Exception) {
				plugin.logger.warning("Error loading schedule from file: ${file.name}")
				e.printStackTrace()
			}
		}
		plugin.logger.info("Loaded ${schedules.size} NPC schedules")
	}

	fun loadSchedule(npcName: String): NPCSchedule? {
		val scheduleFile = File(scheduleFolder, "$npcName.yml")
		if (!scheduleFile.exists()) {
			return null
		}

		val config = YamlConfiguration.loadConfiguration(scheduleFile)
		val schedule = NPCSchedule(npcName)

		// Load all time entries
		val scheduleSection = config.getConfigurationSection("schedule") ?: return schedule

		for (timeKey in scheduleSection.getKeys(false)) {
			try {
				val time = timeKey.toInt()
				val locationName = config.getString("schedule.$timeKey.location")
				val action = config.getString("schedule.$timeKey.action", "idle")
				val dialogue = config.getString("schedule.$timeKey.dialogue")
				val random = config.getBoolean("schedule.$timeKey.random", false)

				val entry = ScheduleEntry(time, locationName, action, dialogue, random)
				schedule.addEntry(entry)
			} catch (e: NumberFormatException) {
				plugin.logger.warning("Invalid time format in schedule for $npcName: $timeKey")
			}
		}

		return schedule
	}

	fun saveSchedule(schedule: NPCSchedule) {
		val scheduleFile = File(scheduleFolder, "${schedule.npcName}.yml")
		val config = YamlConfiguration()

		// Save all time entries
		for (entry in schedule.entries) {
			val timePath = "schedule.${entry.time}"
			config.set("$timePath.location", entry.locationName)
			config.set("$timePath.action", entry.action)
			if (entry.dialogue != null) {
				config.set("$timePath.dialogue", entry.dialogue)
			}
		}

		try {
			config.save(scheduleFile)
		} catch (e: IOException) {
			plugin.logger.severe("Could not save schedule for ${schedule.npcName}")
			e.printStackTrace()
		}
	}

	fun getEmptyScheduleTemplate(npcName: String): NPCSchedule {
		val schedule = NPCSchedule(npcName)

		for (hour in listOf(6, 12, 18, 19, 20, 22, 24)) {
			schedule.addEntry(ScheduleEntry(hour, "", "idle", ""))
		}
		return schedule
	}

	fun getSchedule(npcName: String): NPCSchedule? = schedules[npcName.lowercase()]

	private fun startScheduleRunner() {
		// Stop any existing task
		scheduleTask?.cancel()

		// Run every minute to check for schedule updates
		scheduleTask =
			object : BukkitRunnable() {
				override fun run() {
					// if no players are online, skip the schedule check
					if (plugin.server.onlinePlayers.isEmpty()) {
						return
					}
					val players = plugin.server.onlinePlayers
					val gameTime = plugin.server.worlds[0].time

					// Convert to 24-hour format (0-23)
					val hour = ((gameTime / 1000 + 6) % 24).toInt() // +6 because MC day starts at 6am

					// Handle NPCs with schedules
					if (plugin.config.scheduleEnabled) {
						for (schedule in schedules.values) {
							val currentEntry = schedule.getEntryForTime(hour)
							if (currentEntry != null) {
								executeScheduleEntry(schedule.npcName, currentEntry)
							}
						}
					}

// Handle NPCs without schedules or with empty location entries
					if (plugin.config.randomPathingEnabled) {
						// Pre-filter NPCs that are candidates for random movement
						val candidateNPCs =
							plugin.npcDataManager
								.getAllNPCNames()
								.asSequence()
								.filter { npcName ->
									val npc = plugin.npcDataManager.getNPC(npcName) ?: return@filter false
									// also teleport nearby storedLocation npcs (that are not spawned) we check if its
									// still close to the player
									val currentLocation = npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location

									if (currentLocation == null) {
										plugin.logger.warning("NPC $npcName has no location, skipping.")
										return@filter false
									}

									val nearbyPlayers =
										plugin.getNearbyPlayers(
											currentLocation,
											plugin.config.rangeBeforeTeleport * 5,
											ignoreY = true,
										)

									if (nearbyPlayers.isEmpty()) {
										return@filter false
									}

									// Check schedule status
									val hasSchedule = schedules.containsKey(npcName.lowercase())
									val hasLocationForCurrentTime =
										hasSchedule &&
											schedules[npcName.lowercase()]?.getEntryForTime(hour)?.locationName?.isNotEmpty() == true

									!hasSchedule || !hasLocationForCurrentTime
								}.mapNotNull { plugin.npcDataManager.getNPC(it) }
								.toList()

						// Only process candidates that have a chance of moving
						if (candidateNPCs.isNotEmpty()) {
							val randomChance = plugin.config.randomPathingChance

							// Using ThreadLocalRandom instead of Math.random() for better performance
							val random =
								java.util.concurrent.ThreadLocalRandom
									.current()

							if (plugin.config.randomPathingEnabled) {
								// Find candidate NPCs as before

								// Add eligible NPCs to the queue
								for (npc in candidateNPCs) {
									if (random.nextDouble() < randomChance && hasNearbyPlayers(npc)) {
										npcMovementQueue.add(npc)
									}
								}

								// Process a few NPCs from the queue
								val maxProcessPerTick = 3
								for (i in 0 until maxProcessPerTick) {
									if (npcMovementQueue.isEmpty()) break
									val nextNPC = npcMovementQueue.poll()
									moveNPCToRandomSublocation(nextNPC)
								}
							}
						}
					}
				}
			}.runTaskTimer(plugin, 20L, plugin.config.scheduleTaskPeriod * 20L) // Check every minute
	}

	private fun hasNearbyPlayers(npc: NPC): Boolean {
		val radius = plugin.config.rangeBeforeTeleport * 2
		val nearbyPlayers = plugin.getNearbyPlayers(npc, radius, ignoreY = true)
		return nearbyPlayers.isNotEmpty()
	}

	private fun hasNearbyPlayers(location: Location): Boolean {
		val radius = plugin.config.rangeBeforeTeleport * 5
		val nearbyPlayers = plugin.getNearbyPlayers(location, radius, ignoreY = true)
		return nearbyPlayers.isNotEmpty()
	}

	private fun moveNPCToRandomSublocation(npc: NPC) {
		// Early returns for invalid conditions
		val currentLocation = npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location

		if (currentLocation == null) {
			plugin.logger.warning("NPC ${npc.name} has no location, skipping.")
			return
		}

		if (plugin.conversationManager.isInConversation(npc)) return // Don't move NPCs in conversation

		// Get story location and potential sublocations
		val currentStoryLocation = plugin.locationManager.getLocationByPosition(currentLocation)

		// Get candidate sublocations
		val allSublocations =
			when {
				// Use location-specific logic to determine the sublocation pool
				currentStoryLocation?.hasParent() == true -> {
					// Get the upmost parent location recursively
					var tempLocation = currentStoryLocation
					while (tempLocation?.hasParent() == true) {
						tempLocation = plugin.locationManager.getLocation(tempLocation.parentLocationName!!)
					}
					// Use the upmost parent's name to get sublocations
					tempLocation?.let { plugin.locationManager.getSublocations(it.name) } ?: emptyList()
				}
				currentStoryLocation != null ->
					plugin.locationManager.getSublocations(currentStoryLocation.name)
				else ->
					// If no location context, consider ALL sublocations - but prioritize nearby ones
					plugin.locationManager
						.getAllLocations()
						.filter {
							it.isSubLocation &&
								it.bukkitLocation?.world == currentLocation.world &&
								(it.bukkitLocation?.distanceSquared(currentLocation) ?: Double.MAX_VALUE) <=
								plugin.config.rangeBeforeTeleport * plugin.config.rangeBeforeTeleport
						}.sortedBy { it.bukkitLocation?.distanceSquared(currentLocation) ?: Double.MAX_VALUE }
						.take(5) // Limit to closest 5 locations to add variety
			}

		// Filter eligible locations
		var eligibleLocations =
			allSublocations.filter {
				it.bukkitLocation != null &&
					(it.allowedNPCs.isEmpty() || it.allowedNPCs.contains(npc.name))
			}

		if (eligibleLocations.isEmpty()) return

		// Remove the current location from the list (currentStoryLocation)
		eligibleLocations =
			eligibleLocations.filter {
				it != currentStoryLocation
			}

		// If no eligible locations, return
		if (eligibleLocations.isEmpty()) {
			plugin.logger.warning("No eligible sublocations found for ${npc.name}")
			plugin.logger.warning("Current location: ${currentStoryLocation?.name}")
			plugin.logger.warning("All sublocations: ${allSublocations.joinToString(", ") { it.name }}")
			return
		}

		// Use better randomization with weighted selection
		val random =
			java.util.concurrent.ThreadLocalRandom
				.current()

		// Select a random location with different probability for each NPC (use NPC ID as seed)
		val seed = npc.uniqueId.hashCode()
		val randomGenerator = Random(seed + System.currentTimeMillis() / 30000) // Change every 30 seconds
		val randomIndex = randomGenerator.nextInt(eligibleLocations.size)
		val randomSublocation = eligibleLocations[randomIndex]

		val baseLocation = randomSublocation.bukkitLocation!!
		val offset = plugin.config.randomLocationOffset
		// Add larger variance to offsets (up to 5 blocks)
		val randomOffsetX = random.nextDouble(-offset, offset)
		val randomOffsetZ = random.nextDouble(-offset, offset)

		// Create target location with random offsets
		val targetLocation =
			Location(
				baseLocation.world,
				baseLocation.x + randomOffsetX,
				baseLocation.y,
				baseLocation.z + randomOffsetZ,
				random.nextFloat() * 360f, // Random facing direction
				baseLocation.pitch,
			)

		// Find safe ground
		val safeLocation = findNearbyGround(targetLocation, maxBlocksCheck = 3)
		if (safeLocation != null) {
			moveNPCToLocation(npc, safeLocation)
			// plugin.logger.info("Moving ${npc.name} to random sublocation: ${randomSublocation.name}")
		} else {
			moveNPCToLocation(npc, baseLocation)
			// plugin.logger.info("No safe ground found near random position, using base location for ${npc.name}")
		}
	}

	/**
	 * Finds a safe ground position for an NPC to stand on within a limited vertical range
	 * @param location The initial location to check
	 * @param maxBlocksCheck Maximum blocks to check up and down (default: 2)
	 * @return A safe location or null if none found
	 */
	private fun findNearbyGround(
		location: Location,
		maxBlocksCheck: Int = 5, // Increased default to 5 blocks up/down
	): Location? {
		val world = location.world
		val x = location.x
		val z = location.z
		val startY = location.y.toInt()

		// First check the exact position
		val exactBlock = world.getBlockAt(x.toInt(), startY - 1, z.toInt())
		val blockAtFeet = world.getBlockAt(x.toInt(), startY, z.toInt())
		val blockAtHead = world.getBlockAt(x.toInt(), startY + 1, z.toInt())

		// If current position is already valid (solid ground below, space for NPC)
		if (exactBlock.type.isSolid && !blockAtFeet.type.isSolid && !blockAtHead.type.isSolid) {
			return location.clone()
		}

		// Check downward first (more likely to find ground below)
		for (yOffset in 1..maxBlocksCheck) {
			val y = startY - yOffset

			// Don't check below world or beyond our 5-block limit
			if (y <= 0 || yOffset > maxBlocksCheck) continue

			val block = world.getBlockAt(x.toInt(), y - 1, z.toInt())
			val blockAbove = world.getBlockAt(x.toInt(), y, z.toInt())
			val blockAboveTwo = world.getBlockAt(x.toInt(), y + 1, z.toInt())

			// Check if the block is solid with 2 air blocks above (space for NPC)
			if (block.type.isSolid && !blockAbove.type.isSolid && !blockAboveTwo.type.isSolid) {
				return Location(world, x, y.toDouble(), z, location.yaw, location.pitch)
			}
		}

		// Then check upward
		for (yOffset in 1..maxBlocksCheck) {
			val y = startY + yOffset

			// Don't check above world height or beyond our 5-block limit
			if (y >= world.maxHeight - 1 || yOffset > maxBlocksCheck) continue

			val block = world.getBlockAt(x.toInt(), y - 1, z.toInt())
			val blockAbove = world.getBlockAt(x.toInt(), y, z.toInt())
			val blockAboveTwo = world.getBlockAt(x.toInt(), y + 1, z.toInt())

			// Check if the block is solid with 2 air blocks above (space for NPC)
			if (block.type.isSolid && !blockAbove.type.isSolid && !blockAboveTwo.type.isSolid) {
				return Location(world, x, y.toDouble(), z, location.yaw, location.pitch)
			}
		}

		// No safe location found within 5 blocks up or down
		return null
	}

	private fun executeScheduleEntry(
		npcName: String,
		entry: ScheduleEntry,
	) {
		// Get NPC entity through your NPC system
		val npc = plugin.npcDataManager.getNPC(npcName) ?: return
		val npcEntity = npc.entity ?: return

		var isFollowing = false

		// SentinelTrait
		val sentinelTrait = npc.getOrAddTrait(SentinelTrait::class.java)
		isFollowing = sentinelTrait?.guarding != null || npc.getOrAddTrait(FollowTrait::class.java).isActive

		// If the NPC is currently following someone, skip this entry
		if (isFollowing) {
			plugin.logger.info("${npc.name} is following someone, skipping schedule entry.")
			return
		}

		// Handle location movement
		val locationName = entry.locationName
		if (!locationName.isNullOrEmpty()) {
			val location = plugin.locationManager.getLocation(locationName)
			if (location != null) {
				// Check if currently in conversation.
				val isInConversation = plugin.conversationManager.isInConversation(npc)

				// Check if the NPC needs to move
				val shouldMove =
					location.bukkitLocation?.let {
						npcEntity.location.distanceSquared(it) >=
							plugin.config.scheduleDestinationTolerance * plugin.config.scheduleDestinationTolerance
					} ?: false

				if (shouldMove) {
					// if the entry.type is Work, make NPC say goodbye (unless they are already in the location)
					if (entry.action == "work" && isInConversation) {
						val goodbyeContext =
							mutableListOf(
								"\"You have a work to do at ${location.name}. Tell the people in the conversation that you are leaving.\"",
							)
						npc.getOrAddTrait(SentinelTrait::class.java).guarding = null
						npc.getOrAddTrait(FollowTrait::class.java).follow(null)
						plugin.conversationManager.endConversationWithGoodbye(npc, goodbyeContext)
					}

					// Use your existing NPC movement system or teleport WITH callback for action
					val moveCallback =
						Runnable {
							// Execute action after reaching destination
							if (entry.action != null) {
								executeAction(npc, entry.action)
							}

							// Handle dialogue after reaching destination
							if (entry.dialogue != null && entry.dialogue != "") {
								// add some random delay from 1 to 6 seconds
								val randomDelay = (1..6).random() * 20L // Convert to ticks
								Bukkit.getScheduler().runTaskLater(
									plugin,
									Runnable {
										plugin.npcMessageService.broadcastNPCMessage(entry.dialogue, npc, shouldBroadcast = false)
									},
									randomDelay,
								)
							}
						}

					// Use better randomization with weighted selection
					val random =
						java.util.concurrent.ThreadLocalRandom
							.current()

					val baseLocation = location.bukkitLocation!!
					val offset = plugin.config.randomLocationOffset
					// Add larger variance to offsets (up to 5 blocks)
					val randomOffsetX = random.nextDouble(-offset, offset)
					val randomOffsetZ = random.nextDouble(-offset, offset)

					// Create target location with random offsets
					val targetLocation =
						Location(
							baseLocation.world,
							baseLocation.x + randomOffsetX,
							baseLocation.y,
							baseLocation.z + randomOffsetZ,
							random.nextFloat() * 360f, // Random facing direction
							baseLocation.pitch,
						)

					// Find safe ground
					val safeLocation = findNearbyGround(targetLocation, maxBlocksCheck = 3)
					if (safeLocation != null && entry.random) {
						moveNPCToLocation(npc, safeLocation, moveCallback)
						plugin.logger.info("Moving ${npc.name} to scheduled location: ${location.name}")
					} else {
						moveNPCToLocation(npc, baseLocation, moveCallback)
						plugin.logger.info("No safe ground found near scheduled random position, using base location for ${npc.name}")
					}
				} else {
					// plugin.logger.info("${npc.name} is already at ${location.name}, skipping movement.")

					// Execute action since NPC is already at destination
					if (entry.action != null) {
						executeAction(npc, entry.action)
					}

					// Handle dialogue since NPC is already at destination
					if (entry.dialogue != null && entry.dialogue != "") {
						// add some random delay from 1 to 4 seconds
						val randomDelay = (1..6).random() * 20L // Convert to ticks
						Bukkit.getScheduler().runTaskLater(
							plugin,
							Runnable {
								plugin.npcMessageService.broadcastNPCMessage(entry.dialogue, npc, shouldBroadcast = false)
							},
							randomDelay,
						)
					}
				}
			}
		} else {
			// No location change, just execute the action
			if (entry.action != null) {
				executeAction(npc, entry.action)
			}

			// Handle dialogue
			if (entry.dialogue != null && entry.dialogue != "") {
				val randomDelay = (1..6).random() * 20L
				Bukkit.getScheduler().runTaskLater(
					plugin,
					Runnable {
						plugin.npcMessageService.broadcastNPCMessage(entry.dialogue, npc, shouldBroadcast = false)
					},
					randomDelay,
				)
			}
		}
	}

	private fun moveNPCToLocation(
		npc: NPC,
		location: Location,
		callback: Runnable? = null,
	) {
		val range = plugin.config.rangeBeforeTeleport
		if (!npc.isSpawned) {
			plugin.logger.warning("NPC ${npc.name} is not spawned, cannot move.")
			return
		}

		val nearbyPlayers = plugin.getNearbyPlayers(npc, range, ignoreY = true)
		var shouldTeleport = nearbyPlayers.isEmpty()

		if (npc.entity.location.world != location.world) {
			plugin.logger.warning("NPC ${npc.name} is in a different world, cannot move.")
			return
		}

		// If target location has players, do not teleport.
		if (shouldTeleport) {
			val nearbyPlayersInTargetLocation = plugin.getNearbyPlayers(location, range, ignoreY = true)
			if (nearbyPlayersInTargetLocation.isNotEmpty()) {
				shouldTeleport = false
			}
		}

		// Set NPC pose to standing
		npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
		npc.getOrAddTrait(SitTrait::class.java).setSitting(null)

		if (shouldTeleport) {
			npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
			// plugin.logger.info("Teleporting NPC ${npc.name} to $location")
			callback?.run() // Execute callback after teleporting
		} else {
			// plugin.logger.info("Walking NPC ${npc.name} to $location")
			// Pass the callback to the walkToLocation method
			val teleportOnFail =
				Runnable {
					npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
					callback?.run()
				}
			plugin.npcManager.walkToLocation(npc, location, .5, 1f, 30, callback, teleportOnFail)
		}
	}

	private fun moveNPCToLocation(
		npc: NPC,
		location: StoryLocation,
		callback: Runnable? = null,
	) {
		npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
		npc.getOrAddTrait(SitTrait::class.java).setSitting(null)

		val bukkitLocation = location.bukkitLocation
		if (bukkitLocation == null) {
			plugin.logger.warning("No Bukkit location found for ${location.name}")
			return
		}
		moveNPCToLocation(npc, bukkitLocation, callback)
	}

	private fun executeAction(
		npc: NPC,
		action: String,
	) {
		// Implement actions like sitting, working, etc.
		when (action.lowercase()) {
			"sit" -> {
				// Make NPC sit
				npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
				npc.getOrAddTrait(SitTrait::class.java).setSitting(null)
				npc.getOrAddTrait(SitTrait::class.java).setSitting(npc.entity?.location)
			}
			"work" -> {
				// Make NPC perform work animation
				npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
				npc.getOrAddTrait(SitTrait::class.java).setSitting(null)
			}
			"sleep" -> {
				// Make NPC sleep
				npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.SLEEPING
			}
			"idle" -> {
				// Default idle behavior
				npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
				npc.getOrAddTrait(SitTrait::class.java).setSitting(null)
			}
			else -> {
				plugin.logger.warning("Unknown action: $action for NPC: ${npc.name}")
			}
		}
	}

	fun shutdown() {
		scheduleTask?.cancel()
	}

	class NPCSchedule(
		val npcName: String,
	) {
		val entries: MutableList<ScheduleEntry> = ArrayList()

		fun addEntry(entry: ScheduleEntry) {
			entries.add(entry)
			// Sort entries by time
			entries.sortBy { it.time }
		}

		fun getEntryForTime(currentHour: Int): ScheduleEntry? {
			// Find the entry with the closest time <= current hour
			var bestEntry: ScheduleEntry? = null
			var bestTimeDiff = 24 // Maximum possible difference

			for (entry in entries) {
				val entryTime = entry.time

				// Check if this entry is applicable for current time
				if (entryTime <= currentHour) {
					val diff = currentHour - entryTime
					if (diff < bestTimeDiff) {
						bestTimeDiff = diff
						bestEntry = entry
					}
				}
			}

			// If no entry found before current hour, use the last one (evening)
			if (bestEntry == null && entries.isNotEmpty()) {
				return entries[entries.size - 1]
			}

			return bestEntry
		}
	}

	class ScheduleEntry(
		val time: Int, // Hour (0-23)
		var locationName: String?,
		val action: String?,
		val dialogue: String?,
		val random: Boolean = false,
	)

	companion object {
		private var instance: NPCScheduleManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): NPCScheduleManager = instance ?: NPCScheduleManager(plugin).also { instance = it }
	}
}
