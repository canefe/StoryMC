package com.canefe.story

import com.canefe.story.command.base.CommandManager
import com.canefe.story.command.story.location.LocationCommandUtils
import com.canefe.story.conversation.ConversationMessage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.Locale.getDefault
import java.util.concurrent.TimeUnit

class PromptIntegrationTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Story

    @BeforeEach
    fun setUp() {
        System.setProperty("mockbukkit", "true")
        server = MockBukkit.mock()

        mockkConstructor(CommandManager::class)
        every { anyConstructed<CommandManager>().registerCommands() } just Runs

        plugin = MockBukkit.load(Story::class.java)

        // If an API key is provided in the environment, inject it into the plugin config so
        // production code that reads plugin.config.openAIKey will see it. This avoids changing
        // production code while allowing integration tests to use a real key.

        plugin.saveDefaultConfig()
        plugin.reloadConfig()

        val envKey =
            System.getenv("OPENROUTER_API_KEY") ?: System.getenv("OPENAI_API_KEY") ?: ""
        if (envKey.isNotBlank()) {
            plugin.config.openAIKey = envKey
        }
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // CreateLocationCommand adds a location with AI-generated context
    @Test
    fun `create location command adds location with AI-generated context`() {
        // This is more of an end-to-end test that the command works and uses PromptService
        // Skip this test if the plugin config doesn't have an API key (won't fail CI)
        Assumptions.assumeTrue(
            plugin.config.openAIKey.isNotBlank(),
            "OpenRouter/OpenAI API key not set in plugin config; skipping LLM integration test",
        )
        plugin.config.maxTokens = 512
        plugin.config.aiModel = "meta-llama/llama-3.3-70b-instruct" // a cheaper model for testing

        val player = server.addPlayer()

        plugin.locationManager.createLocation(
            "Harbor",
            player.location,
        )

        val temp = plugin.config.openAIKey
        plugin.config.openAIKey = temp
        // Verify the location was created with some context
        val location = plugin.locationManager.getLocation("Harbor")
        kotlin.test.assertNotNull(location)
        println("Created location context: ${location.context}")

        // Build prompt using PromptService so we're testing prompts.yml integration
        // Also let's assume there are existing lore and see if LLM is using them
        val promptText =
            plugin.promptService.getLocationContextGenerationPrompt(
                "Kingdom of Valoria, self-claimed by King Harald in response of" +
                    " High King of Skyrim handing over the kingdom to Kingdom of Eldora. Kingdom of Valoria is" +
                    "supported by the following holds: Windhelm (capital), Dawnstar, Winterhold, Riften and Whiterun",
            )

        val prompts =
            listOf(
                ConversationMessage("system", promptText),
                ConversationMessage(
                    "user",
                    "Create location description for 'Harbor Market'," +
                        " a market in city Riften.",
                ),
            )

        // Request the AI response (with a timeout)
        val future = plugin.getAIResponse(prompts)

        val result = future.get(30, TimeUnit.SECONDS)
        // Basic assertions: not null and contains one or two expected keywords
        kotlin.test.assertNotNull(result)
        println("LLM result: $result")

        // parse the result as json: use gson
        val gson = com.google.gson.Gson()
        val jsonObject =
            try {
                gson.fromJson(result, com.google.gson.JsonObject::class.java)
            } catch (e: com.google.gson.JsonSyntaxException) {
                null
            }
        kotlin.test.assertNotNull(jsonObject, "LLM result should be valid JSON")
        kotlin.test.assertTrue(
            jsonObject.has("description"),
            "LLM result JSON should have 'description' field",
        )
        kotlin.test.assertTrue(
            jsonObject.has("recent_events"),
            "LLM result JSON should have 'recent_events' field",
        )

        // We expect the LLM to mention Valoria and Eldora (the lore we provided) instead of TES Skyrim Lore
        val listContext = LocationCommandUtils().parseLocationContextResponse(result)
        // check the list items whether they contain valoria or eldora or harald (its a list)
        listContext.forEach { item ->
            val itemLower = item.lowercase(getDefault())
            kotlin.test.assertTrue(
                itemLower.contains("valoria") ||
                    itemLower.contains("eldora") ||
                    itemLower.contains("harald") ||
                    itemLower.contains("riften") ||
                    itemLower.contains("market"),
                "Location context should mention Valoria or Eldora or Harald or Riften or market",
            )
        }

        // Append generated context to the location and save
        location.context.addAll(listContext)
        plugin.locationManager.saveLocation(location)

        // Verify the location now has context entries
        val updatedLocation = plugin.locationManager.getLocation("Harbor")
        kotlin.test.assertNotNull(updatedLocation)
        println("Updated location context: ${updatedLocation.context}")
        kotlin.test.assertTrue(
            updatedLocation.context.isNotEmpty(),
            "Location should have AI-generated context entries",
        )
        kotlin.test.assertTrue(
            updatedLocation.context.any {
                val itLower = it.lowercase(getDefault())
                itLower.contains("valoria") ||
                    itLower.contains("eldora") ||
                    itLower.contains("harald") ||
                    itLower.contains("riften") ||
                    itLower.contains("market")
            },
            "At least one context entry should mention Valoria or Eldora or Harald or Riften or market",
        )
    }
}
