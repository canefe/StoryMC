package com.canefe.story.command.story.session

import com.canefe.story.Story
import com.canefe.story.session.SessionManager
import net.kyori.adventure.text.minimessage.MiniMessage

class SessionCommandUtils {
    val story: Story = Story.instance
    val mm: MiniMessage = story.miniMessage
    val sessionManager: SessionManager = story.sessionManager
}
