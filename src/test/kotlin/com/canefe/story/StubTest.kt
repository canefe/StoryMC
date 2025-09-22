package com.canefe.story

import com.canefe.story.command.base.CommandManager
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class StubTest {
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
    fun `realisticseasons API stub not used when plugin jar present`() {
        // When RealisticSeasons jar is on the classpath,
        // the actual class will not have our stub package-signature
        val clazz =
            runCatching {
                Class.forName("me.casperge.realisticseasons.api.SeasonsAPI")
            }.getOrNull() ?: run {
                Assumptions.assumeTrue(false, "RealisticSeasons not available on classpath, skipping test")
                return
            }

        // Assert it's not the stub by checking the code source
        val location =
            clazz.protectionDomain.codeSource
                ?.location
                ?.toString() ?: "unknown"

        // Our stub will come from "build/classes" or "build/tmp",
        // while the real jar will come from libs/RealisticSeasons.jar (or maven repo).
        println("RealisticSeasons loaded from: $location")
        assertFalse(location.contains("build"), "Stub classpath should not be used")
        assertTrue(
            location.contains("RealisticSeasons") || location.contains(".m2") || location.contains("libs"),
            "Should load from real plugin jar instead of stub: $location",
        )
    }

    @AfterEach
    fun teardown() {
        MockBukkit.unmock()
    }
}
