package com.canefe.story.conversation.theme

class ChatTheme : ConversationTheme() {
    override val name: String = NAME
    override val displayName: String = "Chat"
    override val compatibleWith: Set<String> = emptySet() // compatible with everything

    companion object {
        const val NAME = "chat"
    }
}
