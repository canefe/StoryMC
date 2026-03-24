package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.CharacterRecord
import com.canefe.story.api.character.CharacterSkills
import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.Conversation
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.StubStoryNPC
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.relationship.RelationshipManager
import com.canefe.story.service.AIResponseService
import com.canefe.story.testutils.makeStoryNpc
import com.canefe.story.util.TimeService
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture

class NPCResponseServiceTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story
    private lateinit var npcResponseService: NPCResponseService

    // Mocked dependencies
    private lateinit var mockNPCContextGenerator: NPCContextGenerator
    private lateinit var mockAIResponseService: AIResponseService
    private lateinit var mockTimeService: TimeService
    private lateinit var mockRelationshipManager: RelationshipManager

    @BeforeEach
    fun setUp() {
        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()
        plugin = MockBukkit.load(Story::class.java)
        plugin.characterRegistry = mockk(relaxed = true)

        mockkStatic(CommandAPI::class)
        every { CommandAPI.onLoad(any()) } just Runs
        every { CommandAPI.onEnable() } just Runs
        every { CommandAPI.onDisable() } just Runs

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs

        // Mock CitizensAPI
        val mockRegistry = mockk<NPCRegistry>(relaxed = true)
        every { mockRegistry.isNPC(any()) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        // Mock Bukkit
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns emptyList()

        // Initialize mocked dependencies
        mockNPCContextGenerator = mockk(relaxed = true)
        mockAIResponseService = mockk(relaxed = true)
        mockTimeService = mockk(relaxed = true)
        mockRelationshipManager = mockk(relaxed = true)

        // Set up plugin dependencies
        plugin.npcContextGenerator = mockNPCContextGenerator
        plugin.aiResponseService = mockAIResponseService
        plugin.timeService = mockTimeService
        plugin.relationshipManager = mockRelationshipManager

        // Mock other required services
        plugin.conversationManager = mockk(relaxed = true)
        plugin.characterRegistry = mockk(relaxed = true)
        plugin.locationManager = mockk(relaxed = true)
        plugin.lorebookManager = mockk(relaxed = true)
        plugin.npcMessageService = mockk(relaxed = true)
        plugin.typingSessionManager = mockk(relaxed = true)
        plugin.promptService = mockk(relaxed = true)

        // Initialize the service under test
        npcResponseService = spyk(NPCResponseService(plugin))
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Nested
    inner class DetermineNextSpeakerTests {
        @Test
        fun `determineNextSpeaker should return first NPC when only one available`() {
            // Arrange
            val npc = makeStoryNpc("Guard")
            val conversation =
                Conversation(
                    id = 1,
                    _players = arrayListOf(),
                    initialNPCs = listOf(npc),
                )

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertEquals("Guard", result)
        }

        @Test
        fun `determineNextSpeaker should return null when no NPCs available`() {
            // Arrange
            val conversation =
                Conversation(
                    id = 1,
                    _players = arrayListOf(),
                    initialNPCs = emptyList(),
                )

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertNull(result)
        }

        @Test
        fun `determineNextSpeaker should handle AI response and return valid NPC name`() {
            // Arrange
            val npc1 = makeStoryNpc("Guard")
            val npc2 = makeStoryNpc("Merchant")
            val conversation =
                Conversation(
                    id = 1,
                    initialNPCs = listOf(npc1, npc2),
                    _players = arrayListOf(),
                )

            // Mock AI response
            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("Guard")

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertEquals("Guard", result)
        }

        @Test
        fun `determineNextSpeaker should fallback to first NPC when AI returns invalid name`() {
            // Arrange
            val npc1 = makeStoryNpc("Guard")
            val npc2 = makeStoryNpc("Merchant")
            val conversation =
                Conversation(
                    id = 1,
                    initialNPCs = listOf(npc1, npc2),
                    _players = arrayListOf(),
                )

            // Mock AI response with invalid NPC name
            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("InvalidNPC")

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertEquals("Guard", result) // Should fallback to first NPC
        }

        @Test
        fun `determineNextSpeaker should handle AI response errors gracefully`() {
            // Arrange
            val npc1 = makeStoryNpc("Guard")
            val npc2 = makeStoryNpc("Merchant")
            val conversation =
                Conversation(
                    id = 1,
                    initialNPCs = listOf(npc1, npc2),
                    _players = arrayListOf(),
                )

            // Mock AI response failure
            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.failedFuture(RuntimeException("AI Error"))

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertEquals("Guard", result) // Should fallback to first NPC on error
        }

        @Test
        fun `determineNextSpeaker should exclude muted NPCs from selection`() {
            // Arrange
            val npc1 = makeStoryNpc("Guard")
            val npc2 = makeStoryNpc("Merchant")
            val conversation =
                Conversation(
                    id = 1,
                    initialNPCs = listOf(npc1, npc2),
                    _players = arrayListOf(),
                )
            conversation.muteNPC(npc1)

            // Mock AI response
            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("Merchant")

            // Act
            val result = npcResponseService.determineNextSpeaker(conversation).get()

            // Assert
            assertEquals("Merchant", result)
        }
    }

    @Nested
    inner class GenerateNPCMemoryTests {
        private fun makeTestCharacter(name: String): Character {
            val stubNpc = StubStoryNPC(name)
            return AICharacter(
                npc = stubNpc,
                name = name,
                role = "test",
                skills = CharacterSkills(plugin.skillManager.createProviderForNPC(name)),
            )
        }

        @Test
        fun `generateNPCMemory should return null when NPC does not exist`() {
            // Arrange
            every { plugin.characterRegistry.getByName("NonExistentNPC") } returns null
            every { plugin.characterRegistry.getByStoryNPC(any()) } returns null

            // Act
            val result =
                npcResponseService
                    .generateNPCMemory(makeTestCharacter("NonExistentNPC"), "event", "Some context")
                    .get()

            // Assert
            assertNull(result)
        }

        @Test
        fun `generateNPCMemory should create memory for existing NPC`() {
            // Arrange
            val record = mockk<CharacterRecord>(relaxed = true)
            every { plugin.characterRegistry.getByName("TestNPC") } returns record
            every { plugin.characterRegistry.getByStoryNPC(any()) } returns record

            val npcContext = mockk<NPCContext>(relaxed = true)
            every { npcContext.context } returns "Test NPC context"
            every { mockNPCContextGenerator.getOrCreateContextForNPC(any<StoryNPC>()) } returns npcContext
            every { mockNPCContextGenerator.getOrCreateContextForNPC(any<Character>()) } returns npcContext

            // Mock the conversation summarization to complete successfully
            every {
                npcResponseService.summarizeConversationForSingleNPC(any(), any<Character>())
            } returns CompletableFuture.completedFuture(null)

            // Act — memory is now stored externally so result is null
            val result =
                npcResponseService.generateNPCMemory(makeTestCharacter("TestNPC"), "event", "Some context").get()

            // Assert — memory creation delegates to external storage, local result is null
            assertNull(result)
        }

        @Test
        fun `generateNPCMemory should handle different memory types`() {
            // Arrange
            val record = mockk<CharacterRecord>(relaxed = true)
            every { plugin.characterRegistry.getByName("TestNPC") } returns record
            every { plugin.characterRegistry.getByStoryNPC(any()) } returns record

            val npcContext = mockk<NPCContext>(relaxed = true)
            every { npcContext.context } returns "Test NPC context"
            every { mockNPCContextGenerator.getOrCreateContextForNPC(any<StoryNPC>()) } returns npcContext
            every { mockNPCContextGenerator.getOrCreateContextForNPC(any<Character>()) } returns npcContext

            // Mock the conversation summarization
            every {
                npcResponseService.summarizeConversationForSingleNPC(any(), any<Character>())
            } returns CompletableFuture.completedFuture(null)

            // Test different memory types
            val memoryTypes = listOf("event", "conversation", "observation", "experience")

            memoryTypes.forEach { type ->
                // Act — memory is stored externally, result is null
                val result =
                    npcResponseService.generateNPCMemory(makeTestCharacter("TestNPC"), type, "Some context").get()

                // Assert — memory creation delegates to external storage, local result is null
                assertNull(result)
            }
        }
    }

    @Nested
    inner class EvaluateMemorySignificanceTests {
        @Test
        fun `evaluateMemorySignificance should return significance value from AI response`() {
            // Arrange
            val memoryContent = "Player helped the NPC with a task"
            val expectedSignificance = 3.5

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture(expectedSignificance.toString())

            // Act
            val result = npcResponseService.evaluateMemorySignificance(memoryContent).get()

            // Assert
            assertEquals(expectedSignificance, result, 0.01)
        }

        @Test
        fun `evaluateMemorySignificance should clamp values to valid range`() {
            // Arrange
            val memoryContent = "Player helped the NPC with a task"

            // Test values outside valid range
            val testCases =
                listOf(
                    "0.5" to 1.0, // Below minimum
                    "6.0" to 5.0, // Above maximum
                    "3.0" to 3.0, // Within range
                )

            testCases.forEach { (aiResponse, expectedValue) ->
                every { plugin.getAIResponse(any(), lowCost = true) } returns
                    CompletableFuture.completedFuture(aiResponse)

                // Act
                val result = npcResponseService.evaluateMemorySignificance(memoryContent).get()

                // Assert
                assertEquals(expectedValue, result, 0.01)
            }
        }

        @Test
        fun `evaluateMemorySignificance should handle invalid AI response`() {
            // Arrange
            val memoryContent = "Player helped the NPC with a task"

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("invalid_number")

            // Act
            val result = npcResponseService.evaluateMemorySignificance(memoryContent).get()

            // Assert
            assertEquals(1.0, result, 0.01) // Should default to minimum value
        }

        @Test
        fun `evaluateMemorySignificance should handle empty AI response`() {
            // Arrange
            val memoryContent = "Player helped the NPC with a task"

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("")

            // Act
            val result = npcResponseService.evaluateMemorySignificance(memoryContent).get()

            // Assert
            assertEquals(1.0, result, 0.01) // Should default to minimum value
        }
    }

    @Nested
    inner class GenerateNPCNameTests {
        @Test
        fun `generateNPCName should return generated name from AI`() {
            // Arrange
            val location = "Village"
            val role = "Guard"
            val context = "A village guard"
            val expectedName = "Marcus Ironwood"

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture(expectedName)

            // Mock location manager
            every { plugin.locationManager.getLocation(location) } returns null
            every { plugin.npcContextGenerator.getGeneralContexts() } returns
                listOf("General context")
            every { plugin.lorebookManager.findLoresByKeywords(any()) } returns emptyList()

            // Act
            val result = npcResponseService.generateNPCName(location, role, context).get()

            // Assert
            assertEquals(expectedName, result)
        }

        @Test
        fun `generateNPCName should return fallback name when AI response is empty`() {
            // Arrange
            val location = "Village"
            val role = "Guard"
            val context = "A village guard"

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture("")

            // Mock location manager
            every { plugin.locationManager.getLocation(location) } returns null
            every { plugin.npcContextGenerator.getGeneralContexts() } returns
                listOf("General context")
            every { plugin.lorebookManager.findLoresByKeywords(any()) } returns emptyList()

            // Act
            val result = npcResponseService.generateNPCName(location, role, context).get()

            // Assert
            assertTrue(result.startsWith("Generated_NPC_"))
        }

        @Test
        fun `generateNPCName should return fallback name when AI response is null`() {
            // Arrange
            val location = "Village"
            val role = "Guard"
            val context = "A village guard"

            every { plugin.getAIResponse(any(), lowCost = true) } returns
                CompletableFuture.completedFuture(null)

            // Mock location manager
            every { plugin.locationManager.getLocation(location) } returns null
            every { plugin.npcContextGenerator.getGeneralContexts() } returns
                listOf("General context")
            every { plugin.lorebookManager.findLoresByKeywords(any()) } returns emptyList()

            // Act
            val result = npcResponseService.generateNPCName(location, role, context).get()

            // Assert
            assertTrue(result.startsWith("Generated_NPC_"))
        }
    }
}
