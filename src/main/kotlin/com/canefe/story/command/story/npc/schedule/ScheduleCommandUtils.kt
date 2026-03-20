package com.canefe.story.command.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.location.LocationManager
import com.canefe.story.npc.schedule.ScheduleManager
import net.kyori.adventure.text.minimessage.MiniMessage

class ScheduleCommandUtils {
    val story: Story = Story.instance
    val mm: MiniMessage = story.miniMessage
    val scheduleManager: ScheduleManager = story.scheduleManager
    val locationManager: LocationManager = story.locationManager
}
