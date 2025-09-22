// kotlin
package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.ConversationManager
import com.canefe.story.testutils.makeNpc
import io.mockk.*
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPCRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationManagerTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story

    @BeforeEach
    fun setUp() {
        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs

        plugin = MockBukkit.load(Story::class.java)

        val mockRegistry = mockk<NPCRegistry>()
        every { mockRegistry.isNPC(any()) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        // Keep command manager relaxed to avoid CommandAPI side effects
        plugin.commandManager = mockk(relaxed = true)
        ConversationManager.reset()
        // Replace systems we will observe with mocks
        plugin.npcResponseService = mockk(relaxed = true)
        plugin.worldInformationManager = mockk(relaxed = true)
        plugin.npcContextGenerator = mockk(relaxed = true)
        plugin.sessionManager = mockk(relaxed = true)

        plugin.conversationManager =
            ConversationManager.getInstance(
                plugin,
                plugin.npcContextGenerator,
                plugin.npcResponseService,
                plugin.worldInformationManager,
            )
    }

    @AfterEach
    fun teardown() {
        MockBukkit.unmock()
    }

    @Nested
    inner class EndConversationTests {
        @Test
        fun `endConversation significant conversation triggers summarization`() {
            val npcService = plugin.npcResponseService

            every { npcService.summarizeConversation(any()) } returns CompletableFuture.completedFuture(null)

            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            // Start conversation and add two user messages (<= 2 -> not significant)
            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.addPlayerMessage(player, "hi")
            conversation.addPlayerMessage(player, "hello")
            conversation.addPlayerMessage(player, "hello")

            // act: call endConversation
            plugin.conversationManager.endConversation(conversation)

            verify(exactly = 1) { npcService.summarizeConversation(any()) }
        }

        @Test
        fun `endConversation short conversation does not summarize`() {
            // Arrange
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            // Ensure session/world handlers do nothing but are observable
            every { plugin.sessionManager.feed(any()) } just Runs
            every { plugin.worldInformationManager.processInformation(any()) } just Runs
            // If summarization is called, return a completed future (but we expect it not to be called)
            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture<Void>(null)

            // Start conversation and add two user messages (<= 2 -> not significant)
            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.addPlayerMessage(player, "hi")
            conversation.addPlayerMessage(player, "hello")

            // Make sure a proximity task was scheduled by startConversation
            assertTrue(plugin.conversationManager.getScheduledTasks().containsKey(conversation))

            // Act
            val future = plugin.conversationManager.endConversation(conversation)
            future.get() // wait for completion

            // Assert: summarization not called, world/session not called
            verify(exactly = 0) { plugin.npcResponseService.summarizeConversation(any()) }
            verify(exactly = 0) { plugin.worldInformationManager.processInformation(any()) }
            verify(exactly = 0) { plugin.sessionManager.feed(any()) }

            // Conversation removed and scheduled task cancelled
            assertNull(plugin.conversationManager.getConversation(player))
            assertFalse(plugin.conversationManager.getScheduledTasks().containsKey(conversation))
        }

        @Test
        fun `endConversation significant conversation triggers summarization and world and session`() {
            // Arrange
            val player = server.addPlayer("Bob")
            val npc = makeNpc("Guard")

            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture(null)

            every { plugin.sessionManager.feed(any()) } just Runs
            every { plugin.worldInformationManager.processInformation(any()) } just Runs
            // Summarization should be invoked for significant conversations (> 2 user messages)
            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture<Void>(null)

            // Start conversation and add three user messages (> 2 -> significant)
            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.addPlayerMessage(player, "msg1")
            conversation.addPlayerMessage(player, "msg2")
            conversation.addPlayerMessage(player, "msg3")

            // Ensure scheduled task exists
            assertTrue(plugin.conversationManager.getScheduledTasks().containsKey(conversation))

            // Act
            val future = plugin.conversationManager.endConversation(conversation)
            future.get() // wait for summarization + completion

            // Assert: summarization and world/session processing called
            verify(exactly = 1) { plugin.npcResponseService.summarizeConversation(any()) }
            verify(exactly = 1) { plugin.worldInformationManager.processInformation(any()) }
            verify(exactly = 1) { plugin.sessionManager.feed(any()) }

            // Conversation removed and scheduled task cancelled
            assertNull(plugin.conversationManager.getConversation(player))
            assertFalse(plugin.conversationManager.getScheduledTasks().containsKey(conversation))
        }

        @Test
        fun `duplicate endConversation only summarizes once`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture(null)

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.addPlayerMessage(player, "a")
            conversation.addPlayerMessage(player, "b")
            conversation.addPlayerMessage(player, "c")

            plugin.conversationManager.endConversation(conversation).get()
            plugin.conversationManager.endConversation(conversation).get()

            verify(exactly = 1) { plugin.npcResponseService.summarizeConversation(any()) }
        }

        @Test
        fun `endConversation with dontRemember skips summarization`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture(null)

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.addPlayerMessage(player, "m1")
            conversation.addPlayerMessage(player, "m2")
            conversation.addPlayerMessage(player, "m3")

            plugin.conversationManager.endConversation(conversation, dontRemember = true).get()

            verify(exactly = 0) { plugin.npcResponseService.summarizeConversation(any()) }
        }

        @Test
        fun `conversation is removed from repository after end`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            assertTrue(plugin.conversationManager.getAllActiveConversations().contains(conversation))

            plugin.conversationManager.endConversation(conversation).get()

            assertFalse(plugin.conversationManager.getAllActiveConversations().contains(conversation))
        }

        @Test
        fun `removeNPC ends conversation when no NPCs remain`() {
            // Arrange
            val player = server.addPlayer("Charlie")
            val npc = makeNpc("Guard")

            // Make summarization a no-op just in case
            every { plugin.npcResponseService.summarizeConversation(any()) } returns
                CompletableFuture.completedFuture(null)

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))

            // Act: remove the only NPC
            plugin.conversationManager.removeNPC(npc, conversation)

            // Assert: conversation is gone
            assertNull(plugin.conversationManager.getConversation(player))

            // No summarization invoked because no NPCs left
            verify(exactly = 0) { plugin.npcResponseService.summarizeConversation(any()) }

            // Scheduled task should also be cleaned up
            assertFalse(plugin.conversationManager.getScheduledTasks().containsKey(conversation))
        }
    }

    @Nested
    inner class StartConversationTests {
        @Test
        fun `startConversation creates new conversation`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            // Act: start a conversation
            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))

            // Assert: conversation is active
            assertTrue(plugin.conversationManager.getAllActiveConversations().contains(conversation))

            // Assert: the player is in the conversation
            assertEquals(conversation, plugin.conversationManager.getConversation(player))

            // Assert: the NPC is in the conversation
            assertEquals(conversation, plugin.conversationManager.getConversation(npc))

            // Assert: scheduled task is set up
            assertTrue(plugin.conversationManager.getScheduledTasks().containsKey(conversation))

            // Assert: conversation contains initial NPC
            assertTrue(conversation.npcs.contains(npc))
        }

        @Test
        fun `startConversation replaces existing one`() {
            val player = server.addPlayer("Alice")
            val npc1 = makeNpc("Guard1")
            val npc2 = makeNpc("Guard2")

            // Start first conversation
            val oldConversation = plugin.conversationManager.startConversation(player, listOf(npc1))
            assertTrue(plugin.conversationManager.getAllActiveConversations().contains(oldConversation))

            // Start a new conversation with a different NPC
            val newConversation = plugin.conversationManager.startConversation(player, listOf(npc2))

            // Assert old conversation is ended (removed)
            assertFalse(plugin.conversationManager.getAllActiveConversations().contains(oldConversation))

            // Assert new conversation is active
            assertTrue(plugin.conversationManager.getAllActiveConversations().contains(newConversation))

            // Also check that getConversation(player) returns the new one
            assertEquals(newConversation, plugin.conversationManager.getConversation(player))
        }
    }
}
