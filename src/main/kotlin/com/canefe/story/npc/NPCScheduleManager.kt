package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import net.citizensnpcs.api.npc.NPC
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NPCScheduleManager private constructor(private val plugin: Story) {
	val schedules = ConcurrentHashMap<String, NPCSchedule>()
	private val scheduleFolder: File =
		File(plugin.dataFolder, "schedules").apply {
			if (!exists()) {
				mkdirs()
			}
		}
	private var scheduleTask: BukkitTask? = null

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

				val entry = ScheduleEntry(time, locationName, action, dialogue)
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
		// From 6-23 hours
		for (hour in 6..23) {
			schedule.addEntry(ScheduleEntry(hour, "", "idle", ""))
		}
		return schedule
	}

	fun getSchedule(npcName: String): NPCSchedule? {
		return schedules[npcName.lowercase()]
	}

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

					val gameTime = plugin.server.worlds[0].time
					// Convert to 24-hour format (0-23)
					val hour = ((gameTime / 1000 + 6) % 24).toInt() // +6 because MC day starts at 6am

					for (schedule in schedules.values) {
						val currentEntry = schedule.getEntryForTime(hour)
						if (currentEntry != null) {
							executeScheduleEntry(schedule.npcName, currentEntry)
						}
					}
				}
			}.runTaskTimer(plugin, 20L, 1200L) // Check every minute
	}

	private fun executeScheduleEntry(
		npcName: String,
		entry: ScheduleEntry,
	) {
		// Get NPC entity through your NPC system
		val npc = plugin.npcDataManager.getNPC(npcName) ?: return
		val npcEntity = npc.entity ?: return

		// Handle location movement
		val locationName = entry.locationName
		if (locationName != null) {
			val location = plugin.locationManager.getLocation(locationName)
			if (location != null) {
				// Use your existing NPC movement system or teleport
				moveNPCToLocation(npc, location)
			}
		}

		// Handle action
		if (entry.action != null) {
			executeAction(npcEntity, entry.action)
		}

		// Handle dialogue (announcement)
		if (entry.dialogue != null) {
			plugin.npcMessageService.broadcastNPCMessage(entry.dialogue, npc)
		}
	}

	private fun moveNPCToLocation(
		npc: NPC,
		location: StoryLocation,
	) {
		// Implement movement - this will depend on your NPC system
		val bukkitLocation = location.bukkitLocation
		if (bukkitLocation == null) {
			plugin.logger.warning("No Bukkit location found for ${location.name}")
			return
		}
		plugin.logger.info("Moving NPC to $bukkitLocation")
		plugin.npcManager.walkToLocation(npc, bukkitLocation, 0.1, 1f, 30, null, null)
	}

	private fun executeAction(
		npc: Entity,
		action: String,
	) {
		// Implement actions like sitting, working, etc.
		when (action.lowercase()) {
			"sit" -> {
				// Make NPC sit
			}
			"work" -> {
				// Make NPC perform work animation
			}
			"sleep" -> {
				// Make NPC sleep
			}
			"idle" -> {
				// Default idle behavior
			}
			else -> {
				plugin.logger.warning("Unknown action: $action for NPC: ${npc.name}")
			}
		}
	}

	fun shutdown() {
		scheduleTask?.cancel()
	}

	class NPCSchedule(val npcName: String) {
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
	)

	companion object {
		private var instance: NPCScheduleManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): NPCScheduleManager {
			return instance ?: NPCScheduleManager(plugin).also { instance = it }
		}
	}
}
