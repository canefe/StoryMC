package com.canefe.story.location.data

import com.canefe.story.location.LocationManager
import org.bukkit.Location

data class StoryLocation(
	val name: String,
	val context: MutableList<String>,
	var bukkitLocation: Location? = null,
	var parentLocationName: String? = null,
) {
	fun hasParent(): Boolean {
		return !parentLocationName.isNullOrEmpty()
	}

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
	override fun toString(): String {
		return "Location{name='$name', bukkitLocation=$bukkitLocation, " +
			"context=$context, parent='$parentLocationName'}"
	}
}
