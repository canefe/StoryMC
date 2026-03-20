package com.canefe.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.location.data.StoryLocation
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ScheduleManager private constructor(
    private val plugin: Story,
) {
    val schedules = ConcurrentHashMap<String, NPCSchedule>()
    private val scheduleFolder: File = File(plugin.dataFolder, "schedules").apply { if (!exists()) mkdirs() }
    private var scheduleTask: BukkitTask? = null
    private var lastReloadTime = 0L

    val occupancyTracker = OccupancyTracker(plugin)
    val movementService = MovementService(plugin)
    val scheduleExecutor = ScheduleExecutor(plugin, movementService, occupancyTracker)
    val randomPathingService =
        RandomPathingService(
            plugin,
            movementService,
            occupancyTracker,
            actionExecutor = { npc, action, location -> scheduleExecutor.executeAction(npc, action, location) },
        )

    init {
        loadAllSchedules()
        startScheduleRunner()
    }

    // --- Schedule CRUD ---

    fun reloadSchedules() {
        scheduleTask?.cancel()
        scheduleTask = null
        randomPathingService.clearAllCooldowns()
        scheduleExecutor.clearAllDialogueCooldowns()
        lastReloadTime = System.currentTimeMillis()
        loadAllSchedules()

        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (plugin.isEnabled) startScheduleRunner()
            },
            40L,
        )
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
            scheduleFile = File(scheduleFolder, "${npcName.lowercase()}.yml")
            if (!scheduleFile.exists()) return null
        }

        val config = YamlConfiguration.loadConfiguration(scheduleFile)
        val schedule = NPCSchedule(npcName)
        val scheduleSection = config.getConfigurationSection("schedule") ?: return schedule

        for (timeKey in scheduleSection.getKeys(false)) {
            try {
                val time = timeKey.toInt()
                val locationName = config.getString("schedule.$timeKey.location")
                val action = config.getString("schedule.$timeKey.action", "idle")
                val random = config.getBoolean("schedule.$timeKey.random", false)
                val duty = config.getString("schedule.$timeKey.duty")
                val dialogue =
                    if (config.isList("schedule.$timeKey.dialogue")) {
                        config.getStringList("schedule.$timeKey.dialogue")
                    } else {
                        val singleDialogue = config.getString("schedule.$timeKey.dialogue")
                        if (singleDialogue.isNullOrEmpty()) null else listOf(singleDialogue)
                    }
                schedule.addEntry(ScheduleEntry(time, locationName, action, dialogue, random, duty))
            } catch (e: NumberFormatException) {
                plugin.logger.warning("Invalid time format in schedule for $npcName: $timeKey")
            }
        }
        return schedule
    }

    fun saveSchedule(schedule: NPCSchedule) {
        val scheduleFile = File(scheduleFolder, "${schedule.npcName}.yml")
        val config = YamlConfiguration()
        for (entry in schedule.entries) {
            val timePath = "schedule.${entry.time}"
            config.set("$timePath.location", entry.locationName)
            config.set("$timePath.action", entry.action)
            if (entry.dialogue != null) config.set("$timePath.dialogue", entry.dialogue)
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

    // --- Schedule Runner ---

    private fun startScheduleRunner() {
        scheduleTask?.cancel()
        scheduleTask = null

        scheduleTask =
            object : BukkitRunnable() {
                override fun run() {
                    if (!plugin.isEnabled) {
                        cancel()
                        return
                    }

                    val timeSinceReload = System.currentTimeMillis() - lastReloadTime
                    if (timeSinceReload < 3000) return

                    if (plugin.server.onlinePlayers.isEmpty()) return
                    if (!plugin.config.randomPathingEnabled && !plugin.config.scheduleEnabled) return

                    val hour = plugin.timeService.getHours()
                    val nearbyNPCs = getNearbyNPCsToActivePlayers()

                    if (plugin.config.debugMessages) {
                        plugin.logger.info("Found ${nearbyNPCs.size} NPCs near active players at hour $hour.")
                    }

                    if (plugin.config.scheduleEnabled) {
                        for (npc in nearbyNPCs) {
                            val schedule = schedules[npc.name.lowercase()]
                            if (schedule != null) {
                                val currentEntry = schedule.getEntryForTime(hour)
                                if (currentEntry != null) {
                                    scheduleExecutor.executeScheduleEntry(
                                        schedule.npcName.lowercase(),
                                        currentEntry,
                                        schedules,
                                    )
                                }
                            }
                        }
                    }

                    if (plugin.config.randomPathingEnabled) {
                        scheduleExecutor.cleanupExpiredDialogueCooldowns()
                        randomPathingService.processRandomPathing(nearbyNPCs, hour, schedules)
                    }
                }
            }.runTaskTimer(plugin, 20L, plugin.config.scheduleTaskPeriod * 20L)
    }

    private fun getNearbyNPCsToActivePlayers(): List<StoryNPC> {
        val nearbyNPCs = mutableSetOf<StoryNPC>()
        val checkRadius = plugin.config.rangeBeforeTeleport * 2.0
        for (player in plugin.server.onlinePlayers) {
            nearbyNPCs.addAll(plugin.npcUtils.getNearbyNPCs(player, checkRadius))
        }
        return nearbyNPCs.toList()
    }

    // --- Public API delegations ---

    fun moveNearbyNPCsToLocation(
        targetLocation: Location,
        proximityRadius: Double = 50.0,
        action: String = "idle",
        excludeNPCs: List<String> = emptyList(),
        onlyIncludeNPCs: List<String> = emptyList(),
    ) {
        movementService.moveNearbyNPCsToLocation(
            targetLocation,
            proximityRadius,
            action,
            excludeNPCs,
            onlyIncludeNPCs,
        ) { npc, act -> scheduleExecutor.executeAction(npc, act) }
    }

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

    fun getRandomPathingCooldownRemaining(npcName: String): Int = randomPathingService.getCooldownRemaining(npcName)

    fun clearRandomPathingCooldown(npcName: String) = randomPathingService.clearCooldown(npcName)

    fun clearAllRandomPathingCooldowns() = randomPathingService.clearAllCooldowns()

    fun getDialogueCooldownRemaining(npcName: String): Int = scheduleExecutor.getDialogueCooldownRemaining(npcName)

    fun clearDialogueCooldown(npcName: String) = scheduleExecutor.clearDialogueCooldown(npcName)

    fun clearAllDialogueCooldowns() = scheduleExecutor.clearAllDialogueCooldowns()

    fun isScheduleRunnerActive(): Boolean = scheduleTask != null && !scheduleTask!!.isCancelled

    fun shutdown() {
        scheduleTask?.cancel()
        scheduleTask = null
        randomPathingService.clearAllCooldowns()
        scheduleExecutor.clearAllDialogueCooldowns()
    }

    companion object {
        private var instance: ScheduleManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): ScheduleManager = instance ?: ScheduleManager(plugin).also { instance = it }
    }
}
