package com.canefe.story.location.data

import org.bukkit.Location

data class StoryLocation(
    val name: String,
    val context: MutableList<String>,
    var bukkitLocation: Location? = null,
    var parentLocationName: String? = null
) {
    fun hasParent(): Boolean {
        return !parentLocationName.isNullOrEmpty()
    }

    // two different constructors
    constructor(name: String, context: MutableList<String>, bukkitLocation: Location) : this(
        name,
        context,
        bukkitLocation,
        null
    )

    constructor(name: String, context: MutableList<String>, parentLocationName: String?) : this(
        name,
        context,
        null,
        parentLocationName
    )

    constructor(name: String, context: MutableList<String>) : this(
        name,
        context,
        null,
        null
    ) {

    }

    // Override toString to match the original format exactly
    override fun toString(): String {
        return "Location{name='$name', bukkitLocation=$bukkitLocation, " +
                "context=$context, parent='$parentLocationName'}"
    }
}