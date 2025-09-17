package com.canefe.story.conversation

enum class ConversationMessageRole(
    val value: String,
) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromString(value: String): ConversationMessageRole? = entries.find { it.value == value }
    }
}
