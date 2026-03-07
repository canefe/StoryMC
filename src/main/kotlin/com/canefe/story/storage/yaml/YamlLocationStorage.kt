@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.storage.LocationDocument
import com.canefe.story.storage.LocationStorage
import com.canefe.story.util.PathSanitizer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.logging.Logger

/**
 * @deprecated YAML storage is deprecated and has known bugs. Use MongoDB backend.
 */
@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlLocationStorage(
    private val locationDirectory: File,
    private val logger: Logger,
) : LocationStorage {
    init {
        if (!locationDirectory.exists()) locationDirectory.mkdirs()
    }

    override fun loadAllLocations(): Map<String, LocationDocument> {
        val locations = mutableMapOf<String, LocationDocument>()
        loadLocationsRecursively(locationDirectory, null, locations)
        return locations
    }

    private fun loadLocationsRecursively(
        directory: File,
        parentPath: String?,
        locations: MutableMap<String, LocationDocument>,
    ) {
        if (!directory.exists() || !directory.isDirectory) return
        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.isFile && file.name.endsWith(".yml")) {
                val locationName = file.name.replace(".yml", "")
                val fullPath = parentPath?.let { "$it/$locationName" } ?: locationName

                val config = YamlConfiguration.loadConfiguration(file)
                val context = config.getStringList("context")
                val explicitParent = config.getString("parent")
                val effectiveParent = explicitParent ?: parentPath

                val allowedNPCs =
                    if (config.contains("allowedNPCs")) {
                        config.getStringList("allowedNPCs")
                    } else {
                        emptyList()
                    }

                val randomPathingAction =
                    if (config.contains("randomPathingAction")) {
                        config.getString("randomPathingAction")
                    } else {
                        null
                    }

                val world = config.getString("world")
                val x = config.getDouble("x")
                val y = config.getDouble("y")
                val z = config.getDouble("z")
                val yaw = config.getDouble("yaw").toFloat()
                val pitch = config.getDouble("pitch").toFloat()
                val hideTitle = config.getBoolean("hideTitle")

                locations[fullPath] =
                    LocationDocument(
                        name = fullPath,
                        context = context,
                        parentLocationName = effectiveParent,
                        world = world,
                        x = x,
                        y = y,
                        z = z,
                        yaw = yaw,
                        pitch = pitch,
                        allowedNPCs = allowedNPCs,
                        hideTitle = hideTitle,
                        randomPathingAction = randomPathingAction,
                    )
            }
        }

        for (file in files) {
            if (file.isDirectory) {
                val dirName = file.name
                val newParentPath = parentPath?.let { "$it/$dirName" } ?: dirName
                loadLocationsRecursively(file, newParentPath, locations)
            }
        }
    }

    override fun loadLocation(name: String): LocationDocument? {
        // Location names may contain "/" for hierarchy (e.g. "Ashwood/Barracks")
        // Validate the resolved path stays within the location directory
        val relativePath = name.replace("/", File.separator)
        val locationFile =
            PathSanitizer.safeResolve(locationDirectory, relativePath)
                ?: return null

        if (!locationFile.exists()) return null

        val config = YamlConfiguration.loadConfiguration(locationFile)
        val context = config.getStringList("context")
        var parentName = config.getString("parent")

        if (parentName == null && name.contains("/")) {
            parentName = name.take(name.lastIndexOf("/"))
        }

        val allowedNPCs =
            if (config.contains("allowedNPCs")) {
                config.getStringList("allowedNPCs")
            } else {
                emptyList()
            }

        val randomPathingAction =
            if (config.contains("randomPathingAction")) {
                config.getString("randomPathingAction")
            } else {
                null
            }

        return LocationDocument(
            name = name,
            context = context,
            parentLocationName = parentName,
            world = config.getString("world"),
            x = config.getDouble("x"),
            y = config.getDouble("y"),
            z = config.getDouble("z"),
            yaw = config.getDouble("yaw").toFloat(),
            pitch = config.getDouble("pitch").toFloat(),
            allowedNPCs = allowedNPCs,
            hideTitle = config.getBoolean("hideTitle"),
            randomPathingAction = randomPathingAction,
        )
    }

    override fun saveLocation(location: LocationDocument) {
        val relativePath = location.name.replace("/", File.separator)
        val locationFile =
            PathSanitizer.safeResolve(locationDirectory, relativePath)
                ?: throw IOException("Invalid location name: ${location.name}")
        locationFile.parentFile?.mkdirs()
        val config = YamlConfiguration()

        config.set("name", location.name)
        config.set("context", location.context)

        if (location.parentLocationName != null) {
            config.set("parent", location.parentLocationName)
        }

        if (location.allowedNPCs.isNotEmpty()) {
            config.set("allowedNPCs", location.allowedNPCs)
        }

        if (location.randomPathingAction != null) {
            config.set("randomPathingAction", location.randomPathingAction)
        }

        config.set("hideTitle", location.hideTitle)

        if (location.world != null) {
            config.set("world", location.world)
            config.set("x", location.x)
            config.set("y", location.y)
            config.set("z", location.z)
            config.set("yaw", location.yaw)
            config.set("pitch", location.pitch)
        }

        try {
            config.save(locationFile)
        } catch (e: IOException) {
            logger.severe("Could not save location: ${location.name}")
        }
    }

    override fun deleteLocation(name: String) {
        val relativePath = name.replace("/", File.separator)
        val locationFile = PathSanitizer.safeResolve(locationDirectory, relativePath) ?: return
        if (locationFile.exists()) {
            locationFile.delete()
        }
    }
}
