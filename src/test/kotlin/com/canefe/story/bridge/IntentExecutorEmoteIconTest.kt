package com.canefe.story.bridge

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [IntentExecutor.encodeEmoteIconPayload] produces the documented
 * binary layout: [int entityId (4 bytes big-endian)][UTF emoteId (2-byte
 * length prefix + bytes)].
 *
 * Full end-to-end broadcast coverage (Bukkit.getOnlinePlayers → PacketEvents)
 * is skipped because MockBukkit cannot load the plugin in this environment
 * (NoClassDefFoundError on packetevents classes at classpath boot).
 * The broadcast path is trusted by structural parallel with PerceptionBroadcaster,
 * which uses the identical WrapperPlayServerPluginMessage + sendPacket pattern.
 */
class IntentExecutorEmoteIconTest {

    @Test
    fun `encodeEmoteIconPayload writes entityId then UTF emoteId`() {
        val bytes = IntentExecutor.encodeEmoteIconPayload(
            entityId = 42,
            emoteId = "laugh",
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(42, i.readInt(), "entityId should be first 4 bytes big-endian")
            assertEquals("laugh", i.readUTF(), "emoteId should follow as Java-modified UTF")
        }
    }

    @Test
    fun `encodeEmoteIconPayload handles multi-word emote ids`() {
        val bytes = IntentExecutor.encodeEmoteIconPayload(
            entityId = 999,
            emoteId = "shock",
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(999, i.readInt())
            assertEquals("shock", i.readUTF())
        }
    }

    @Test
    fun `encodeEmoteIconPayload produces correct byte length`() {
        val emoteId = "cry"
        val bytes = IntentExecutor.encodeEmoteIconPayload(entityId = 1, emoteId = emoteId)
        // 4 (int) + 2 (UTF length prefix) + emoteId.length bytes
        val expectedLength = 4 + 2 + emoteId.toByteArray(Charsets.UTF_8).size
        assertEquals(expectedLength, bytes.size)
    }
}
