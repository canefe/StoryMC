package com.canefe.story.command.story.quest

import com.canefe.story.Story
import com.canefe.story.location.LocationManager
import net.kyori.adventure.text.minimessage.MiniMessage

class QuestCommandUtils {
	val story: Story = Story.instance
	val mm: MiniMessage = story.miniMessage
	val locationManager: LocationManager = story.locationManager
}
