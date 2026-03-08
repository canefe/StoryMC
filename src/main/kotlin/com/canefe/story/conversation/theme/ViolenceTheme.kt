package com.canefe.story.conversation.theme

class ViolenceTheme : ConversationTheme() {
    override val name: String = NAME
    override val displayName: String = "Violence"
    override val compatibleWith: Set<String> = setOf(ChatTheme.NAME)

    companion object {
        const val NAME = "violence"
    }
}
