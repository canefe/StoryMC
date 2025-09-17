package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.location.LocationManager
import net.kyori.adventure.text.minimessage.MiniMessage

class LocationCommandUtils {
    val story: Story = Story.instance
    val mm: MiniMessage = story.miniMessage
    val locationManager: LocationManager = story.locationManager
}
