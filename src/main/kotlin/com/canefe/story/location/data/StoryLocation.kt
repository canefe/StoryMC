package com.canefe.story.location.data

import com.canefe.story.location.LocationManager
import org.bukkit.Location

data class StoryLocation(
    val name: String,
    val context: MutableList<String>,
    var bukkitLocation: Location? = null,
    var parentLocationName: String? = null,
    val allowedNPCs: MutableList<String> = mutableListOf(),
    var hideTitle: Boolean = false,
    var randomPathingAction: String? = null, // Action to perform when NPCs randomly move here (sit, sleep, work, idle)
) {
    fun hasParent(): Boolean = !parentLocationName.isNullOrEmpty()

    // two different constructors
    constructor(name: String, context: MutableList<String>, bukkitLocation: Location) : this(
        name,
        context,
        bukkitLocation,
        null,
    )

    constructor(name: String, context: MutableList<String>, parentLocationName: String?) : this(
        name,
        context,
        null,
        parentLocationName,
    )

    constructor(name: String, context: MutableList<String>) : this(
        name,
        context,
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

    // Helper method for getting the location context (which includes the parent location context as well)
    private fun getFullContext(locationRegistry: Map<String, StoryLocation>): List<String> {
        val allContext = context.toMutableList()

        // Recursively add parent contexts
        if (hasParent() && locationRegistry.containsKey(parentLocationName)) {
            val parentLocation = locationRegistry[parentLocationName]
            parentLocation?.let {
                allContext.addAll(it.getFullContext(locationRegistry))
            }
        }

        return allContext
    }

    /**
     * Checks if a location is a sublocation (has a parent)
     */
    val isSubLocation: Boolean
        get() = this.parentLocationName != null || this.name.contains("/")

    // Format the full context for use in prompts
    fun getContextForPrompt(locationRegistry: Map<String, StoryLocation>): String {
        val fullContext = getFullContext(locationRegistry)

        return if (fullContext.isEmpty()) {
            "No context available for location '$name'."
        } else {
            "Location context for '$name':\n" + fullContext.joinToString("\n") { "- $it" }
        }
    }

    // Add a simple version that doesn't need location registry
    fun getContextForPrompt(): String {
        // Without access to parents, just format the current context
        return if (context.isEmpty()) {
            "No context available for location '$name'."
        } else {
            "Location context for '$name':\n" + context.joinToString("\n") { "- $it" } +
                (if (hasParent()) "\n(Note: Parent location context not included)" else "")
        }
    }

    // Add version that works with LocationManager
    fun getContextForPrompt(locationManager: LocationManager): String {
        // Convert the list of locations to a map with location name as key
        val locationMap = locationManager.getAllLocations().associateBy { it.name }
        return getContextForPrompt(locationMap)
    }

    // Override toString to match the original format exactly
    override fun toString(): String =
        "Location{name='$name', bukkitLocation=$bukkitLocation, " +
            "context=$context, parent='$parentLocationName'}"
}
