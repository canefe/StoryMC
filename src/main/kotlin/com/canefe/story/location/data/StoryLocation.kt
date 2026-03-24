package com.canefe.story.location.data

import com.canefe.story.location.LocationManager
import org.bukkit.Location

data class StoryLocation(
    val name: String,
    var description: String = "",
    var bukkitLocation: Location? = null,
    var parentLocationName: String? = null,
    val allowedNPCs: MutableList<String> = mutableListOf(),
    var hideTitle: Boolean = false,
    var randomPathingAction: String? = null, // Action to perform when NPCs randomly move here (sit, sleep, work, idle)
) {
    fun hasParent(): Boolean = !parentLocationName.isNullOrEmpty()

    // two different constructors
    constructor(name: String, description: String, bukkitLocation: Location) : this(
        name,
        description,
        bukkitLocation,
        null,
    )

    constructor(name: String, description: String, parentLocationName: String?) : this(
        name,
        description,
        null,
        parentLocationName,
    )

    constructor(name: String) : this(
        name,
        "",
        null,
        null,
    )

    fun getOwnName(): String {
        // If the name contains "/", split and take the last segment
        if ("/" in name) {
            return name.substringAfterLast("/")
        }

        // If it has a parent, return just this location's name
        parentLocationName?.let {
            if (it.isNotEmpty()) {
                return name
            }
        }

        // Fallback: no parent, no slash → it's already the root name
        return name
    }

    fun getFormattedName(): String {
        val own = getOwnName() // e.g. "Windhelm_Stables"

        val parts = own.split('_')
        if (parts.size <= 1) {
            return parts[0].lowercase().replaceFirstChar { it.titlecase() }
        }

        val parent = parentLocationName?.substringAfterLast("/") // normalize

        val trimmed =
            if (parent != null && parts.first().equals(parent, ignoreCase = true)) {
                parts.drop(1) // Stables
            } else {
                parts // DO NOT drop "Upper" in "Upper_City" etc.
            }

        return trimmed.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.titlecase() } }
    }

    // Helper method for getting the location description (which includes the parent location description as well)
    private fun getFullDescription(locationRegistry: Map<String, StoryLocation>): String {
        val parts = mutableListOf<String>()
        if (description.isNotBlank()) {
            parts.add(description)
        }

        // Recursively add parent descriptions
        if (hasParent() && locationRegistry.containsKey(parentLocationName)) {
            val parentLocation = locationRegistry[parentLocationName]
            parentLocation?.let {
                val parentDesc = it.getFullDescription(locationRegistry)
                if (parentDesc.isNotBlank()) {
                    parts.add(parentDesc)
                }
            }
        }

        return parts.joinToString("\n")
    }

    /**
     * Checks if a location is a sublocation (has a parent)
     */
    val isSubLocation: Boolean
        get() = this.parentLocationName != null || this.name.contains("/")

    // Format the full description for use in prompts
    fun getContextForPrompt(locationRegistry: Map<String, StoryLocation>): String {
        val fullDescription = getFullDescription(locationRegistry)

        return if (fullDescription.isBlank()) {
            "No context available for location '$name'."
        } else {
            "Location context for '$name':\n$fullDescription"
        }
    }

    // Simple version that doesn't need location registry
    fun getContextForPrompt(): String =
        if (description.isBlank()) {
            "No context available for location '$name'."
        } else {
            "Location context for '$name':\n$description" +
                (if (hasParent()) "\n(Note: Parent location context not included)" else "")
        }

    // Version that works with LocationManager
    fun getContextForPrompt(locationManager: LocationManager): String {
        val locationMap = locationManager.getAllLocations().associateBy { it.name }
        return getContextForPrompt(locationMap)
    }

    // Override toString to match the original format exactly
    override fun toString(): String =
        "Location{name='$name', bukkitLocation=$bukkitLocation, " +
            "description='$description', parent='$parentLocationName'}"
}
