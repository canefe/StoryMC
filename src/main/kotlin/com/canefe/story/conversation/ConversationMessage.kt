package com.canefe.story.conversation

data class ConversationMessage(
	val role: String,
	val content: String,
) {
	override fun toString(): String {
		return "ConversationMessage(role='$role', content='$content')"
	}

	companion object {
		fun fromString(str: String): ConversationMessage? {
			return try {
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
}
