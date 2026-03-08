package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.testutils.makeNpc
import io.mockk.*
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPCRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture

class MessageHistorySummarizationTest {
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

        plugin.commandManager = mockk(relaxed = true)
        ConversationManager.reset()
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
    inner class ConversationMessageCounterTests {
        @Test
        fun `messagesSinceLastSummary increments on player messages`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            val conversation = Conversation(
                _players = mutableListOf(player.uniqueId),
                initialNPCs = listOf(npc),
            )

            assertEquals(0, conversation.messagesSinceLastSummary)

            conversation.addPlayerMessage(player, "hello")
            assertEquals(1, conversation.messagesSinceLastSummary)

            conversation.addPlayerMessage(player, "how are you")
            assertEquals(2, conversation.messagesSinceLastSummary)
        }

        @Test
        fun `messagesSinceLastSummary increments on NPC messages`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            val conversation = Conversation(
                _players = mutableListOf(player.uniqueId),
                initialNPCs = listOf(npc),
            )

            assertEquals(0, conversation.messagesSinceLastSummary)

            // addNPCMessage adds an assistant message (+1) and a "..." user message (not counted)
            conversation.addNPCMessage(npc, "Greetings traveler")
            assertEquals(1, conversation.messagesSinceLastSummary)
        }

        @Test
        fun `messagesSinceLastSummary does not increment on system messages`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            val conversation = Conversation(
                _players = mutableListOf(player.uniqueId),
                initialNPCs = listOf(npc),
            )

            conversation.addSystemMessage("A new player has joined")
            assertEquals(0, conversation.messagesSinceLastSummary)
        }

        @Test
        fun `messagesSinceLastSummary does not count ellipsis user messages`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            val conversation = Conversation(
                _players = mutableListOf(player.uniqueId),
                initialNPCs = listOf(npc),
            )

            // Simulate the pattern from addNPCMessage: assistant + "..."
            conversation.addNPCMessage(npc, "Hello")
            // assistant message counts as 1, "..." does not count
            assertEquals(1, conversation.messagesSinceLastSummary)

            // A real player message should count
            conversation.addPlayerMessage(player, "Hi back")
            assertEquals(2, conversation.messagesSinceLastSummary)
        }

        @Test
        fun `replaceHistoryWithSummary resets counter and replaces history`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")
            val conversation = Conversation(
                _players = mutableListOf(player.uniqueId),
                initialNPCs = listOf(npc),
            )

            // Add several messages
            conversation.addPlayerMessage(player, "msg1")
            conversation.addPlayerMessage(player, "msg2")
            conversation.addNPCMessage(npc, "response1")
            conversation.addPlayerMessage(player, "msg3")
            conversation.addNPCMessage(npc, "response2")

            assertEquals(5, conversation.messagesSinceLastSummary)

            val recentMessages = listOf(
                ConversationMessage("user", "Alice: msg3"),
                ConversationMessage("assistant", "Guard: response2"),
            )

            conversation.replaceHistoryWithSummary(
                "Summary of conversation so far: Alice and Guard discussed things.",
                recentMessages,
            )

            // Counter should be reset
            assertEquals(0, conversation.messagesSinceLastSummary)

            // History should contain summary + recent messages
            assertEquals(3, conversation.history.size)
            assertEquals("system", conversation.history[0].role)
            assertTrue(conversation.history[0].content.contains("Summary of conversation"))
            assertEquals("user", conversation.history[1].role)
            assertEquals("assistant", conversation.history[2].role)
        }
    }

    @Nested
    inner class SummarizationTriggerTests {
        @Test
        fun `summarization is not triggered below threshold`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            // Set threshold to 5 (default)
            plugin.configService.summarizationThreshold = 5

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            // Disable chat to prevent response generation
            conversation.chatEnabled = false

            // Add 4 messages (below threshold of 5)
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg1")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg2")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg3")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg4")

            // getAIResponse should NOT have been called for summarization
            verify(exactly = 0) { plugin.aiResponseService.getAIResponseAsync(any(), any()) }
        }

        @Test
        fun `summarization is triggered at threshold`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            plugin.configService.summarizationThreshold = 5

            // Mock the AI response for summarization
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture("Summary of conversation so far: things happened")

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            // Add 5 messages to hit the threshold
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg1")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg2")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg3")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg4")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg5")

            // getAIResponseAsync should have been called for summarization
            verify(atLeast = 1) { plugin.aiResponseService.getAIResponseAsync(any(), any()) }
        }

        @Test
        fun `summarization replaces old messages and keeps recent ones`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            plugin.configService.summarizationThreshold = 5

            val summaryText = "Summary of conversation so far: Alice talked to Guard about the kingdom."
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture(summaryText)

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            // Add 6 messages to trigger summarization with enough to split
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg1")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg2")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg3")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg4")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg5")
            plugin.conversationManager.addPlayerMessage(player, conversation, "msg6")

            // After summarization completes, history should be condensed
            // 1 summary system message + 4 recent messages kept
            assertTrue(conversation.history.size <= 6)
            assertEquals(0, conversation.messagesSinceLastSummary)

            // First message should be the summary
            val firstMessage = conversation.history[0]
            assertEquals("system", firstMessage.role)
            assertEquals(summaryText, firstMessage.content)
        }

        @Test
        fun `configurable threshold is respected`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            // Set a higher threshold
            plugin.configService.summarizationThreshold = 10

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            // Add 5 messages (below threshold of 10)
            repeat(5) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "msg${i + 1}")
            }

            // Should NOT trigger summarization at 5 with threshold of 10
            verify(exactly = 0) { plugin.aiResponseService.getAIResponseAsync(any(), any()) }

            // Now mock AI response and add more to reach 10
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture("Summary text")

            repeat(5) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "msg${i + 6}")
            }

            // Should now trigger summarization
            verify(atLeast = 1) { plugin.aiResponseService.getAIResponseAsync(any(), any()) }
        }

        @Test
        fun `summarization handles null AI response gracefully`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            plugin.configService.summarizationThreshold = 5

            // Return null response
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture(null)

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            // Add enough messages to trigger summarization
            repeat(6) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "msg${i + 1}")
            }

            // History should remain unchanged since AI returned null
            assertTrue(conversation.history.size >= 6)
            // Counter should still be at the accumulated value since no summary was applied
            assertTrue(conversation.messagesSinceLastSummary >= 5)
        }

        @Test
        fun `summarization handles AI exception gracefully`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            plugin.configService.summarizationThreshold = 5

            // Return a failed future
            val failedFuture = CompletableFuture<String?>()
            failedFuture.completeExceptionally(RuntimeException("API error"))
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns failedFuture

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            val historyBefore = conversation.history.size

            // Add enough messages to trigger summarization - should not throw
            repeat(6) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "msg${i + 1}")
            }

            // Conversation should still be functional
            assertTrue(conversation.history.isNotEmpty())
            assertTrue(conversation.active)
        }

        @Test
        fun `counter resets after successful summarization allowing re-trigger`() {
            val player = server.addPlayer("Alice")
            val npc = makeNpc("Guard")

            plugin.configService.summarizationThreshold = 5

            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture("Summary of conversation so far: round 1")

            val conversation = plugin.conversationManager.startConversation(player, listOf(npc))
            conversation.chatEnabled = false

            // First round: trigger summarization
            repeat(6) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "round1_msg${i + 1}")
            }

            // Counter should be reset after summarization
            assertEquals(0, conversation.messagesSinceLastSummary)

            // Update mock for second round
            every {
                plugin.aiResponseService.getAIResponseAsync(any(), any())
            } returns CompletableFuture.completedFuture("Summary of conversation so far: round 2")

            // Second round: add more messages to trigger again
            repeat(6) { i ->
                plugin.conversationManager.addPlayerMessage(player, conversation, "round2_msg${i + 1}")
            }

            // Should have been called twice total (once per round)
            verify(atLeast = 2) { plugin.aiResponseService.getAIResponseAsync(any(), any()) }
            assertEquals(0, conversation.messagesSinceLastSummary)
        }
    }
}
