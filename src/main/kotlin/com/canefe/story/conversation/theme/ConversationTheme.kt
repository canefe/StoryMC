package com.canefe.story.conversation.theme

import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage

abstract class ConversationTheme {
    abstract val name: String
    abstract val displayName: String
    abstract val compatibleWith: Set<String>

    open fun onActivate(conversation: Conversation) {}

    open fun onDeactivate(conversation: Conversation) {}

    open fun onMessage(conversation: Conversation, message: ConversationMessage) {}
}
