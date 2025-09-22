package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.service.AIResponseService
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture

class StoryTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story

    @BeforeEach
    fun setUp() {
        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()

        mockkStatic(CommandAPI::class)
        every { CommandAPI.onLoad(any()) } just Runs
        every { CommandAPI.onEnable() } just Runs
        every { CommandAPI.onDisable() } just Runs

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs

        // Load the plugin
        plugin = MockBukkit.load(Story::class.java)

        plugin.commandManager = mockk(relaxed = true)
    }

    @Test
    fun pluginEnables() {
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun `getAIResponse delegates to aiResponseService`() {
        // Arrange
        val mockAIResponseService = mockk<AIResponseService>(relaxed = true)

        plugin.aiResponseService = mockAIResponseService

        val prompts = listOf(ConversationMessage("user", "Hello"))
        val expected = "world"

        every {
            mockAIResponseService.getAIResponseAsync(prompts, lowCost = false)
        } returns CompletableFuture.completedFuture(expected)

        // Act
        val result = plugin.getAIResponse(prompts).get()

        // Assert
        assertEquals(expected, result)
    }

    @AfterEach
    fun teardown() {
        MockBukkit.unmock()
    }
}
