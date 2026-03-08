package com.canefe.story.conversation.theme

import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage

class ConversationThemeManager(
    private val registry: ConversationThemeRegistry,
) {
    private val activeThemes = mutableMapOf<Int, MutableList<ConversationTheme>>()

    fun activateTheme(conversation: Conversation, themeName: String): Boolean {
        if (!registry.isRegistered(themeName)) return false

        val themes = activeThemes.getOrPut(conversation.id) { mutableListOf() }

        // Don't activate if already active
        if (themes.any { it.name == themeName }) return false

        val newTheme = registry.create(themeName)

        // Check compatibility with all currently active themes
        for (existing in themes) {
            val newCompatible = newTheme.compatibleWith.isEmpty() || newTheme.compatibleWith.contains(existing.name)
            val existingCompatible = existing.compatibleWith.isEmpty() || existing.compatibleWith.contains(themeName)
            if (!newCompatible || !existingCompatible) return false
        }

        themes.add(newTheme)
        conversation.themeData.addThemeName(themeName)
        newTheme.onActivate(conversation)
        return true
    }

    fun deactivateTheme(conversation: Conversation, themeName: String): Boolean {
        val themes = activeThemes[conversation.id] ?: return false
        val theme = themes.find { it.name == themeName } ?: return false

        theme.onDeactivate(conversation)
        themes.remove(theme)
        conversation.themeData.removeThemeName(themeName)

        if (themes.isEmpty()) {
            activeThemes.remove(conversation.id)
        }
        return true
    }

    fun getActiveThemes(conversation: Conversation): List<ConversationTheme> =
        activeThemes[conversation.id]?.toList() ?: emptyList()

    fun hasTheme(conversation: Conversation, themeName: String): Boolean =
        activeThemes[conversation.id]?.any { it.name == themeName } ?: false

    fun onConversationEnd(conversation: Conversation) {
        val themes = activeThemes.remove(conversation.id) ?: return
        for (theme in themes) {
            theme.onDeactivate(conversation)
        }
        conversation.themeData.clearThemeNames()
    }

    fun onMessage(conversation: Conversation, message: ConversationMessage) {
        val themes = activeThemes[conversation.id] ?: return
        for (theme in themes) {
            theme.onMessage(conversation, message)
        }
    }
}
