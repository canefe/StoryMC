package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.event.NPCInteractionListener
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.testutils.makeNpc
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPCRegistry
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class NPCInteractionListenerTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story
    private lateinit var listener: NPCInteractionListener

    // lets have a getter getNearbyNPCs that returns plugin.npcUtils.getNearbyNPCs so we dont have to
    fun getNearbyNPCs(
        player: Player,
        radius: Double,
    ) = plugin.npcUtils.getNearbyNPCs(player, radius)

    fun getNearbyPlayers(
        player: Player,
        radius: Double,
    ) = plugin.npcUtils.getNearbyPlayers(player, radius)

    @BeforeEach
    fun setUp() {
        val npcUtilsMock = mockk<NPCUtils>(relaxed = true)
        every { npcUtilsMock.getNearbyNPCs(any<Player>(), any()) } returns emptyList()

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
        plugin.npcUtils = npcUtilsMock

        listener = spyk(NPCInteractionListener(plugin))
        server.pluginManager.registerEvents(listener, plugin)

        plugin.saveDefaultConfig()
        plugin.reloadConfig()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `onPlayerChat cancels event and schedules processing`() {
        val player: Player = server.addPlayer("Alice")

        // Minimal stubs for required Paper API types
        val renderer = mockk<ChatRenderer>(relaxed = true)
        val message = Component.text("hello world")
        val signedMessage = mockk<SignedMessage>(relaxed = true)

        val event =
            AsyncChatEvent(
                false, // async
                player,
                setOf<Audience>(player),
                renderer,
                message,
                message,
                signedMessage,
            )

        server.pluginManager.callEvent(event)

        server.scheduler.performTicks(1)

        // Assert cancelled
        assert(event.isCancelled)

        // Let scheduled task run
        server.scheduler.performOneTick()

        // Optional: check what was serialized
        val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
        assert(plain == "hello world")
    }

    @Test
    fun `player joins existing NPC conversation if nearby NPC already in conversation`() {
        val alice = server.addPlayer("Alice")
        val guard = makeNpc("Guard")
        val shopkeeper = makeNpc("Shopkeeper")

        // There exists a conversation for Guard already
        val existingConversation = plugin.conversationManager.startConversation(listOf(guard, shopkeeper))
        Assertions.assertNotNull(existingConversation)

        every { getNearbyNPCs(alice, any()) } returns listOf(guard, shopkeeper)
        every { getNearbyPlayers(alice, any()) } returns emptyList()

        // Fire async chat
        val event =
            AsyncChatEvent(
                false,
                alice,
                setOf<Audience>(alice),
                mockk<ChatRenderer>(relaxed = true),
                Component.text("hi"),
                Component.text("hi"),
                mockk<SignedMessage>(relaxed = true),
            )
        server.pluginManager.callEvent(event)

        // Process scheduled sync task from onPlayerChat
        server.scheduler.performTicks(1)

        // Assert: Alice ended up in the existing convo (not a new one)
        //
        existingConversation.thenApply { existingConversation ->
            Assertions.assertNotNull(existingConversation)
            Assertions.assertTrue(existingConversation.players.contains(alice.uniqueId))
            verify(exactly = 1) { plugin.conversationManager.joinConversation(alice, existingConversation, any()) }
            // Make sure no brand-new conversation was started implicitly
            // (We rely on the fact getConversation(alice) returns the existing one)
            Assertions.assertEquals(existingConversation, plugin.conversationManager.getConversation(alice))
        }
    }

    @Test
    fun `nobody nearby - no conversation created`() {
        val alice = server.addPlayer("Alice")

        every { getNearbyNPCs(alice, any()) } returns emptyList()
        every { getNearbyPlayers(alice, any()) } returns emptyList()

        val fakeNearbyEntities =
            NPCInteractionListener.NearbyEntities(
                npcs = emptyList(),
                players = emptyList(),
                allInteractableNPCs = emptyList(),
            )

        val mockRegistry = mockk<NPCRegistry>()
        every { mockRegistry.isNPC(any()) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        every { listener.gatherNearbyEntities(any(), any()) } returns fakeNearbyEntities

        val event =
            AsyncChatEvent(
                false,
                alice,
                setOf<Audience>(alice),
                mockk(relaxed = true),
                Component.text("anyone here?"),
                Component.text("anyone here?"),
                mockk(relaxed = true),
            )
        server.pluginManager.callEvent(event)
        server.scheduler.performTicks(1)

        val convo = plugin.conversationManager.getConversation(alice)
        Assertions.assertNull(convo)
        // Assertions.assertTrue(convo!!.players.contains(alice.uniqueId))
        // Assertions.assertTrue(convo.npcs.isEmpty())
    }

    @Test
    fun `player is nearby to another player in conversation - joins that conversation`() {
        val alice = server.addPlayer("Alice")
        val bob = server.addPlayer("Bob")
        val guard = makeNpc("Guard")

        every { getNearbyNPCs(bob, any()) } returns listOf(guard)
        every { getNearbyPlayers(bob, any()) } returns emptyList()
        every { getNearbyNPCs(alice, any()) } returns listOf(guard)
        every { getNearbyPlayers(alice, any()) } returns listOf(bob)

        val mockRegistry = mockk<NPCRegistry>(relaxed = true)
        every { mockRegistry.isNPC(bob) } returns false
        every { mockRegistry.isNPC(alice) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        val fakeNearbyEntities =
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = emptyList(),
                allInteractableNPCs = listOf(guard),
            )
        every { listener.gatherNearbyEntities(bob, any()) } returns fakeNearbyEntities

        // Stub for Alice initially (same area as Bob+Guard)
        val aliceNearby =
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = listOf(bob),
                allInteractableNPCs = listOf(guard),
            )
        every { listener.gatherNearbyEntities(alice, any()) } returns aliceNearby

        // Let bob already be in a conversation with the guard

        val bobEvent =
            AsyncChatEvent(
                false,
                bob,
                setOf<Audience>(bob),
                mockk(relaxed = true),
                Component.text("anyone here?"),
                Component.text("anyone here?"),
                mockk(relaxed = true),
            )
        server.pluginManager.callEvent(bobEvent)
        server.scheduler.performTicks(1)

        val existingConversation = plugin.conversationManager.getConversation(bob)
        Assertions.assertNotNull(existingConversation)
        Assertions.assertTrue(existingConversation!!.players.contains(bob.uniqueId))

        // Now Alice chats - she should join Bob's existing conversation

        // Fire async chat
        val event =
            AsyncChatEvent(
                false,
                alice,
                setOf<Audience>(alice),
                mockk<ChatRenderer>(relaxed = true),
                Component.text("hi"),
                Component.text("hi"),
                mockk<SignedMessage>(relaxed = true),
            )
        server.pluginManager.callEvent(event)

        // Process scheduled sync task from onPlayerChat
        server.scheduler.performOneTick()
        server.scheduler.performOneTick()
        server.scheduler.performTicks(100)

        // Assert: Alice ended up in the existing convo (not a new one)
        Assertions.assertEquals(1, plugin.conversationManager.activeConversations.size)

        // Process scheduled sync task from onPlayerChat
        server.scheduler.performTicks(1)

        val convo = plugin.conversationManager.getConversation(alice)
        Assertions.assertNotNull(convo)
        Assertions.assertEquals(existingConversation, convo)
        Assertions.assertTrue(convo!!.players.contains(alice.uniqueId))
    }

    // Two players and 1 npcs in one conversation.
    // One player goes away and talks to another npc - starts a new conversation, should not be in the previous one.
    @Test
    fun `player leaves conversation and starts a new one with different npc`() {
        // Clear all existing conversations
        plugin.conversationManager.activeConversations.forEach { convo ->
            plugin.conversationManager.endConversation(convo, true)
        }
        val alice = server.addPlayer("Alice")
        val bob = server.addPlayer("Bob")
        val guard = makeNpc("Guard")
        val shopkeeper = makeNpc("Shopkeeper")
        val mockRegistry = mockk<NPCRegistry>(relaxed = true)
        every { mockRegistry.isNPC(bob) } returns false
        every { mockRegistry.isNPC(alice) } returns false
        CitizensAPI.setNPCRegistry(mockRegistry)

        val fakeNearbyEntities =
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = listOf(alice),
                allInteractableNPCs = listOf(guard),
            )
        every { listener.gatherNearbyEntities(bob, any()) } returns fakeNearbyEntities

        // Stub for Alice initially (same area as Bob+Guard)
        val aliceNearby =
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(guard),
                players = listOf(bob),
                allInteractableNPCs = listOf(guard),
            )
        every { listener.gatherNearbyEntities(alice, any()) } returns aliceNearby

        // Alice, Bob and Guard are in the same spot, make someone initiate conversation (event)
        every { getNearbyNPCs(bob, any()) } returns listOf(guard)
        every { getNearbyPlayers(bob, any()) } returns listOf(alice)

        val bobEvent =
            AsyncChatEvent(
                false,
                bob,
                setOf<Audience>(bob),
                mockk(relaxed = true),
                Component.text("anyone here?"),
                Component.text("anyone here?"),
                mockk(relaxed = true),
            )

        server.pluginManager.callEvent(bobEvent)
        server.scheduler.performOneTick()

        val cm = spyk(plugin.conversationManager)
        every { cm.scheduleProximityCheck(any()) } returns Unit
        plugin.conversationManager = cm

        // assert that conversation exists with both players and guard
        val existingConversation = plugin.conversationManager.getConversation(bob)!!

        existingConversation.players.contains(alice.uniqueId)
        existingConversation.players.contains(bob.uniqueId)
        existingConversation.npcs.contains(guard)

        // Now Alice walks to the shop and talks to the shopkeeper
        every { getNearbyNPCs(alice, any()) } returns listOf(shopkeeper)
        every { getNearbyPlayers(alice, any()) } returns emptyList()

        val aliceAtShop =
            NPCInteractionListener.NearbyEntities(
                npcs = listOf(shopkeeper),
                players = emptyList(),
                allInteractableNPCs = listOf(shopkeeper),
            )
        every { listener.gatherNearbyEntities(alice, any()) } returns aliceAtShop

        val aliceEvent =
            AsyncChatEvent(
                false,
                alice,
                setOf<Audience>(alice),
                mockk(relaxed = true),
                Component.text("hello shopkeeper"),
                Component.text("hello shopkeeper"),
                mockk(relaxed = true),
            )
        server.pluginManager.callEvent(aliceEvent)
        server.scheduler.performTicks(1)
        // Assert: Alice ended up in a brand-new convo (not the existing one)
        Assertions.assertEquals(2, plugin.conversationManager.activeConversations.size)
        val newConvo = plugin.conversationManager.getConversation(alice)
        Assertions.assertNotNull(newConvo)
        Assertions.assertNotEquals(existingConversation, newConvo)
        Assertions.assertTrue(newConvo!!.players.contains(alice.uniqueId))
        Assertions.assertFalse(newConvo.players.contains(bob.uniqueId))
        Assertions.assertTrue(newConvo.npcs.contains(shopkeeper))
        Assertions.assertFalse(newConvo.npcs.contains(guard))
    }
}
