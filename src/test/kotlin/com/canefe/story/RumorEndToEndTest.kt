package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.event.NPCInteractionListener
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.testutils.makeStoryNpc
import com.canefe.story.testutils.waitUntil
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPCRegistry
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.junit.jupiter.api.*
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test: player chat → conversation → conversation end → rumors created.
 */
class RumorEndToEndTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story
    private lateinit var listener: NPCInteractionListener

    @BeforeEach
    fun setUp() {
        mockkObject(NPCUtils)
        every { NPCUtils.getNearbyNPCs(any<Player>(), any()) } returns emptyList()

        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()

        mockkStatic(CommandAPI::class)
        every { CommandAPI.onLoad(any()) } just Runs
        every { CommandAPI.onEnable() } just Runs
        every { CommandAPI.onDisable() } just Runs

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs
        plugin = MockBukkit.load(Story::class.java)
        plugin.characterRegistry = mockk(relaxed = true)
        plugin.commandManager = mockk(relaxed = true)

        val mockRegistry = mockk<NPCRegistry>(relaxed = true)
        every { mockRegistry.isNPC(any()) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        listener = spyk(NPCInteractionListener(plugin))
        server.pluginManager.registerEvents(listener, plugin)

        plugin.saveDefaultConfig()
        plugin.reloadConfig()

        plugin.configService.npcReactionsEnabled = false
        plugin.configService.autoModeEnabledByDefault = false
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        unmockkObject(NPCUtils)
    }

    private fun mockNPCAtLocation(
        npcName: String,
        locationName: String,
    ) {
        val location = StoryLocation(locationName)
        val npcContext =
            NPCContext(
                name = npcName,
                role = "guard",
                context = "A town guard.",
                location = location,
                avatar = "",
                memories = emptyList(),
            )
        plugin.npcContextGenerator = mockk(relaxed = true)
        every { plugin.npcContextGenerator.getOrCreateContextForNPC(any<com.canefe.story.api.StoryNPC>()) } returns
            npcContext
        every {
            plugin.npcContextGenerator.getOrCreateContextForNPC(
                any<com.canefe.story.api.character.Character>(),
            )
        } returns
            npcContext
        // Recreate ConversationManager so it picks up the mocked npcContextGenerator
        plugin.conversationManager =
            com.canefe.story.conversation.ConversationManager(
                plugin,
                plugin.npcContextGenerator,
                plugin.npcResponseService,
                plugin.worldInformationManager,
            )
    }

    private fun mockAIAlwaysReturn(response: String) {
        plugin.aiResponseService = mockk(relaxed = true)
        every { plugin.aiResponseService.getAIResponseAsync(any(), any()) } answers {
            CompletableFuture.completedFuture(response)
        }
    }

    private fun fireChat(
        player: Player,
        message: String,
    ) {
        val chatEvent =
            AsyncChatEvent(
                false,
                player,
                setOf<Audience>(player),
                mockk<ChatRenderer>(relaxed = true),
                Component.text(message),
                Component.text(message),
                mockk<SignedMessage>(relaxed = true),
            )
        server.pluginManager.callEvent(chatEvent)
        server.scheduler.performTicks(1)
    }

    private fun fillConversationHistory(
        conversation: com.canefe.story.conversation.Conversation,
        exchanges: Int = 2,
    ) {
        repeat(exchanges) { i ->
            conversation.addMessage(ConversationMessage("user", "Player message $i"))
            conversation.addMessage(ConversationMessage("assistant", "NPC response $i"))
        }
    }

    @Test
    fun `conversation end creates rumors via WorldInformationManager`() {
        // Start a session (required for rumors to be saved)
        plugin.sessionManager.startSession()
        assertTrue(plugin.sessionManager.hasActiveSession())

        // Create location + mock NPC context
        plugin.locationManager.createLocation("Tavern", null)
        mockNPCAtLocation("Guard", "Tavern")

        // Set up NPC and player
        val guard = makeStoryNpc("Guard")
        val alice = server.addPlayer("Alice")

        every { NPCUtils.getNearbyNPCs(alice, any()) } returns listOf(guard)
        every { listener.gatherNearbyEntities(alice, any()) } returns
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = emptyList(),
                allInteractableNPCs = listOf(guard),
            )

        // Mock AI: first calls are NPC conversation, last is WorldInformationManager analysis
        val rumorAnalysis =
            """
            ---
            Type: RUMOR
            Target: Tavern
            Importance: HIGH
            Information: The guard mentioned that bandits have been spotted near the eastern road
            ---
            """.trimIndent()

        mockAIAlwaysReturn(rumorAnalysis)

        // Player chats - starts conversation
        fireChat(alice, "Hello guard, any news?")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!

        // Add enough messages to pass userMessageCount > 2 check
        fillConversationHistory(conversation, 3)

        // No rumors before conversation end
        assertTrue(plugin.rumorManager.getAllRumors().isEmpty())

        // End conversation - triggers WorldInformationManager.processInformation()
        plugin.conversationManager.endConversation(conversation)

        // Wait for async AI call + rumor creation
        waitUntil(server, 400) { plugin.rumorManager.getAllRumors().isNotEmpty() }

        val rumors = plugin.rumorManager.getAllRumors()
        assertTrue(rumors.isNotEmpty(), "Rumors should have been created")

        val rumor = rumors.first()
        assertTrue(rumor.content.contains("bandits"), "Rumor should mention bandits: ${rumor.content}")
        assertEquals("Tavern", rumor.location)
        assertTrue(rumor.significance > 0)
    }

    @Test
    fun `no rumors created when no active session`() {
        // Do NOT start a session
        Assertions.assertFalse(plugin.sessionManager.hasActiveSession())

        plugin.locationManager.createLocation("Tavern", null)
        mockNPCAtLocation("Guard", "Tavern")
        val guard = makeStoryNpc("Guard")
        val alice = server.addPlayer("Alice")

        every { NPCUtils.getNearbyNPCs(alice, any()) } returns listOf(guard)
        every { listener.gatherNearbyEntities(alice, any()) } returns
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = emptyList(),
                allInteractableNPCs = listOf(guard),
            )

        val rumorAnalysis =
            """
            ---
            Type: RUMOR
            Target: Tavern
            Importance: HIGH
            Information: The guard mentioned bandits
            ---
            """.trimIndent()

        mockAIAlwaysReturn(rumorAnalysis)

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!
        fillConversationHistory(conversation, 3)

        plugin.conversationManager.endConversation(conversation)
        server.scheduler.performTicks(200)

        assertTrue(
            plugin.rumorManager.getAllRumors().isEmpty(),
            "No rumors should be created without an active session",
        )
    }

    @Test
    fun `multiple rumors from single conversation`() {
        plugin.sessionManager.startSession()
        plugin.locationManager.createLocation("Market", null)
        mockNPCAtLocation("Merchant", "Market")

        val merchant = makeStoryNpc("Merchant")
        val alice = server.addPlayer("Alice")

        every { NPCUtils.getNearbyNPCs(alice, any()) } returns listOf(merchant)
        every { listener.gatherNearbyEntities(alice, any()) } returns
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(merchant),
                players = emptyList(),
                allInteractableNPCs = listOf(merchant),
            )

        val multiRumorResponse =
            """
            ---
            Type: RUMOR
            Target: Market
            Importance: HIGH
            Information: A dragon was seen flying over the northern mountains
            ---
            Type: RUMOR
            Target: Market
            Importance: MEDIUM
            Information: The merchant guild is raising prices on all imported goods
            ---
            """.trimIndent()

        mockAIAlwaysReturn(multiRumorResponse)

        fireChat(alice, "What's new?")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!
        fillConversationHistory(conversation, 3)

        plugin.conversationManager.endConversation(conversation)

        waitUntil(server, 400) { plugin.rumorManager.getAllRumors().size >= 2 }

        val rumors = plugin.rumorManager.getAllRumors()
        assertEquals(2, rumors.size, "Two rumors should have been created")
        assertTrue(rumors.any { it.content.contains("dragon") })
        assertTrue(rumors.any { it.content.contains("prices") || it.content.contains("guild") })
        assertTrue(rumors.all { it.location == "Market" })
    }
}
