package com.canefe.story.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for the inbound `go_to` execution path.
 *
 * Full arrival simulation needs a MockBukkit-loaded Story plugin, which is
 * broken on this branch (the combat package pulls packetevents classes that
 * aren't on the test classpath — see IntentExecutorOutcomeTest). So we pin the
 * two pieces that are pure/serializable:
 *   - the world-resolution rule (intent.world wins when non-blank, else the
 *     NPC's current world),
 *   - the GoToExecIntent wire shape (camelCase keys, defaults, eventType).
 */
class GoToExecTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `resolveGoToWorld prefers the intent world when non-blank`() {
        assertEquals("nether", IntentExecutor.resolveGoToWorld("nether", "world"))
    }

    @Test
    fun `resolveGoToWorld falls back to the npc world when blank`() {
        assertEquals("world", IntentExecutor.resolveGoToWorld("", "world"))
        assertEquals("world", IntentExecutor.resolveGoToWorld("   ", "world"))
    }

    @Test
    fun `GoToExecIntent decodes the camelCase wire shape`() {
        val wire = """
            {"characterId":"npc_1","intentId":"abc","x":1.0,"y":64.0,"z":2.0,
             "world":"world","arrivalRange":3.5,"stallTimeout":30.0,"maxDuration":300.0}
        """.trimIndent()
        val intent = json.decodeFromString<GoToExecIntent>(wire)
        assertEquals("npc_1", intent.characterId)
        assertEquals("abc", intent.intentId)
        assertEquals(1.0, intent.x)
        assertEquals(64.0, intent.y)
        assertEquals(2.0, intent.z)
        assertEquals("world", intent.world)
        assertEquals(3.5, intent.arrivalRange)
        assertEquals(30.0, intent.stallTimeout)
        assertEquals(300.0, intent.maxDuration)
        assertEquals("go_to", intent.eventType)
    }

    @Test
    fun `GoToExecIntent uses safe defaults for omitted fields`() {
        val intent = json.decodeFromString<GoToExecIntent>("""{"characterId":"npc_2"}""")
        assertEquals("npc_2", intent.characterId)
        assertEquals("", intent.intentId)
        assertEquals("", intent.world)
        assertEquals(0.0, intent.arrivalRange)
        assertEquals(0.0, intent.stallTimeout)
        assertEquals(0.0, intent.maxDuration)
    }

    @Test
    fun `GoToExecIntent round-trips through JSON`() {
        val intent = GoToExecIntent(
            characterId = "npc_3",
            intentId = "uuid-9",
            x = 10.0, y = 65.0, z = -4.0,
            world = "world",
            arrivalRange = 2.0,
        )
        val decoded = json.decodeFromString<GoToExecIntent>(json.encodeToString(intent))
        assertEquals(intent, decoded)
    }
}
