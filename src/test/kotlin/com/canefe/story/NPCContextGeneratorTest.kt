package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.npc.NPCContextGenerator
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.name.NPCNameManager
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.testutils.makeNpc
import io.mockk.*
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Location
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class NPCContextGeneratorTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story
    private val generator: NPCContextGenerator by lazy { NPCContextGenerator(plugin) }

    @BeforeEach
    fun setUp() {
        val npcUtilsMock = mockk<NPCUtils>()
        every { npcUtilsMock.getNearbyNPCs(any<Player>(), any()) } returns emptyList()

        val mockRegistry = mockk<NPCRegistry>()
        every { mockRegistry.isNPC(any()) } returns false
        every { mockRegistry.iterator() } returns emptyList<NPC>().iterator()
        CitizensAPI.setNPCRegistry(mockRegistry)

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs

        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()
        plugin = MockBukkit.load(Story::class.java)
        plugin.commandManager = mockk(relaxed = true)

        // Stub dependencies so we can control behavior
        val world = server.addSimpleWorld("world")
        val fakeLocation = Location(world, 0.0, 64.0, 0.0)

        val npc =
            CitizensAPI
                .getNPCRegistry()
                .iterator()
                .asSequence()
                .firstOrNull()
                ?: makeNpc("Guard")

        // Preload generic NPCData
        val genericData =
            NPCData(
                name = npc.name,
                role = "Villager",
                storyLocation = plugin.locationManager.getLocation("Village"),
                context = "A placeholder context",
            ).apply {
                generic = true
                nameBank = "villagers"
            }

        plugin.npcDataManager.saveNPCData(npc.name, genericData)
        val alias =
            NPCNameManager.NPCAlias(
                canonicalName = "Guard_123",
                displayHandle = "G. 123",
                callsign = null,
            )

        plugin.saveDefaultConfig()
        plugin.reloadConfig()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `getOrCreateContextForNPC generates context with traits and quirks`() {
        val ctx: NPCContext? = generator.getOrCreateContextForNPC("Guard")

        assertNotNull(ctx)

        assertTrue(ctx.name == "Guard")
        assertTrue(ctx.context.contains("is"))
        assertTrue(ctx.context.contains("has the quirk of"))
        assertTrue(ctx.context.contains("is motivated by"))
        assertTrue(ctx.context.contains("and their flaw is"))
        assertTrue(ctx.context.contains("They speak in a"))
    }

    @Test
    fun `generic npc gets canonical name and temporary personality`() {
        val generator = NPCContextGenerator(plugin)

        val context = generator.getOrCreateContextForNPC("Guard")

        assertNotNull(context, "Context should not be null")
        // load npc data to check name
        val hel = plugin.npcDataManager.getAllNPCData()
        val npcData = plugin.npcDataManager.getNPCData("Tariq ibn Hakim")
        assertNotNull(npcData, "NPC data should not be null")
        assertEquals("Guard", context.name, "Canonical name should be resolved")
    }
}
