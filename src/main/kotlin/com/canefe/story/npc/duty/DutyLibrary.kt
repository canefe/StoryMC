package com.canefe.story.npc.duty

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages duty scripts and workstations for locations
 */
class DutyLibrary private constructor(
    private val plugin: Story,
) {
    // Cache for loaded duty data per location
    private val locationDutyData = ConcurrentHashMap<String, LocationDutyData>()

    data class LocationDutyData(
        val workstations: Map<String, Workstation>,
        val dutyScripts: Map<String, DutyScript>,
        val barkPools: Map<String, BarkPool>,
        val defaultDuty: String?,
    )

    /**
     * Load duty data for a location
     */
    fun loadLocationDutyData(location: StoryLocation): LocationDutyData {
        val cacheKey = location.name.lowercase()

        // Check cache first
        locationDutyData[cacheKey]?.let { return it }

        // Try to find duty file for this location
        val dutyFile = findDutyFile(location)

        if (dutyFile?.exists() != true) {
            // Return empty data if no duty file found
            val emptyData =
                LocationDutyData(
                    workstations = emptyMap(),
                    dutyScripts = emptyMap(),
                    barkPools = emptyMap(),
                    defaultDuty = null,
                )
            locationDutyData[cacheKey] = emptyData
            return emptyData
        }

        val config = YamlConfiguration.loadConfiguration(dutyFile)

        // Load workstations
        val workstations = mutableMapOf<String, Workstation>()
        config.getConfigurationSection("workstations")?.let { section ->
            for (key in section.getKeys(false)) {
                val coords = section.getList(key) as? List<Double>
                if (coords != null && coords.size >= 3) {
                    workstations[key] = Workstation(key, coords[0], coords[1], coords[2])
                }
            }
        }

        // Load duty scripts
        val dutyScripts = mutableMapOf<String, DutyScript>()
        config.getConfigurationSection("duty_scripts")?.let { section ->
            for (scriptName in section.getKeys(false)) {
                val scriptSection = section.getConfigurationSection(scriptName) ?: continue
                val cycleSeconds = scriptSection.getInt("cycle_seconds", 60)

                val steps = mutableListOf<DutyStep>()
                scriptSection.getList("steps")?.forEach { stepObj ->
                    if (stepObj is Map<*, *>) {
                        val stepMap = stepObj as Map<String, Any>
                        val action = stepMap["action"]?.toString() ?: return@forEach
                        val duration = (stepMap["duration"] as? Number)?.toInt() ?: 0
                        val ifNear = (stepMap["if_near"] as? Number)?.toDouble()
                        val cooldown = (stepMap["cooldown"] as? Number)?.toInt() ?: 0

                        val args = mutableMapOf<String, String>()
                        (stepMap["args"] as? Map<*, *>)?.forEach { (k, v) ->
                            args[k.toString()] = v.toString()
                        }

                        steps.add(DutyStep(action, args, duration, ifNear, cooldown))
                    }
                }

                dutyScripts[scriptName] = DutyScript(scriptName, cycleSeconds, steps)
            }
        }

        // Load bark pools
        val barkPools = mutableMapOf<String, BarkPool>()
        config.getConfigurationSection("bark_pools")?.let { section ->
            for (poolName in section.getKeys(false)) {
                val messages = section.getStringList(poolName)
                if (messages.isNotEmpty()) {
                    barkPools[poolName] = BarkPool(poolName, messages)
                }
            }
        }

        // Get default duty
        val defaultDuty = config.getString("default_duty")

        val dutyData = LocationDutyData(workstations, dutyScripts, barkPools, defaultDuty)
        locationDutyData[cacheKey] = dutyData

        plugin.logger.info(
            "Loaded duty data for ${location.name}: ${dutyScripts.size} scripts, ${workstations.size} workstations",
        )

        return dutyData
    }

    /**
     * Get a duty script for a location
     */
    fun getDutyScript(
        location: StoryLocation,
        scriptName: String,
    ): DutyScript? {
        val dutyData = loadLocationDutyData(location)
        return dutyData.dutyScripts[scriptName]
    }

    /**
     * Get workstation location
     */
    fun getWorkstationLocation(
        location: StoryLocation,
        workstationName: String,
    ): Location? {
        val dutyData = loadLocationDutyData(location)
        val workstation = dutyData.workstations[workstationName] ?: return null
        val world = location.bukkitLocation?.world ?: return null

        return Location(world, workstation.x, workstation.y, workstation.z)
    }

    /**
     * Get bark pool
     */
    fun getBarkPool(
        location: StoryLocation,
        poolName: String,
    ): BarkPool? {
        val dutyData = loadLocationDutyData(location)
        return dutyData.barkPools[poolName]
    }

    /**
     * Get default duty for a location
     */
    fun getDefaultDuty(location: StoryLocation): String? {
        val dutyData = loadLocationDutyData(location)
        return dutyData.defaultDuty
    }

    /**
     * Find the duty file for a location
     * Looks for: locations/<path>/<name>.yml
     */
    private fun findDutyFile(location: StoryLocation): File? {
        val locationsDir = File(plugin.dataFolder, "locations")
        if (!locationsDir.exists()) return null

        // Try to construct path from location name
        val nameParts = location.name.split("/")
        if (nameParts.isEmpty()) return null

        // Build path: locations/World/Area/Location.yml
        val dutyFile = File(locationsDir, "${location.name}.yml")

        return if (dutyFile.exists()) dutyFile else null
    }

    /**
     * Clear cache for a location (useful for reloading)
     */
    fun clearCache(locationName: String) {
        locationDutyData.remove(locationName.lowercase())
    }

    /**
     * Clear all cached duty data
     */
    fun clearAllCache() {
        locationDutyData.clear()
    }

    companion object {
        private var instance: DutyLibrary? = null

        fun getInstance(plugin: Story): DutyLibrary {
            if (instance == null) {
                instance = DutyLibrary(plugin)
            }
            return instance!!
        }
    }
}
