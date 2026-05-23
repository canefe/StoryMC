package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.bridge.LocationSpawnIntent
import com.canefe.story.location.data.StoryLocation

/** Push a location's current state to the live sim (id = name, coords from bukkitLocation). */
fun emitLocationToSim(plugin: Story, loc: StoryLocation) {
    val bl = loc.bukkitLocation ?: return
    plugin.eventBus.emit(
        LocationSpawnIntent(
            id = loc.name,
            instanceName = loc.name,
            template = loc.template,
            x = bl.x,
            y = bl.y,
            z = bl.z,
            radius = loc.radius,
            tags = loc.tags.joinToString(","),
        ),
    )
}
