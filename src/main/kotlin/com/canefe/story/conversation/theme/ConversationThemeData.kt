package com.canefe.story.conversation.theme

data class ConversationThemeData(
    private val _activeThemeNames: MutableList<String> = mutableListOf(),
) {
    val activeThemeNames: List<String> get() = _activeThemeNames.toList()

    fun addThemeName(name: String) {
        if (!_activeThemeNames.contains(name)) {
            _activeThemeNames.add(name)
        }
    }

    fun removeThemeName(name: String): Boolean = _activeThemeNames.remove(name)

    fun clearThemeNames() {
        _activeThemeNames.clear()
    }
}
