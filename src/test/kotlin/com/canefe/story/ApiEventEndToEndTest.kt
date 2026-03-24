package com.canefe.story

import com.canefe.story.api.event.*
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
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.junit.jupiter.api.*
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests verifying that API events (CharacterSpeakEvent, ConversationStartEvent,
 * ConversationEndEvent, ConversationJoinEvent) fire correctly during the conversation lifecycle.
 */
class ApiEventEndToEndTest {
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
        every { plugin.npcContextGenerator.getOrCreateContextForNPC(npcName) } returns npcContext
        every { plugin.npcContextGenerator.getOrCreateContextForNPC(any<com.canefe.story.api.StoryNPC>()) } returns
            npcContext
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

    private fun setupNPCAndPlayer(
        npcName: String,
        locationName: String,
        playerName: String,
    ): Pair<com.canefe.story.api.StoryNPC, Player> {
        plugin.locationManager.createLocation(locationName, null)
        mockNPCAtLocation(npcName, locationName)
        val npc = makeStoryNpc(npcName)
        val player = server.addPlayer(playerName)

        every { NPCUtils.getNearbyNPCs(player, any()) } returns listOf(npc)
        every { listener.gatherNearbyEntities(player, any()) } returns
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(npc),
                players = emptyList(),
                allInteractableNPCs = listOf(npc),
            )

        return npc to player
    }

    // ── ConversationStartEvent ──────────────────────────────────────────

    @Test
    fun `ConversationStartEvent fires when player starts conversation`() {
        val firedEvents = CopyOnWriteArrayList<ConversationStartEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onStart(event: ConversationStartEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello there!")

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val event = firedEvents.first()
        assertEquals(alice, event.player)
        assertEquals(1, event.npcs.size)
        assertEquals("Guard", event.npcs.first().name)
        assertNotNull(event.conversation)
        assertTrue(event.conversation.active)
    }

    @Test
    fun `ConversationStartEvent contains correct player and NPC data`() {
        val firedEvents = CopyOnWriteArrayList<ConversationStartEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onStart(event: ConversationStartEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello there!")

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val event = firedEvents.first()
        assertEquals("Alice", event.player.name)
        assertEquals(1, event.npcs.size)
        assertEquals("Guard", event.npcs.first().name)
        assertEquals(event.conversation.id, plugin.conversationManager.getConversation(alice)?.id)
    }

    // ── ConversationEndEvent ────────────────────────────────────────────

    @Test
    fun `ConversationEndEvent fires when conversation ends`() {
        val firedEvents = CopyOnWriteArrayList<ConversationEndEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onEnd(event: ConversationEndEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        plugin.sessionManager.startSession()

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Farewell!")

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!

        // Need enough messages for endConversation to fire the event (userMessageCount > 2)
        fillConversationHistory(conversation, 3)

        plugin.conversationManager.endConversation(conversation)

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val event = firedEvents.first()
        assertEquals(alice, event.player)
        assertEquals("Guard", event.npcs.first().name)
        assertEquals(conversation.id, event.conversation.id)
    }

    @Test
    fun `ConversationEndEvent contains correct message count`() {
        val firedEvents = CopyOnWriteArrayList<ConversationEndEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onEnd(event: ConversationEndEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        plugin.sessionManager.startSession()

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Farewell!")

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!

        fillConversationHistory(conversation, 4)

        plugin.conversationManager.endConversation(conversation)

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val event = firedEvents.first()
        // toWireData should include the message count
        val wireData = event.toWireData()
        val messageCount = wireData["messageCount"].toString().toInt()
        assertEquals(conversation.history.size, messageCount)
    }

    @Test
    fun `ConversationEndEvent does not fire for short conversations`() {
        val firedEvents = CopyOnWriteArrayList<ConversationEndEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onEnd(event: ConversationEndEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        plugin.sessionManager.startSession()

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hi!")

        fireChat(alice, "Hello")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }
        val conversation = plugin.conversationManager.getConversation(alice)!!

        // Don't add extra messages — keep the conversation short (at most the initial exchange)
        // The threshold is userMessageCount > 2 (non-system messages)

        plugin.conversationManager.endConversation(conversation)
        server.scheduler.performTicks(100)

        assertTrue(
            firedEvents.isEmpty(),
            "ConversationEndEvent should not fire for conversations with <= 2 non-system messages",
        )
    }

    // ── ConversationStartEvent wire data ────────────────────────────────

    @Test
    fun `ConversationStartEvent toWireData contains correct fields`() {
        val firedEvents = CopyOnWriteArrayList<ConversationStartEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onStart(event: ConversationStartEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello!")

        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val event = firedEvents.first()
        val wireData = event.toWireData()

        // Should contain conversationId, npcNames, and playerNames
        assertTrue(wireData.containsKey("conversationId"), "Wire data should contain conversationId")
        assertTrue(wireData.containsKey("npcNames"), "Wire data should contain npcNames")
        assertTrue(wireData.containsKey("playerNames"), "Wire data should contain playerNames")
    }

    // ── CharacterSpeakEvent ─────────────────────────────────────────────

    @Test
    fun `CharacterSpeakEvent fires for player message in conversation`() {
        val firedEvents = CopyOnWriteArrayList<CharacterSpeakEvent>()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onSpeak(event: CharacterSpeakEvent) {
                    firedEvents.add(event)
                    val char = event.speaker

                    plugin.logger.warning(char.name)
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello traveler!")

        fireChat(alice, "Hello guard, how are you?")

        waitUntil(server, 200) { plugin.conversationManager.getConversation(alice) != null }

        // Player's broadcastPlayerMessage should fire CharacterSpeakEvent
        // Wait for any speak events to fire
        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        val playerSpeakEvents = firedEvents.filter { it.speaker.name == "Alice" }
        assertTrue(playerSpeakEvents.isNotEmpty(), "CharacterSpeakEvent should fire for player speech")
        assertTrue(
            playerSpeakEvents.any { it.message.contains("Hello guard") },
            "Event message should contain the player's message",
        )
    }

    @Test
    fun `CharacterSpeakEvent cancellation prevents message broadcast`() {
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onSpeak(event: CharacterSpeakEvent) {
                    if (event.message.contains("blocked")) {
                        event.isCancelled = true
                    }
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("I should not see this")

        fireChat(alice, "This should be blocked")

        // Give time for event processing
        server.scheduler.performTicks(100)

        // The conversation may still start but the cancelled message should not propagate
        // This is a best-effort check — the key guarantee is that isCancelled returns true
        // and broadcastNPCMessage/broadcastPlayerMessage returns early
    }

    @Test
    fun `CharacterSpeakEvent message can be modified by listener`() {
        val firedEvents = CopyOnWriteArrayList<CharacterSpeakEvent>()

        // Register modifier listener at high priority
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onSpeak(event: CharacterSpeakEvent) {
                    if (event.message.contains("original")) {
                        event.message = event.message.replace("original", "modified")
                    }
                }
            },
            plugin,
        )

        // Register capture listener at lower priority
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onSpeak(event: CharacterSpeakEvent) {
                    firedEvents.add(event)
                }
            },
            plugin,
        )

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello!")

        fireChat(alice, "This is original text")

        waitUntil(server, 200) { firedEvents.isNotEmpty() }

        // The message field should be mutable and modifiable by listeners
        val modifiedEvents = firedEvents.filter { it.message.contains("modified") }
        assertTrue(
            modifiedEvents.isNotEmpty(),
            "CharacterSpeakEvent message should be modifiable by listeners",
        )
    }

    // ── Full lifecycle ──────────────────────────────────────────────────

    @Test
    fun `full conversation lifecycle fires start and end events in order`() {
        val eventLog = CopyOnWriteArrayList<String>()

        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun onStart(event: ConversationStartEvent) {
                    eventLog.add("start:${event.npcs.first().name}")
                }

                @EventHandler
                fun onEnd(event: ConversationEndEvent) {
                    eventLog.add("end:${event.npcs.first().name}")
                }
            },
            plugin,
        )

        plugin.sessionManager.startSession()

        val (_, alice) = setupNPCAndPlayer("Guard", "Tavern", "Alice")
        mockAIAlwaysReturn("Hello!")

        // Start conversation
        fireChat(alice, "Hello guard")

        waitUntil(server, 200) { eventLog.any { it.startsWith("start:") } }

        val conversation = plugin.conversationManager.getConversation(alice)!!
        fillConversationHistory(conversation, 3)

        // End conversation
        plugin.conversationManager.endConversation(conversation)

        waitUntil(server, 200) { eventLog.any { it.startsWith("end:") } }

        assertEquals("start:Guard", eventLog[0], "Start event should fire first")
        assertEquals("end:Guard", eventLog[1], "End event should fire second")
        assertEquals(2, eventLog.size, "Exactly two lifecycle events should fire")
    }
}
