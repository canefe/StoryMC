package com.canefe.story.location

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.storage.LocationDocument
import com.canefe.story.storage.LocationStorage
import org.bukkit.Location
import java.io.File

class LocationManager(
    private val plugin: Story,
    private var locationStorage: LocationStorage,
) {
    fun updateStorage(storage: LocationStorage) {
        locationStorage = storage
    }

    private val locations: MutableMap<String, StoryLocation> = HashMap()
    val locationDirectory: File =
        File(plugin.dataFolder, "locations").apply {
            if (!exists()) mkdirs()
        }

    fun getSublocations(parentLocationName: String): List<StoryLocation> =
        locations.values.filter { location ->
            location.parentLocationName == parentLocationName ||
                location.name.startsWith("$parentLocationName/")
        }

    fun loadLocationData(locationName: String): StoryLocation? {
        val doc = locationStorage.loadLocation(locationName) ?: return null
        val storyLocation = documentToStoryLocation(doc)
        locations[locationName] = storyLocation
        return storyLocation
    }

    fun getLocationByPosition(
        position: Location,
        range: Double = 50.0,
    ): StoryLocation? {
        var closestLocation: StoryLocation? = null
        var closestDistance = Double.MAX_VALUE

        for (location in locations.values) {
            val bukkitLocation = location.bukkitLocation ?: continue
            if (bukkitLocation.world != position.world) continue

            val distance = bukkitLocation.distance(position)
            if (distance <= range && distance < closestDistance) {
                closestDistance = distance
                closestLocation = location
            }
        }

        return closestLocation
    }

    fun getLocationByPosition2D(
        position: Location,
        range: Double = 50.0,
    ): StoryLocation? {
        var closestLocation: StoryLocation? = null
        var closestDistance = Double.MAX_VALUE

        for (location in locations.values) {
            val loc = location.bukkitLocation ?: continue
            if (loc.world != position.world) continue

            val dx = loc.x - position.x
            val dz = loc.z - position.z
            val distSq = dx * dx + dz * dz

            if (distSq <= range * range && distSq < closestDistance) {
                closestDistance = distSq
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

            val location = StoryLocation(name, "", bukkitLocation, null)
            locations[name] = location
            saveLocation(location)
            location
        } catch (e: Exception) {
            plugin.logger.severe("Failed to create location: $name")
            e.printStackTrace()
            null
        }
    }

    fun getOrCreateDefaultLocation(): StoryLocation {
        val name = plugin.configService.defaultLocationName
        return getLocation(name) ?: createLocation(name, null)
            ?: StoryLocation(name, "", null, null)
    }

    fun getLocation(name: String): StoryLocation? =
        locations[name] ?: loadLocation(name)?.also { location ->
            locations[name] = location
        }

    fun getAllLocations(): List<StoryLocation> = locations.values.toList()

    fun getLocationGlobalContexts(locationName: String): String = getAllContextForLocation(locationName)

    fun loadAllLocations() {
        locations.clear()
        val docs = locationStorage.loadAllLocations()
        for ((name, doc) in docs) {
            locations[name] = documentToStoryLocation(doc)
        }
        plugin.logger.info("Loaded ${locations.size} locations")
    }

    fun loadLocation(name: String): StoryLocation? {
        locations[name]?.let { return it }

        val doc = locationStorage.loadLocation(name) ?: return null
        val location = documentToStoryLocation(doc)
        locations[name] = location
        return location
    }

    fun saveLocation(location: StoryLocation) {
        val doc = storyLocationToDocument(location)
        locationStorage.saveLocation(doc)

        // Reload to update cache
        val reloaded = locationStorage.loadLocation(location.name)
        if (reloaded != null) {
            locations[location.name] = documentToStoryLocation(reloaded)
        }
    }

    fun saveLocationFile(
        locationName: String,
        config: org.bukkit.configuration.file.FileConfiguration,
    ) {
        val locationFile = File(locationDirectory, "$locationName.yml")
        try {
            config.save(locationFile)
        } catch (e: java.io.IOException) {
            plugin.logger.severe("Failed to save location file: $locationName")
        }
    }

    fun getAllContextForLocation(locationName: String): String {
        val parts = mutableListOf<String>()
        val location = getLocation(locationName) ?: return ""

        if (location.description.isNotBlank()) {
            parts.add(location.description)
        }

        var parentName = location.parentLocationName
        while (!parentName.isNullOrEmpty()) {
            val parentLocation = getLocation(parentName) ?: break
            if (parentLocation.description.isNotBlank()) {
                parts.add(parentLocation.description)
            }
            parentName = parentLocation.parentLocationName
        }

        return parts.joinToString("\n")
    }

    private fun documentToStoryLocation(doc: LocationDocument): StoryLocation {
        val location =
            StoryLocation(
                doc.name,
                doc.description,
                doc.parentLocationName,
            )

        if (doc.allowedNPCs.isNotEmpty()) {
            location.allowedNPCs.addAll(doc.allowedNPCs)
        }

        location.randomPathingAction = doc.randomPathingAction
        location.hideTitle = doc.hideTitle

        // Reconstruct Bukkit Location
        if (doc.world != null) {
            val world = plugin.server.getWorld(doc.world)
            if (world != null) {
                location.bukkitLocation = Location(world, doc.x, doc.y, doc.z, doc.yaw, doc.pitch)
            }
        }

        return location
    }

    private fun storyLocationToDocument(location: StoryLocation): LocationDocument {
        val bukkitLoc = location.bukkitLocation
        return LocationDocument(
            name = location.name,
            description = location.description,
            parentLocationName = if (location.hasParent()) location.parentLocationName else null,
            world = bukkitLoc?.world?.name,
            x = bukkitLoc?.x ?: 0.0,
            y = bukkitLoc?.y ?: 0.0,
            z = bukkitLoc?.z ?: 0.0,
            yaw = bukkitLoc?.yaw ?: 0f,
            pitch = bukkitLoc?.pitch ?: 0f,
            allowedNPCs = location.allowedNPCs,
            hideTitle = location.hideTitle,
            randomPathingAction = location.randomPathingAction,
        )
    }
}
