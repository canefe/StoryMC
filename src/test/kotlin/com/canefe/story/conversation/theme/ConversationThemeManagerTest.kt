package com.canefe.story.conversation.theme

import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.testutils.makeNpc
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConversationThemeManagerTest {
    private lateinit var registry: ConversationThemeRegistry
    private lateinit var manager: ConversationThemeManager
    private lateinit var conversation: Conversation

    @BeforeEach
    fun setUp() {
        registry = ConversationThemeRegistry()
        registry.register(ChatTheme.NAME) { ChatTheme() }
        registry.register(ViolenceTheme.NAME) { ViolenceTheme() }
        manager = ConversationThemeManager(registry)

        val npc = makeNpc("Guard")
        conversation = Conversation(
            id = 1,
            _players = mutableListOf(),
            initialNPCs = listOf(npc),
        )
    }

    @Test
    fun `activate theme adds it to conversation`() {
        assertTrue(manager.activateTheme(conversation, ChatTheme.NAME))
        assertTrue(manager.hasTheme(conversation, ChatTheme.NAME))
        assertEquals(listOf(ChatTheme.NAME), conversation.themeData.activeThemeNames)
    }

    @Test
    fun `activate same theme twice returns false`() {
        assertTrue(manager.activateTheme(conversation, ChatTheme.NAME))
        assertFalse(manager.activateTheme(conversation, ChatTheme.NAME))
    }

    @Test
    fun `activate unregistered theme returns false`() {
        assertFalse(manager.activateTheme(conversation, "nonexistent"))
    }

    @Test
    fun `compatible themes can stack`() {
        // ChatTheme has empty compatibleWith (compatible with everything)
        // ViolenceTheme is compatible with ChatTheme
        assertTrue(manager.activateTheme(conversation, ChatTheme.NAME))
        assertTrue(manager.activateTheme(conversation, ViolenceTheme.NAME))

        assertEquals(2, manager.getActiveThemes(conversation).size)
    }

    @Test
    fun `incompatible themes cannot stack`() {
        // Register a "trade" theme that is incompatible with violence
        registry.register("trade") {
            object : ConversationTheme() {
                override val name = "trade"
                override val displayName = "Trade"
                override val compatibleWith = setOf(ChatTheme.NAME)
            }
        }

        assertTrue(manager.activateTheme(conversation, ViolenceTheme.NAME))
        // trade is compatible with chat only, not violence; violence is compatible with chat only, not trade
        assertFalse(manager.activateTheme(conversation, "trade"))
    }

    @Test
    fun `deactivate theme removes it`() {
        manager.activateTheme(conversation, ChatTheme.NAME)
        assertTrue(manager.deactivateTheme(conversation, ChatTheme.NAME))
        assertFalse(manager.hasTheme(conversation, ChatTheme.NAME))
        assertTrue(conversation.themeData.activeThemeNames.isEmpty())
    }

    @Test
    fun `deactivate nonexistent theme returns false`() {
        assertFalse(manager.deactivateTheme(conversation, ChatTheme.NAME))
    }

    @Test
    fun `onConversationEnd cleans up all themes`() {
        manager.activateTheme(conversation, ChatTheme.NAME)
        manager.activateTheme(conversation, ViolenceTheme.NAME)

        manager.onConversationEnd(conversation)

        assertTrue(manager.getActiveThemes(conversation).isEmpty())
    }

    @Test
    fun `onMessage propagates to active themes`() {
        var messageReceived = false
        registry.register("spy") {
            object : ConversationTheme() {
                override val name = "spy"
                override val displayName = "Spy"
                override val compatibleWith = emptySet<String>()

                override fun onMessage(conversation: Conversation, message: ConversationMessage) {
                    messageReceived = true
                }
            }
        }

        manager.activateTheme(conversation, "spy")
        manager.onMessage(conversation, ConversationMessage("user", "hello"))

        assertTrue(messageReceived)
    }

    @Test
    fun `getActiveThemes returns empty list for conversation with no themes`() {
        assertTrue(manager.getActiveThemes(conversation).isEmpty())
    }
}
