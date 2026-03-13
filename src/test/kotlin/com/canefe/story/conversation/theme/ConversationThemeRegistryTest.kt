package com.canefe.story.conversation.theme

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConversationThemeRegistryTest {
    private lateinit var registry: ConversationThemeRegistry

    @BeforeEach
    fun setUp() {
        registry = ConversationThemeRegistry()
    }

    @Test
    fun `register and create theme`() {
        registry.register(ChatTheme.NAME) { ChatTheme() }

        val theme = registry.create(ChatTheme.NAME)
        assertEquals(ChatTheme.NAME, theme.name)
        assertEquals("Chat", theme.displayName)
    }

    @Test
    fun `create unregistered theme throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.create("nonexistent")
        }
    }

    @Test
    fun `getRegisteredThemes returns all registered names`() {
        registry.register(ChatTheme.NAME) { ChatTheme() }
        registry.register(ViolenceTheme.NAME) { ViolenceTheme() }

        val themes = registry.getRegisteredThemes()
        assertEquals(setOf(ChatTheme.NAME, ViolenceTheme.NAME), themes)
    }

    @Test
    fun `unregister removes theme`() {
        registry.register(ChatTheme.NAME) { ChatTheme() }
        assertTrue(registry.isRegistered(ChatTheme.NAME))

        registry.unregister(ChatTheme.NAME)
        assertFalse(registry.isRegistered(ChatTheme.NAME))
    }

    @Test
    fun `isRegistered returns false for unknown theme`() {
        assertFalse(registry.isRegistered("unknown"))
    }
}
