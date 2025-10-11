package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.duty.DutyLibrary
import com.canefe.story.npc.duty.DutyLoopRunner
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
import kotlin.math.sqrt

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

    private val npcMovementQueue = LinkedHashSet<NPC>()

    // Track when NPCs were last processed for random pathing to prevent repeated selection
    private val npcRandomPathingCooldowns = ConcurrentHashMap<String, Long>()

    // Track when NPCs were last barked dialogue to prevent repeated dialogue
    private val npcLastDialogueTimes = ConcurrentHashMap<String, Long>()

    // Track location occupancy for actions like sitting, sleeping, etc.
    private val locationOccupancy = ConcurrentHashMap<String, OccupancyInfo>()

    // Track last reload time to prevent scheduler from running immediately after reload
    private var lastReloadTime = 0L

    init {
        loadAllSchedules()
        startScheduleRunner()
    }

    fun reloadSchedules() {
        // Stop the current task and wait for it to finish
        scheduleTask?.cancel()
        scheduleTask = null

        // Clear the movement queue to prevent accumulation
        npcMovementQueue.clear()

        // Clear cooldowns to prevent stale data
        npcRandomPathingCooldowns.clear()

        // Clear cooldowns for bark dialogue
        npcLastDialogueTimes.clear()

        // Set reload timestamp
        lastReloadTime = System.currentTimeMillis()

        // Reload all schedules
        loadAllSchedules()

        // Add a small delay before restarting to allow server to settle
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (plugin.isEnabled) {
                    startScheduleRunner()
                }
            },
            40L,
        ) // 2 second delay
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
        var scheduleFile = File(scheduleFolder, "$npcName.yml")
        if (!scheduleFile.exists()) {
            // also try lowercase
            scheduleFile = File(scheduleFolder, "${npcName.lowercase()}.yml")
            if (!scheduleFile.exists()) {
                return null
            }
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
                val random = config.getBoolean("schedule.$timeKey.random", false)

                // NEW: Load duty field
                val duty = config.getString("schedule.$timeKey.duty")

                // Handle dialogue as a list or convert single string to list
                val dialogue =
                    if (config.isList("schedule.$timeKey.dialogue")) {
                        config.getStringList("schedule.$timeKey.dialogue")
                    } else {
                        val singleDialogue = config.getString("schedule.$timeKey.dialogue")
                        if (singleDialogue.isNullOrEmpty()) null else listOf(singleDialogue)
                    }

                val entry = ScheduleEntry(time, locationName, action, dialogue, random, duty)
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

        for (hour in listOf(6, 12, 18, 22, 24)) {
            schedule.addEntry(ScheduleEntry(hour, "", "idle", emptyList(), false))
        }
        return schedule
    }

    fun getSchedule(npcName: String): NPCSchedule? = schedules[npcName.lowercase()]

    private fun startScheduleRunner() {
        // Stop any existing task
        scheduleTask?.cancel()
        scheduleTask = null

        // Clear the movement queue to prevent accumulation from previous runs
        npcMovementQueue.clear()

        // Run every minute to check for schedule updates
        scheduleTask =
            object : BukkitRunnable() {
                override fun run() {
                    // Double-check if plugin is still enabled to prevent running during reload
                    if (!plugin.isEnabled) {
                        cancel()
                        return
                    }

                    // Skip processing if reloaded recently (within 3 seconds) to allow server to settle
                    val timeSinceReload = System.currentTimeMillis() - lastReloadTime
                    if (timeSinceReload < 3000) {
                        if (plugin.config.debugMessages) {
                            plugin.logger.info(
                                "Skipping schedule processing - recently reloaded (${timeSinceReload}ms ago)",
                            )
                        }
                        return
                    }

                    // if no players are online, skip the schedule check
                    if (plugin.server.onlinePlayers.isEmpty()) {
                        return
                    }

                    // if both random pathing and schedule are disabled, skip the check
                    if (!plugin.config.randomPathingEnabled && !plugin.config.scheduleEnabled) {
                        return
                    }

                    val debugMessages = plugin.config.debugMessages

                    // Convert to 24-hour format (0-23)
                    val hour = plugin.timeService.getHours()

                    // Get all NPCs near active players to avoid checking irrelevant NPCs
                    val nearbyNPCs = getNearbyNPCsToActivePlayers()

                    if (debugMessages) {
                        plugin.logger.info("Found ${nearbyNPCs.size} NPCs near active players at hour $hour.")
                    }

                    // Handle NPCs with schedules
                    if (plugin.config.scheduleEnabled) {
                        for (npc in nearbyNPCs) {
                            val schedule = schedules[npc.name.lowercase()]
                            if (schedule != null) {
                                val currentEntry = schedule.getEntryForTime(hour)
                                if (currentEntry != null) {
                                    executeScheduleEntry(schedule.npcName.lowercase(), currentEntry)
                                }
                            }
                        }
                    }

                    // Handle NPCs without schedules or with empty location entries for random pathing
                    if (plugin.config.randomPathingEnabled) {
                        // Filter nearby NPCs that are candidates for random movement
                        val candidateNPCs =
                            nearbyNPCs
                                .filter { npc ->
                                    val currentLocation =
                                        npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location

                                    if (currentLocation == null) {
                                        if (debugMessages) {
                                            plugin.logger.warning("NPC ${npc.name} has no location, skipping.")
                                        }
                                        return@filter false
                                    }

                                    if (plugin.npcManager.isNPCDisabled(npc)) {
                                        if (debugMessages) {
                                            plugin.logger.info("NPC ${npc.name} is disabled, skipping random pathing.")
                                        }
                                        return@filter false
                                    }

                                    // check if random pathing is enabled for this NPC
                                    val npcData = plugin.npcDataManager.getNPCData(npc.name)

                                    if (npcData?.randomPathing == false) {
                                        if (debugMessages) {
                                            plugin.logger.info("NPC ${npc.name} has random pathing disabled, skipping.")
                                        }
                                        return@filter false
                                    }

                                    // Check if NPC is still in cooldown from previous random pathing
                                    val lastProcessedTime = npcRandomPathingCooldowns[npc.name.lowercase()]
                                    if (lastProcessedTime != null) {
                                        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
                                        val timeSinceLastProcessed = System.currentTimeMillis() - lastProcessedTime
                                        if (timeSinceLastProcessed < cooldownMs) {
                                            if (debugMessages) {
                                                val remainingSeconds = (cooldownMs - timeSinceLastProcessed) / 1000
                                                plugin.logger.info(
                                                    "NPC ${npc.name} is in cooldown for ${remainingSeconds}s, skipping.",
                                                )
                                            }
                                            return@filter false
                                        }
                                    }

                                    // Check schedule status
                                    val hasSchedule = getSchedule(npc.name) != null
                                    val hasLocationForCurrentTime =
                                        hasSchedule &&
                                            schedules[npc.name.lowercase()]
                                                ?.getEntryForTime(
                                                    hour,
                                                )?.locationName
                                                ?.isNotEmpty() ==
                                            true
                                    !hasSchedule || !hasLocationForCurrentTime
                                }.toSet() // Convert to Set to prevent duplicates

                        if (debugMessages) {
                            plugin.logger.info(
                                "Found ${candidateNPCs.size} candidate NPCs for random pathing at hour $hour.",
                            )
                            plugin.logger.info(
                                "Candidate NPCs: ${candidateNPCs.joinToString(", ") { it.name }}",
                            )
                            // Show cooldown status
                            val cooldownCount =
                                nearbyNPCs.count { npc ->
                                    val lastProcessedTime = npcRandomPathingCooldowns[npc.name.lowercase()]
                                    if (lastProcessedTime != null) {
                                        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
                                        System.currentTimeMillis() - lastProcessedTime < cooldownMs
                                    } else {
                                        false
                                    }
                                }
                            if (cooldownCount > 0) {
                                plugin.logger.info("$cooldownCount NPCs are currently in random pathing cooldown")
                            }
                        }

                        // Only process candidates that have a chance of moving
                        if (candidateNPCs.isNotEmpty()) {
                            // Clean up expired cooldowns to prevent memory leaks
                            cleanupExpiredCooldowns()
                            cleanupExpiredDialogueCooldowns()

                            val randomChance = plugin.config.randomPathingChance

                            if (plugin.config.randomPathingEnabled) {
                                // Fair selection: ensure all NPCs get equal opportunity over time
                                val candidateList = candidateNPCs.toList()
                                val numToSelect = Math.max(1, (candidateList.size * randomChance).toInt())

                                // Shuffle the list to ensure randomness but fair distribution
                                val shuffledCandidates = candidateList.shuffled()

                                // Add the first N NPCs to the queue (where N is based on randomChance)
                                for (i in 0 until Math.min(numToSelect, shuffledCandidates.size)) {
                                    val npc = shuffledCandidates[i]
                                    npcMovementQueue.add(npc)
                                    if (debugMessages) {
                                        plugin.logger.info(
                                            "Added ${npc.name} to movement queue (${i + 1}/$numToSelect)",
                                        )
                                    }
                                }

                                // Process a few NPCs from the queue
                                // Process NPCs from the queue with staggered timing to prevent race conditions
                                val maxProcessPerTick = plugin.config.maxProcessPerTick
                                var processed = 0
                                val queueSnapshot = npcMovementQueue.toList() // Create a snapshot to avoid concurrent modification
                                npcMovementQueue.clear() // Clear the queue before processing

                                for (npc in queueSnapshot) {
                                    if (processed >= maxProcessPerTick) {
                                        // Add remaining NPCs back to queue for next tick
                                        for (remainingIndex in (processed until queueSnapshot.size)) {
                                            npcMovementQueue.add(queueSnapshot[remainingIndex])
                                        }
                                        break
                                    }

                                    // Stagger NPC processing with small delays to prevent race conditions
                                    val delayTicks = processed * 2L // 0, 2, 4 ticks (0, 0.1, 0.2 seconds)

                                    if (delayTicks == 0L) {
                                        // Process first NPC immediately
                                        moveNPCToRandomSublocation(npc)
                                        // Update cooldown timestamp
                                        npcRandomPathingCooldowns[npc.name.lowercase()] = System.currentTimeMillis()
                                        if (debugMessages) {
                                            plugin.logger.info(
                                                "Processing NPC ${npc.name} from queue immediately (${processed + 1}/$maxProcessPerTick)",
                                            )
                                        }
                                    } else {
                                        // Delay other NPCs slightly
                                        Bukkit.getScheduler().runTaskLater(
                                            plugin,
                                            Runnable {
                                                moveNPCToRandomSublocation(npc)
                                                // Update cooldown timestamp
                                                npcRandomPathingCooldowns[npc.name.lowercase()] =
                                                    System.currentTimeMillis()
                                                if (debugMessages) {
                                                    plugin.logger.info(
                                                        "Processing NPC ${npc.name} from queue with $delayTicks tick delay",
                                                    )
                                                }
                                            },
                                            delayTicks,
                                        )
                                    }
                                    processed++
                                }

                                if (debugMessages && processed > 0) {
                                    plugin.logger.info("Processed $processed NPCs from movement queue")
                                }
                            }
                        }
                    }
                }
            }.runTaskTimer(plugin, 20L, plugin.config.scheduleTaskPeriod * 20L) // Check every minute
    }

    /**
     * Checks if an NPC is in dialogue cooldown
     * @param npcName The name of the NPC to check
     * @return true if the NPC is in cooldown, false otherwise
     */
    private fun isNPCInDialogueCooldown(npcName: String): Boolean {
        val lastDialogueTime = npcLastDialogueTimes[npcName.lowercase()]
        if (lastDialogueTime == null) return false

        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L
        val timeSinceLastDialogue = System.currentTimeMillis() - lastDialogueTime
        return timeSinceLastDialogue < cooldownMs
    }

    /**
     * Updates the last dialogue time for an NPC
     * @param npcName The name of the NPC
     */
    private fun updateNPCDialogueTime(npcName: String) {
        npcLastDialogueTimes[npcName.lowercase()] = System.currentTimeMillis()
    }

    /**
     * Gets the remaining dialogue cooldown time for an NPC in seconds
     * @param npcName The name of the NPC
     * @return Remaining cooldown in seconds, or 0 if not in cooldown
     */
    fun getDialogueCooldownRemaining(npcName: String): Int {
        val lastDialogueTime = npcLastDialogueTimes[npcName.lowercase()] ?: return 0
        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L
        val timeSinceLastDialogue = System.currentTimeMillis() - lastDialogueTime
        val remainingMs = cooldownMs - timeSinceLastDialogue

        return if (remainingMs > 0) (remainingMs / 1000).toInt() else 0
    }

    /**
     * Manually clears the dialogue cooldown for a specific NPC
     */
    fun clearDialogueCooldown(npcName: String) {
        npcLastDialogueTimes.remove(npcName.lowercase())
        if (plugin.config.debugMessages) {
            plugin.logger.info("Cleared dialogue cooldown for NPC: $npcName")
        }
    }

    /**
     * Cleans up expired dialogue cooldown entries to prevent memory leaks
     */
    private fun cleanupExpiredDialogueCooldowns() {
        val currentTime = System.currentTimeMillis()
        val cooldownMs = plugin.config.scheduleDialogueCooldown * 1000L

        val expiredEntries =
            npcLastDialogueTimes.entries.filter { (_, timestamp) ->
                currentTime - timestamp > cooldownMs * 2 // Remove entries that are more than 2x the cooldown period old
            }

        expiredEntries.forEach { (npcName, _) ->
            npcLastDialogueTimes.remove(npcName)
        }

        if (expiredEntries.isNotEmpty() && plugin.config.debugMessages) {
            plugin.logger.info("Cleaned up ${expiredEntries.size} expired dialogue cooldowns")
        }
    }

    /**
     * Clears all dialogue cooldowns
     */
    fun clearAllDialogueCooldowns() {
        val clearedCount = npcLastDialogueTimes.size
        npcLastDialogueTimes.clear()
    }

    /**
     * Gets all NPCs that are near active players to optimize processing
     * @return List of NPCs that are within range of at least one online player
     */
    private fun getNearbyNPCsToActivePlayers(): List<NPC> {
        val nearbyNPCs = mutableSetOf<NPC>()
        val checkRadius = plugin.config.rangeBeforeTeleport * 2.0 // Use a larger radius for proximity checks

        for (player in plugin.server.onlinePlayers) {
            // Get NPCs near this player
            val playerNearbyNPCs = plugin.npcUtils.getNearbyNPCs(player, checkRadius)
            nearbyNPCs.addAll(playerNearbyNPCs)
        }

        return nearbyNPCs.toList()
    }

    private fun hasNearbyPlayers(npc: NPC): Boolean {
        val radius = plugin.config.rangeBeforeTeleport * 2
        val nearbyPlayers = plugin.npcUtils.getNearbyPlayers(npc, radius, ignoreY = true)
        return nearbyPlayers.isNotEmpty()
    }

    private fun hasNearbyPlayers(location: Location): Boolean {
        val radius = plugin.config.rangeBeforeTeleport * 5
        val nearbyPlayers = plugin.npcUtils.getNearbyPlayers(location, radius, ignoreY = true)
        return nearbyPlayers.isNotEmpty()
    }

    private fun moveNPCToRandomSublocation(npc: NPC) {
        val debugMessages = plugin.config.debugMessages

        if (debugMessages) {
            plugin.logger.info("Starting moveNPCToRandomSublocation for ${npc.name}")
        }

        // Early returns for invalid conditions
        val currentLocation = npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location

        if (currentLocation == null) {
            if (debugMessages) {
                plugin.logger.info("NPC ${npc.name} has no current location, skipping random movement.")
            }
            return
        }

        if (plugin.conversationManager.isInConversation(npc)) {
            if (debugMessages) {
                plugin.logger.info("NPC ${npc.name} is in conversation, skipping random movement.")
            }
            return // Don't move NPCs in conversation
        }

        // Get story location and potential sublocations
        val currentStoryLocation = plugin.locationManager.getLocationByPosition(currentLocation, 200.0)

        // return if no story location is found
        if (currentStoryLocation == null) {
            if (debugMessages) {
                plugin.logger.info("NPC ${npc.name} is not in a valid story location, skipping.")
            }
            return
        }

        if (debugMessages) {
            plugin.logger.info("NPC ${npc.name} is at story location: ${currentStoryLocation.name}")
        }

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

        if (debugMessages) {
            plugin.logger.info("All sublocations for ${npc.name}: ${eligibleLocations.joinToString(", ") { it.name }}")
        }

        // Use better randomization with weighted selection
        val random =
            java.util.concurrent.ThreadLocalRandom
                .current()

        // Select a random location with proper synchronization to prevent race conditions
        val randomSublocation =
            synchronized(locationOccupancy) {
                // Filter out locations that are occupied by actions that would conflict inside the synchronized block
                val currentlyEligibleLocations =
                    eligibleLocations.filter { location ->
                        val action = location.randomPathingAction
                        if (action != null && (action.lowercase() == "sit" || action.lowercase() == "sleep")) {
                            val isOccupied = isLocationOccupied(location, action)
                            if (debugMessages) {
                                plugin.logger.info(
                                    "SYNC: Checking location ${location.name} for ${npc.name}: occupied = $isOccupied",
                                )
                                if (isOccupied) {
                                    val occupancy = locationOccupancy["${location.name}:$action"]
                                    plugin.logger.info(
                                        "SYNC: Location ${location.name} is occupied by ${occupancy?.npcName}",
                                    )
                                }
                            }
                            !isOccupied
                        } else {
                            // Location has no conflicting action or action doesn't require occupancy tracking
                            true
                        }
                    }

                if (debugMessages) {
                    plugin.logger.info(
                        "SYNC: Currently eligible locations for ${npc.name}: ${
                            currentlyEligibleLocations.joinToString(
                                ", ",
                            ) {
                                it.name
                            }
                        }",
                    )
                }

                if (currentlyEligibleLocations.isEmpty()) {
                    if (debugMessages) {
                        plugin.logger.info("SYNC: No eligible locations available for ${npc.name}")
                    }
                    null // Return null instead of exiting the function
                } else {
                    // Select a random location from the currently available ones
                    val seed = npc.uniqueId.hashCode()
                    val randomGenerator = Random(seed + System.currentTimeMillis() / 30000)
                    val randomIndex = randomGenerator.nextInt(currentlyEligibleLocations.size)
                    val selectedLocation = currentlyEligibleLocations[randomIndex]

                    // Immediately mark the location as occupied while still in the synchronized block
                    val locationAction = selectedLocation.randomPathingAction
                    if (locationAction != null &&
                        (locationAction.lowercase() == "sit" || locationAction.lowercase() == "sleep")
                    ) {
                        markLocationOccupied(selectedLocation, npc.name, locationAction)
                        if (debugMessages) {
                            plugin.logger.info(
                                "SYNC: Pre-marked location '${selectedLocation.name}' as occupied by ${npc.name} for action '$locationAction'",
                            )
                        }
                    }

                    selectedLocation // Return the selected location
                }
            }

        // Handle the case where no location was available
        if (randomSublocation == null) {
            plugin.logger.warning("No eligible sublocations found for ${npc.name}")
            plugin.logger.warning("Current location: ${currentStoryLocation?.name}")
            plugin.logger.warning("All sublocations: ${eligibleLocations.joinToString(", ") { it.name }}")
            return
        }

        val baseLocation = randomSublocation.bukkitLocation!!

        // Check if this location requires precise positioning (no random offset)
        val requiresPrecisePositioning = randomSublocation.randomPathingAction?.lowercase() in listOf("sit", "sleep")

        val targetLocation =
            if (requiresPrecisePositioning) {
                // Use exact location for sitting/sleeping positions
                baseLocation.clone()
            } else {
                // Apply random offsets for other locations
                val offset = plugin.config.randomLocationOffset
                val random =
                    java.util.concurrent.ThreadLocalRandom
                        .current()
                val randomOffsetX = random.nextDouble(-offset, offset)
                val randomOffsetZ = random.nextDouble(-offset, offset)

                Location(
                    baseLocation.world,
                    baseLocation.x + randomOffsetX,
                    baseLocation.y,
                    baseLocation.z + randomOffsetZ,
                    random.nextFloat() * 360f, // Random facing direction
                    baseLocation.pitch,
                )
            }

        // Find safe ground
        val safeLocation = findNearbyGround(targetLocation, maxBlocksCheck = 3)
        if (safeLocation != null) {
            moveNPCToLocation(npc, safeLocation)
            if (plugin.config.debugMessages) {
                plugin.logger.info("Moving ${npc.name} to random sublocation: ${randomSublocation.name}")
            }
        } else {
            moveNPCToLocation(npc, baseLocation)
            // plugin.logger.info("No safe ground found near random position, using base location for ${npc.name}")
        }

        // Execute the location's random pathing action if specified
        randomSublocation.randomPathingAction?.let { action ->
            // Add a small delay to ensure the NPC has moved to the location first
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    // Only execute if we are at the correct location
                    val npcLocation = npc.entity?.location ?: return@Runnable
                    val distanceToTarget = npcLocation.distanceSquared(randomSublocation.bukkitLocation!!)
                    val tolerance = 4.0 // 2 blocks tolerance
                    if (distanceToTarget > tolerance) {
                        if (plugin.config.debugMessages) {
                            plugin.logger.info(
                                "Skipping action '$action' for ${npc.name} - not at target location (distance: ${sqrt(
                                    distanceToTarget,
                                )})",
                            )
                        }
                        return@Runnable
                    }
                    npc.teleport(randomSublocation.bukkitLocation!!, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    executeAction(npc, action, randomSublocation)
                    if (plugin.config.debugMessages) {
                        plugin.logger.info(
                            "Executed random pathing action '$action' for ${npc.name} at ${randomSublocation.name}",
                        )
                    }
                },
                20L, // 1 second delay
            )
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

        // Get duty loop runner instance
        val dutyLoopRunner = DutyLoopRunner.getInstance(plugin)
        val dutyLibrary = DutyLibrary.getInstance(plugin)

        // Helper function to select random dialogue
        fun selectRandomDialogue(): String? =
            if (entry.dialogue.isNullOrEmpty()) {
                null
            } else {
                // Pick a random dialogue from the list
                entry.dialogue.random()
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
                    location.bukkitLocation?.let { mainLocation ->
                        val npcLocation = npcEntity.location
                        val distanceToMain = npcLocation.distanceSquared(mainLocation)
                        val tolerance =
                            plugin.config.scheduleDestinationTolerance * plugin.config.scheduleDestinationTolerance

                        // If NPC is close to main location, they don't need to move
                        if (distanceToMain <= tolerance) {
                            if (plugin.config.debugMessages) {
                                plugin.logger.info(
                                    "${npc.name} is already at main location ${location.name}, no movement needed",
                                )
                            }
                            false
                        } else {
                            // Check if NPC is at any workstation within this location
                            val dutyData = dutyLibrary.loadLocationDutyData(location)

                            if (plugin.config.debugMessages) {
                                plugin.logger.info(
                                    "Checking workstations for ${npc.name} at ${location.name}. Found ${dutyData.workstations.size} workstations",
                                )
                            }

                            val isAtWorkstation =
                                dutyData.workstations.values.any { workstation ->
                                    val workstationLocation =
                                        location.bukkitLocation?.world?.let { world ->
                                            Location(world, workstation.x, workstation.y, workstation.z)
                                        }
                                    val isAtThisWorkstation =
                                        workstationLocation?.let { wsLoc ->
                                            val distanceToWorkstation = npcLocation.distanceSquared(wsLoc)
                                            val atWorkstation = distanceToWorkstation <= tolerance

                                            if (plugin.config.debugMessages && atWorkstation) {
                                                plugin.logger.info(
                                                    "${npc.name} is at workstation '${workstation.name}' (distance: ${
                                                        sqrt(
                                                            distanceToWorkstation,
                                                        )
                                                    })",
                                                )
                                            }

                                            atWorkstation
                                        } ?: false

                                    isAtThisWorkstation
                                }

                            if (plugin.config.debugMessages) {
                                if (isAtWorkstation) {
                                    plugin.logger.info(
                                        "${npc.name} is at a workstation in ${location.name}, no movement needed",
                                    )
                                } else {
                                    plugin.logger.info(
                                        "${npc.name} is not at any workstation in ${location.name}, movement required",
                                    )
                                }
                            }

                            // Only move if not at main location AND not at any workstation
                            !isAtWorkstation
                        }
                    } ?: false

                if (shouldMove) {
                    // Stop any existing duty loop while moving
                    dutyLoopRunner.stop(npc)

                    // if the entry.type is Work, make NPC say goodbye (unless they are already in the location)
                    if ((entry.action == "work" || entry.duty != null) && isInConversation) {
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
                            // After reaching destination, handle duty or action
                            handleDestinationArrival(npc, entry, location, dutyLibrary, dutyLoopRunner)

                            // Handle dialogue after reaching destination
                            if (!entry.dialogue.isNullOrEmpty()) {
                                // Check if conversation, if so, do not speak
                                if (plugin.conversationManager.isInConversation(npc)) {
                                    return@Runnable
                                }

                                // Check dialogue cooldown
                                if (isNPCInDialogueCooldown(npc.name)) {
                                    if (plugin.config.debugMessages) {
                                        val remainingSeconds = getDialogueCooldownRemaining(npc.name)
                                        plugin.logger.info(
                                            "${npc.name} is in dialogue cooldown for ${remainingSeconds}s, skipping dialogue",
                                        )
                                    }
                                    return@Runnable
                                }

                                // Select a random dialogue from the list
                                val randomDialogue = selectRandomDialogue()
                                updateNPCDialogueTime(npcName)
                                // add some random delay from 1 to 6 seconds
                                val randomDelay = (1..6).random() * 20L // Convert to ticks
                                Bukkit.getScheduler().runTaskLater(
                                    plugin,
                                    Runnable {
                                        if (randomDialogue != null) {
                                            plugin.npcMessageService.broadcastNPCMessage(
                                                randomDialogue,
                                                npc,
                                                shouldBroadcast = false,
                                            )
                                        }
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
                    // Check if this location requires precise positioning (no random offset)
                    val requiresPrecisePositioning = location.randomPathingAction?.lowercase() in listOf("sit", "sleep")

                    val targetLocation =
                        if (requiresPrecisePositioning) {
                            // Use exact location for sitting/sleeping positions
                            baseLocation.clone()
                        } else {
                            // Apply random offsets for other locations
                            val offset = plugin.config.randomLocationOffset
                            val random =
                                java.util.concurrent.ThreadLocalRandom
                                    .current()
                            val randomOffsetX = random.nextDouble(-offset, offset)
                            val randomOffsetZ = random.nextDouble(-offset, offset)

                            Location(
                                baseLocation.world,
                                baseLocation.x + randomOffsetX,
                                baseLocation.y,
                                baseLocation.z + randomOffsetZ,
                                random.nextFloat() * 360f, // Random facing direction
                                baseLocation.pitch,
                            )
                        }

                    // Find safe ground
                    val safeLocation = findNearbyGround(targetLocation, maxBlocksCheck = 3)
                    if (safeLocation != null && entry.random) {
                        moveNPCToLocation(npc, safeLocation, moveCallback)
                        plugin.logger.info("Moving ${npc.name} to scheduled location: ${location.name}")
                    } else {
                        moveNPCToLocation(npc, baseLocation, moveCallback)
                    }
                } else {
                    // NPC is already at destination - handle duty or action immediately
                    handleDestinationArrival(npc, entry, location, dutyLibrary, dutyLoopRunner)

                    // Handle dialogue since NPC is already at destination
                    if (!entry.dialogue.isNullOrEmpty()) {
                        // Check if conversation, if so, do not speak
                        if (plugin.conversationManager.isInConversation(npc)) {
                            return
                        }

                        // Check dialogue cooldown
                        if (isNPCInDialogueCooldown(npc.name)) {
                            if (plugin.config.debugMessages) {
                                val remainingSeconds = getDialogueCooldownRemaining(npc.name)
                                plugin.logger.info(
                                    "${npc.name} is in dialogue cooldown for ${remainingSeconds}s, skipping dialogue",
                                )
                            }
                            return
                        }

                        // Select a random dialogue
                        val randomDialogue = selectRandomDialogue()
                        updateNPCDialogueTime(npcName)
                        // add some random delay from 1 to 6 seconds
                        val randomDelay = (1..6).random() * 20L // Convert to ticks
                        Bukkit.getScheduler().runTaskLater(
                            plugin,
                            Runnable {
                                if (randomDialogue != null) {
                                    plugin.npcMessageService.broadcastNPCMessage(
                                        randomDialogue,
                                        npc,
                                        shouldBroadcast = false,
                                    )
                                }
                            },
                            randomDelay,
                        )
                    }
                }
            }
        } else {
            // Check if conversation, if so, do not speak
            if (plugin.conversationManager.isInConversation(npc)) {
                return
            }

            // Check dialogue cooldown
            if (isNPCInDialogueCooldown(npc.name)) {
                if (plugin.config.debugMessages) {
                    val remainingSeconds = getDialogueCooldownRemaining(npc.name)
                    plugin.logger.info(
                        "${npc.name} is in dialogue cooldown for ${remainingSeconds}s, skipping dialogue",
                    )
                }
                return
            }

            // Select a random dialogue from the entry
            val randomDialogue = selectRandomDialogue()

            // Handle dialogue
            if (randomDialogue != null && !plugin.conversationManager.isInConversation(npc)) {
                updateNPCDialogueTime(npcName)
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

    /**
     * Handle what happens when an NPC arrives at their scheduled destination
     */
    private fun handleDestinationArrival(
        npc: NPC,
        entry: ScheduleEntry,
        location: StoryLocation,
        dutyLibrary: DutyLibrary,
        dutyLoopRunner: DutyLoopRunner,
    ) {
        // Unless it's non precise location
        if (!entry.random) {
            // teleport npc to absolute location just in case
            npc.teleport(location.bukkitLocation!!, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
        // Determine what duty to start (if any)
        val dutyToStart =
            when {
                // Priority 1: Explicit duty specified in schedule entry
                entry.duty != null -> entry.duty

                // Priority 2: Action is "work" and location has a default duty
                entry.action == "work" -> dutyLibrary.getDefaultDuty(location)

                // Priority 3: No duty
                else -> null
            }

        if (dutyToStart != null) {
            // Try to start the duty loop
            val dutyScript = dutyLibrary.getDutyScript(location, dutyToStart)
            if (dutyScript != null) {
                dutyLoopRunner.start(npc, dutyScript, location)
                if (plugin.config.debugMessages) {
                    plugin.logger.info("Started duty '$dutyToStart' for ${npc.name} at ${location.name}")
                }
            } else {
                plugin.logger.warning("Duty script '$dutyToStart' not found for location ${location.name}")
                // Fallback to basic action
                if (entry.action != null) {
                    executeAction(npc, entry.action)
                }
            }
        } else {
            // Stop any existing duty loop
            dutyLoopRunner.stop(npc)

            // Execute basic action if specified
            if (entry.action != null) {
                executeAction(npc, entry.action)
            }
        }
    }

    private fun moveNPCToLocation(
        npc: NPC,
        location: Location,
        callback: Runnable? = null,
    ) {
        val range = plugin.config.rangeBeforeTeleport

        val nearbyPlayers = plugin.npcUtils.getNearbyPlayers(npc, range, ignoreY = true)
        var shouldTeleport = nearbyPlayers.isEmpty()

        val npcLocation = npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location

        if (npcLocation.world != location.world) {
            plugin.logger.warning("NPC ${npc.name} is in a different world, cannot move.")
            return
        }

        val debugMessages = plugin.config.debugMessages

        if (debugMessages) {
            plugin.logger.info("Moving NPC ${npc.name} to $location")
            plugin.logger.info("Nearby players: ${nearbyPlayers.joinToString(", ") { it.name }}")
            plugin.logger.info("Initial shouldTeleport: $shouldTeleport")
        }

        // If target location has players, do not teleport.
        if (shouldTeleport) {
            val nearbyPlayersInTargetLocation = plugin.npcUtils.getNearbyPlayers(location, range, ignoreY = true)
            if (nearbyPlayersInTargetLocation.isNotEmpty()) {
                shouldTeleport = false
                if (debugMessages) {
                    plugin.logger.info(
                        "Target location has nearby players: ${
                            nearbyPlayersInTargetLocation.joinToString(
                                ", ",
                            ) { it.name }
                        }, switching to walking",
                    )
                }
            }
        }

        // Set NPC pose to standing
        npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
        npc.getOrAddTrait(SitTrait::class.java).setSitting(null)

        if (shouldTeleport) {
            if (debugMessages) {
                plugin.logger.info("Teleporting ${npc.name} to $location")
            }
            npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
            callback?.run() // Execute callback after teleporting
        } else {
            if (debugMessages) {
                plugin.logger.info("Walking ${npc.name} to $location")
            }
            // Pass the callback to the walkToLocation method
            val teleportOnFail =
                Runnable {
                    val teleportEnabled = plugin.config.teleportOnFail
                    if (teleportEnabled) {
                        if (debugMessages) {
                            plugin.logger.info("Walking failed for ${npc.name}, executing teleportOnFail callback")
                        }
                        npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        callback?.run()
                    }
                }
            plugin.npcManager.walkToLocation(npc, location, 1.0, 1f, 120, callback, teleportOnFail)
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
        location: StoryLocation? = null,
    ) {
        val entityPoseTrait = npc.getOrAddTrait(EntityPoseTrait::class.java)
        val sitTrait = npc.getOrAddTrait(SitTrait::class.java)

        // Clear any existing occupancy for this NPC EXCEPT for the location they're about to occupy
        location?.let { targetLocation ->
            val targetLocationKey = "${targetLocation.name}:$action"
            clearNPCOccupancyExcept(npc.name, targetLocationKey)
        } ?: clearNPCOccupancy(npc.name) // If no target location, clear all

        // Implement actions like sitting, working, etc.
        when (action.lowercase()) {
            "sit" -> {
                if (!sitTrait.isSitting) {
                    sitTrait.setSitting(npc.entity.location)
                    // Only mark location as occupied if it's not already occupied by this NPC
                    location?.let { loc ->
                        val locationKey = "${loc.name}:$action"
                        val currentOccupancy = locationOccupancy[locationKey]
                        if (currentOccupancy == null || currentOccupancy.npcName != npc.name) {
                            markLocationOccupied(loc, npc.name, action)
                        }
                    }
                }
            }

            "work" -> {
                // Make NPC perform work animation
                sitTrait.setSitting(null) // Ensure NPC is not sitting
            }

            "sleep" -> {
                // Make NPC sleep
                entityPoseTrait.pose = EntityPoseTrait.EntityPose.SLEEPING
                // Only mark location as occupied if it's not already occupied by this NPC
                location?.let { loc ->
                    val locationKey = "${loc.name}:$action"
                    val currentOccupancy = locationOccupancy[locationKey]
                    if (currentOccupancy == null || currentOccupancy.npcName != npc.name) {
                        markLocationOccupied(loc, npc.name, action)
                    }
                }
            }

            "idle" -> {
                // Default idle behavior
                entityPoseTrait.pose = EntityPoseTrait.EntityPose.STANDING
                sitTrait.setSitting(null) // Ensure NPC is not sitting
            }

            else -> {
                plugin.logger.warning("Unknown action: $action for NPC: ${npc.name}")
            }
        }
    }

    /**
     * Moves all NPCs within proximity to a specific target location
     * @param targetLocation The location where NPCs should move to
     * @param proximityRadius The radius to search for NPCs around the target location
     * @param action Optional action to execute when NPCs reach the destination (idle, sit, work, sleep)
     * @param excludeNPCs Optional list of NPC names to exclude from the movement
     * @param onlyIncludeNPCs Optional list of NPC names to only include (if specified, only these NPCs will move)
     */
    fun moveNearbyNPCsToLocation(
        targetLocation: Location,
        proximityRadius: Double = 50.0,
        action: String = "idle",
        excludeNPCs: List<String> = emptyList(),
        onlyIncludeNPCs: List<String> = emptyList(),
    ) {
        // Get all NPCs within proximity of the target location
        val allNPCs = mutableListOf<NPC>()

        // Get NPCs from all online players within range
        for (player in plugin.server.onlinePlayers) {
            if (player.location.world == targetLocation.world &&
                player.location.distance(targetLocation) <= proximityRadius * 2
            ) {
                allNPCs.addAll(plugin.npcUtils.getNearbyNPCs(player, proximityRadius))
            }
        }

        // Filter NPCs to only include those actually within the target radius
        val nearbyNPCs =
            allNPCs
                .distinct()
                .filter { npc ->
                    val npcLocation = npc.entity?.location ?: npc.getOrAddTrait(CurrentLocation::class.java).location
                    npcLocation != null &&
                        npcLocation.world == targetLocation.world &&
                        npcLocation.distance(targetLocation) <= proximityRadius
                }.filter { npc ->
                    // Apply inclusion/exclusion filters
                    val npcName = npc.name.lowercase()
                    val shouldExclude = excludeNPCs.any { it.lowercase() == npcName }
                    val shouldInclude = onlyIncludeNPCs.isEmpty() || onlyIncludeNPCs.any { it.lowercase() == npcName }

                    !shouldExclude && shouldInclude && !plugin.npcManager.isNPCDisabled(npc)
                }

        if (nearbyNPCs.isEmpty()) {
            plugin.logger.info("No NPCs found within $proximityRadius blocks of target location")
            return
        }

        plugin.logger.info(
            "Moving ${nearbyNPCs.size} NPCs to target location: ${targetLocation.x}, ${targetLocation.y}, ${targetLocation.z}",
        )

        // Move each NPC to the target location with slight random offsets to avoid clustering
        nearbyNPCs.forEachIndexed { index, npc ->
            // Add small random offsets to prevent NPCs from clustering at exact same spot
            val random =
                java.util.concurrent.ThreadLocalRandom
                    .current()
            val offsetRadius = 3.0 // 3 block radius for spreading NPCs
            val randomOffsetX = random.nextDouble(-offsetRadius, offsetRadius)
            val randomOffsetZ = random.nextDouble(-offsetRadius, offsetRadius)

            val individualTargetLocation =
                Location(
                    targetLocation.world,
                    targetLocation.x + randomOffsetX,
                    targetLocation.y,
                    targetLocation.z + randomOffsetZ,
                    random.nextFloat() * 360f, // Random facing direction
                    targetLocation.pitch,
                )

            // Find safe ground for this NPC
            val safeLocation = findNearbyGround(individualTargetLocation, maxBlocksCheck = 5)
            val finalLocation = safeLocation ?: targetLocation

            // Create callback to execute action after reaching destination
            val moveCallback =
                Runnable {
                    executeAction(npc, action)
                    if (plugin.config.debugMessages) {
                        plugin.logger.info("${npc.name} reached target location and executed action: $action")
                    }
                }

            // Add slight delay between each NPC movement to prevent overwhelming the server
            val delay = (index * 5L) // 0.25 second delay between each NPC
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    moveNPCToLocation(npc, finalLocation, moveCallback)
                },
                delay,
            )
        }
    }

    /**
     * Moves all NPCs within proximity to a specific StoryLocation
     * @param targetStoryLocation The StoryLocation where NPCs should move to
     * @param proximityRadius The radius to search for NPCs around the target location
     * @param action Optional action to execute when NPCs reach the destination
     * @param excludeNPCs Optional list of NPC names to exclude from the movement
     * @param onlyIncludeNPCs Optional list of NPC names to only include
     */
    fun moveNearbyNPCsToStoryLocation(
        targetStoryLocation: StoryLocation,
        proximityRadius: Double = 50.0,
        action: String = "idle",
        excludeNPCs: List<String> = emptyList(),
        onlyIncludeNPCs: List<String> = emptyList(),
    ) {
        val bukkitLocation = targetStoryLocation.bukkitLocation
        if (bukkitLocation == null) {
            plugin.logger.warning("Cannot move NPCs to ${targetStoryLocation.name} - no Bukkit location found")
            return
        }

        moveNearbyNPCsToLocation(bukkitLocation, proximityRadius, action, excludeNPCs, onlyIncludeNPCs)
    }

    fun shutdown() {
        scheduleTask?.cancel()
        scheduleTask = null
        // Clear cooldowns on shutdown
        npcRandomPathingCooldowns.clear()

        // Clear cooldowns for bark dialogue
        npcLastDialogueTimes.clear()

        // Clear movement queue
        npcMovementQueue.clear()
    }

    /**
     * Checks if the schedule runner is currently active
     */
    fun isScheduleRunnerActive(): Boolean = scheduleTask != null && !scheduleTask!!.isCancelled

    /**
     * Gets the remaining cooldown time for an NPC in seconds
     * @return Remaining cooldown in seconds, or 0 if not in cooldown
     */
    fun getRandomPathingCooldownRemaining(npcName: String): Int {
        val lastProcessedTime = npcRandomPathingCooldowns[npcName.lowercase()] ?: return 0
        val cooldownMs = plugin.config.randomPathingCooldown * 1000L
        val timeSinceLastProcessed = System.currentTimeMillis() - lastProcessedTime
        val remainingMs = cooldownMs - timeSinceLastProcessed

        return if (remainingMs > 0) (remainingMs / 1000).toInt() else 0
    }

    /**
     * Manually clears the cooldown for a specific NPC
     */
    fun clearRandomPathingCooldown(npcName: String) {
        npcRandomPathingCooldowns.remove(npcName.lowercase())
        if (plugin.config.debugMessages) {
            plugin.logger.info("Cleared random pathing cooldown for NPC: $npcName")
        }
    }

    /**
     * Clears all random pathing cooldowns
     */
    fun clearAllRandomPathingCooldowns() {
        val clearedCount = npcRandomPathingCooldowns.size
        npcRandomPathingCooldowns.clear()
        if (plugin.config.debugMessages) {
            plugin.logger.info("Cleared all random pathing cooldowns ($clearedCount NPCs)")
        }
    }

    /**
     * Cleans up expired cooldown entries to prevent memory leaks
     */
    private fun cleanupExpiredCooldowns() {
        val currentTime = System.currentTimeMillis()
        val cooldownMs = plugin.config.randomPathingCooldown * 1000L

        val expiredEntries =
            npcRandomPathingCooldowns.entries.filter { (_, timestamp) ->
                currentTime - timestamp > cooldownMs * 2 // Remove entries that are more than 2x the cooldown period old
            }

        expiredEntries.forEach { (npcName, _) ->
            npcRandomPathingCooldowns.remove(npcName)
        }

        if (expiredEntries.isNotEmpty() && plugin.config.debugMessages) {
            plugin.logger.info("Cleaned up ${expiredEntries.size} expired random pathing cooldowns")
        }
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
        val dialogue: List<String>? = null,
        val random: Boolean = false,
        val duty: String? = null, // NEW: Duty field
    )

    companion object {
        private var instance: NPCScheduleManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): NPCScheduleManager =
            instance ?: NPCScheduleManager(plugin).also { instance = it }
    }

    /**
     * Data class to track occupancy information for a location
     */
    data class OccupancyInfo(
        val npcName: String,
        val action: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Checks if a location is currently occupied by an NPC performing a specific action
     */
    private fun isLocationOccupied(
        location: StoryLocation,
        action: String,
    ): Boolean {
        val locationKey = "${location.name}:$action"
        val occupancy = locationOccupancy[locationKey]

        if (occupancy == null) return false

        // Give newly marked locations a grace period before checking action status
        // This prevents clearing occupancy when NPCs are still walking to the location
        val gracePeriodMs = 10000L // 10 seconds
        val isWithinGracePeriod = (System.currentTimeMillis() - occupancy.timestamp) < gracePeriodMs

        if (isWithinGracePeriod) {
            // During grace period, only check if NPC exists, not if they're performing the action yet
            val occupyingNPC = plugin.npcDataManager.getNPC(occupancy.npcName)
            if (occupyingNPC?.entity == null) {
                // NPC no longer exists, clear occupancy
                locationOccupancy.remove(locationKey)
                return false
            }
            // Location is still considered occupied during grace period
            return true
        }

        // After grace period, do full validation
        val occupyingNPC = plugin.npcDataManager.getNPC(occupancy.npcName)
        if (occupyingNPC?.entity == null) {
            // NPC no longer exists, clear occupancy
            locationOccupancy.remove(locationKey)
            return false
        }

        val npcLocation = occupyingNPC.entity.location
        val locationDistance = npcLocation.distanceSquared(location.bukkitLocation ?: return false)
        val tolerance = 25.0 // 5 block radius squared

        // Check if NPC is still at the location
        if (locationDistance > tolerance) {
            // NPC moved away, clear occupancy
            locationOccupancy.remove(locationKey)
            return false
        }

        // Check if NPC is still performing the expected action
        val isStillPerformingAction =
            when (action.lowercase()) {
                "sit" -> occupyingNPC.getOrAddTrait(SitTrait::class.java).isSitting
                "sleep" ->
                    occupyingNPC.getOrAddTrait(EntityPoseTrait::class.java).pose ==
                        EntityPoseTrait.EntityPose.SLEEPING

                else -> true // For other actions, assume still valid
            }

        if (!isStillPerformingAction) {
            // NPC stopped performing the action, clear occupancy
            locationOccupancy.remove(locationKey)
            return false
        }

        return true
    }

    /**
     * Marks a location as occupied by an NPC performing a specific action
     */
    private fun markLocationOccupied(
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

    /**
     * Clears occupancy for a location and action
     */
    private fun clearLocationOccupancy(
        location: StoryLocation,
        action: String,
    ) {
        val locationKey = "${location.name}:$action"
        val removed = locationOccupancy.remove(locationKey)

        if (removed != null && plugin.config.debugMessages) {
            plugin.logger.info(
                "Cleared occupancy for location '${location.name}' action '$action' (was ${removed.npcName})",
            )
        }
    }

    /**
     * Clears all occupancy for a specific NPC (useful when NPC moves or changes actions)
     */
    private fun clearNPCOccupancy(npcName: String) {
        val toRemove = locationOccupancy.entries.filter { it.value.npcName == npcName }
        toRemove.forEach { entry ->
            locationOccupancy.remove(entry.key)
            if (plugin.config.debugMessages) {
                plugin.logger.info("Cleared occupancy for $npcName at ${entry.key}")
            }
        }
    }

    /**
     * Clears occupancy for a specific NPC except for a specific location (useful when NPC moves to a new location)
     */
    private fun clearNPCOccupancyExcept(
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
}
