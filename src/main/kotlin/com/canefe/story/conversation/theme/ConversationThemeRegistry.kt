package com.canefe.story.conversation.theme

class ConversationThemeRegistry {
    private val factories = mutableMapOf<String, ConversationThemeFactory>()

    fun register(
        name: String,
        factory: ConversationThemeFactory,
    ) {
        factories[name] = factory
    }

    fun unregister(name: String) {
        factories.remove(name)
    }

    fun create(name: String): ConversationTheme =
        factories[name]?.create()
            ?: throw IllegalArgumentException("No theme registered with name: $name")

    fun getRegisteredThemes(): Set<String> = factories.keys.toSet()

    fun isRegistered(name: String): Boolean = factories.containsKey(name)
}
