package com.canefe.story.location

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import org.bukkit.Location
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.logging.Level

class LocationManager private constructor(
    private val plugin: Story,
) {
    private val locations: MutableMap<String, StoryLocation> = HashMap()
    val locationDirectory: File =
        File(plugin.dataFolder, "locations").apply {
            if (!exists()) mkdirs()
        }

    fun saveLocationFile(
        locationName: String,
        config: FileConfiguration,
    ) {
        val locationFile = File(locationDirectory, "$locationName.yml")
        try {
            config.save(locationFile)
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Failed to save location file: $locationName", e)
        }
    }

    /**
     * Gets all sublocations for a given parent location name
     * @param parentLocationName The name of the parent location
     * @return List of all sublocations that have the specified parent
     */
    fun getSublocations(parentLocationName: String): List<StoryLocation> =
        locations.values.filter { location ->
            // A location is a sublocation of the parent if:
            // 1. It explicitly has the given parent name OR
            // 2. Its name starts with parentLocationName/ (nested path structure)
            location.parentLocationName == parentLocationName ||
                location.name.startsWith("$parentLocationName/")
        }

    fun loadLocationData(locationName: String): StoryLocation? {
        val locationFile = File(locationDirectory, "$locationName.yml")
        if (!locationFile.exists()) {
            return null
        }

        val config = YamlConfiguration.loadConfiguration(locationFile)
        val storyLocation = StoryLocation(locationName, config.getStringList("context"))

        locations[locationName] = storyLocation
        return storyLocation
    }

    /**
     * Gets a location based on a bukkit position within a specified range
     * @param position The bukkit location to check
     * @param range The maximum distance to consider, defaults to 50 blocks
     * @return The closest StoryLocation within range, or null if none found
     */
    fun getLocationByPosition(
        position: Location,
        range: Double = 50.0,
    ): StoryLocation? {
        var closestLocation: StoryLocation? = null
        var closestDistance = Double.MAX_VALUE

        // Check all locations that have bukkit locations defined
        for (location in locations.values) {
            val bukkitLocation = location.bukkitLocation ?: continue

            // Must be in same world
            if (bukkitLocation.world != position.world) continue

            val distance = bukkitLocation.distance(position)
            if (distance <= range && distance < closestDistance) {
                closestDistance = distance
                closestLocation = location
            }
        }

        return closestLocation
    }

    fun addLocation(storyLocation: StoryLocation) {
        locations[storyLocation.name] = storyLocation
    }

    fun createLocation(
        name: String,
        bukkitLocation: Location?,
    ): StoryLocation? {
        return try {
            if (locations.containsKey(name)) {
                plugin.logger.warning("Location with name $name already exists.")
                return null
            }

            val location = StoryLocation(name, ArrayList(), bukkitLocation, null)
            locations[name] = location
            saveLocation(location)
            location
        } catch (e: Exception) {
            plugin.logger.severe("Failed to create location: $name")
            e.printStackTrace()
            null
        }
    }

    fun getLocation(name: String): StoryLocation? {
        // Check if it's already loaded in memory first
        return locations[name] ?: loadLocation(name)?.also { location ->
            // Store in cache for future use
            locations[name] = location
        }
    }

    fun getAllLocations(): List<StoryLocation> = locations.values.toList()

    fun getLocationGlobalContexts(locationName: String): List<String> = getAllContextForLocation(locationName)

    fun loadAllLocations() {
        locations.clear()
        loadLocationsRecursively(locationDirectory, null)
        plugin.logger.info("Loaded ${locations.size} locations")
    }

    private fun loadBukkitLocation(
        config: FileConfiguration,
        location: StoryLocation,
    ) {
        if (config.contains("world")) {
            val worldName = config.getString("world")
            val x = config.getDouble("x")
            val y = config.getDouble("y")
            val z = config.getDouble("z")
            val yaw = config.getDouble("yaw").toFloat()
            val pitch = config.getDouble("pitch").toFloat()

            val world = worldName?.let { plugin.server.getWorld(it) }
            world?.let {
                val bukkitLocation = Location(it, x, y, z, yaw, pitch)
                location.bukkitLocation = bukkitLocation
            }
        }
    }

    private fun loadLocationsRecursively(
        directory: File,
        parentPath: String?,
    ) {
        if (!directory.exists() || !directory.isDirectory) return

        val files = directory.listFiles() ?: return

        // First pass: load all YAML files in this directory
        for (file in files) {
            if (file.isFile && file.name.endsWith(".yml")) {
                val locationName = file.name.replace(".yml", "")
                val fullPath = parentPath?.let { "$it/$locationName" } ?: locationName

                val config = YamlConfiguration.loadConfiguration(file)
                val context = config.getStringList("context")

                // Use explicit parent from file if available, otherwise use directory structure
                val explicitParent = config.getString("parent")
                val effectiveParent = explicitParent ?: parentPath

                val location = StoryLocation(fullPath, context, effectiveParent)

                // Load allowedNPCs if exists
                if (config.contains("allowedNPCs")) {
                    location.allowedNPCs.addAll(config.getStringList("allowedNPCs"))
                }

                // Load randomPathingAction if exists
                if (config.contains("randomPathingAction")) {
                    location.randomPathingAction = config.getString("randomPathingAction")
                }

                // Load Bukkit location if exists
                loadBukkitLocation(config, location)

                locations[fullPath] = location
				/*plugin.logger.info(
					"Loaded location: $fullPath" +
						(effectiveParent?.let { " with parent: $it" } ?: ""),
				)*/
            }
        }

        // Second pass: process subdirectories
        for (file in files) {
            if (file.isDirectory) {
                val dirName = file.name
                val newParentPath = parentPath?.let { "$it/$dirName" } ?: dirName
                loadLocationsRecursively(file, newParentPath)
            }
        }
    }

    fun loadLocation(name: String): StoryLocation? {
        // Check if already loaded
        locations[name]?.let { return it }

        // If not loaded, try to find file - first as direct path
        var locationFile = File(locationDirectory, "$name.yml")

        // If file doesn't exist, try with folder structure
        if (!locationFile.exists() && name.contains("/")) {
            locationFile = File(locationDirectory, name.replace("/", File.separator) + ".yml")
        }

        if (!locationFile.exists()) {
            return null
        }

        // Load it and add to cache
        val config = YamlConfiguration.loadConfiguration(locationFile)
        val context = config.getStringList("context")
        var parentName = config.getString("parent")

        // If no explicit parent and this is a path with slashes, infer parent from path
        if (parentName == null && name.contains("/")) {
            parentName = name.substring(0, name.lastIndexOf("/"))
        }

        val location = StoryLocation(name, context, parentName)

        // Load allowedNPCs
        if (config.contains("allowedNPCs")) {
            location.allowedNPCs.addAll(config.getStringList("allowedNPCs"))
        }

        // Load randomPathingAction if exists
        if (config.contains("randomPathingAction")) {
            location.randomPathingAction = config.getString("randomPathingAction")
        }

        // Load Bukkit location
        loadBukkitLocation(config, location)

        locations[name] = location
        return location
    }

    fun saveLocation(location: StoryLocation) {
        val locationFile = File(locationDirectory, "${location.name}.yml")
        val config = YamlConfiguration()

        // Save basic properties
        config.set("name", location.name)
        config.set("context", location.context)

        // Save parent location if it exists
        if (location.hasParent()) {
            config.set("parent", location.parentLocationName)
        }

        // Save allowedNPCs if not empty
        if (location.allowedNPCs.isNotEmpty()) {
            config.set("allowedNPCs", location.allowedNPCs)
        }

        // Save randomPathingAction if it exists
        if (location.randomPathingAction != null) {
            config.set("randomPathingAction", location.randomPathingAction)
        }

        // Save Bukkit location if it exists
        location.bukkitLocation?.let { bukkitLoc ->
            config.set("world", bukkitLoc.world?.name)
            config.set("x", bukkitLoc.x)
            config.set("y", bukkitLoc.y)
            config.set("z", bukkitLoc.z)
            config.set("yaw", bukkitLoc.yaw)
            config.set("pitch", bukkitLoc.pitch)
        }

        try {
            config.save(locationFile)
        } catch (e: IOException) {
            plugin.logger.severe("Could not save location: ${location.name}")
            e.printStackTrace()
        }
    }

    fun getAllContextForLocation(locationName: String): List<String> {
        val allContext = mutableListOf<String>()
        val location = getLocation(locationName) ?: return allContext

        // Add this location's context
        allContext.addAll(location.context)

        // Recursively add parent contexts
        var parentName = location.parentLocationName
        while (!parentName.isNullOrEmpty()) {
            val parentLocation = getLocation(parentName) ?: break // Parent not found

            allContext.addAll(parentLocation.context)
            parentName = parentLocation.parentLocationName
        }

        return allContext
    }

    companion object {
        private var instance: LocationManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): LocationManager =
            instance ?: synchronized(this) {
                instance ?: LocationManager(plugin).also { instance = it }
            }
    }
}
