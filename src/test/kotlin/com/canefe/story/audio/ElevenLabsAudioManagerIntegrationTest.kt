package com.canefe.story.audio

import com.canefe.story.Story
import com.canefe.story.command.base.CommandManager
import dev.jorel.commandapi.CommandAPI
import io.mockk.*
import org.bukkit.entity.Player
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.assertFalse

/**
 * Integration test for ElevenLabsAudioManager that tests real API calls and audio conversion.
 *
 * This test requires:
 * 1. ELEVENLABS_API_KEY environment variable to be set (using local.properties)
 * 2. Internet connection to reach ElevenLabs API
 * 3. Valid ElevenLabs API key with sufficient credits
 *
 *
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfEnvironmentVariable(named = "ELEVENLABS_API_KEY", matches = ".+")
class ElevenLabsAudioManagerIntegrationTest {
    private lateinit var plugin: Story
    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var audioManager: ElevenLabsAudioManager
    private lateinit var mockPlayer: Player

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
        audioManager = ElevenLabsAudioManager(plugin)
        mockPlayer = Mockito.mock(Player::class.java)

        // Configure the audio manager with test API key
        val apiKey = System.getenv("ELEVENLABS_API_KEY")
        assertNotNull(apiKey, "ELEVENLABS_API_KEY environment variable must be set")
        assertTrue(apiKey.isNotEmpty(), "ELEVENLABS_API_KEY cannot be empty")

        // Set the API key in the plugin config
        plugin.config.elevenLabsApiKey = apiKey

        // Load configuration
        audioManager.loadConfig()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun testIsConfigured() {
        assertTrue(
            audioManager.isConfigured(),
            "Audio manager should be configured with valid API key",
        )
    }

    @Test
    @Order(2)
    @DisplayName("Should generate speech from text using real ElevenLabs API")
    fun testGenerateSpeechRealAPI() {
        val testText = "Hello"
        val voiceId = "EXAVITQu4vr4xnSDxMaL" // Default ElevenLabs voice

        val future = audioManager.generateSpeech(testText, voiceId)
        val audioData = future.get(30, TimeUnit.SECONDS) // 30 second timeout

        assertNotNull(audioData, "Audio data should not be null")
        assertTrue(audioData.isNotEmpty(), "Audio data should not be empty")
        assertTrue(audioData.size > 1000, "Audio data should be substantial (>1KB)")

        println("Generated audio data size: ${audioData.size} bytes")
    }

    @Test
    @Order(3)
    @DisplayName("Should convert MP3 to WAV format correctly")
    fun testMp3ToWavConversion() {
        val testText = "Testing"
        val voiceId = "EXAVITQu4vr4xnSDxMaL"

        // Generate speech (returns WAV data after conversion)
        val future = audioManager.generateSpeech(testText, voiceId)
        val wavData = future.get(30, TimeUnit.SECONDS)

        assertNotNull(wavData, "WAV data should not be null")
        assertTrue(wavData.isNotEmpty(), "WAV data should not be empty")

        // Verify it's valid WAV data by trying to read it with AudioSystem
        AudioSystem.getAudioInputStream(ByteArrayInputStream(wavData)).use { audioInputStream ->
            val audioFormat = audioInputStream.format

            // Verify WAV format properties
            assertEquals(
                AudioFormat.Encoding.PCM_SIGNED,
                audioFormat.encoding,
                "Should be PCM signed encoding",
            )
            assertEquals(
                44100f,
                audioFormat.sampleRate,
                0.1f,
                "Should be 44.1kHz sample rate",
            )
            assertEquals(16, audioFormat.sampleSizeInBits, "Should be 16-bit")
            assertTrue(audioFormat.channels in 1..2, "Should be mono or stereo")
            assertFalse(audioFormat.isBigEndian, "Should be little endian")

            println("WAV format: $audioFormat")
            println("WAV data size: ${wavData.size} bytes")
        }
    }
}
