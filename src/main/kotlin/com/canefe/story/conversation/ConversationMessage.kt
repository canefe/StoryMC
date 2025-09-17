package com.canefe.story.conversation

data class ConversationMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    override fun toString(): String = "ConversationMessage(role='$role', content='$content')"

    companion object {
        fun fromString(str: String): ConversationMessage? =
            try {
                val rolePattern = "role='([^']*)'".toRegex()
                val contentPattern = "content='([^']*)'".toRegex()

                val roleMatch = rolePattern.find(str)
                val contentMatch = contentPattern.find(str)

                if (roleMatch != null && contentMatch != null) {
                    val role = roleMatch.groupValues[1]
                    val content = contentMatch.groupValues[1]
                    ConversationMessage(role, content)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
    }
}
