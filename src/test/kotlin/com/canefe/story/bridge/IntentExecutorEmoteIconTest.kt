package com.canefe.story.bridge

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [IntentExecutor.encodeEmoteIconPayload] produces the documented
 * binary layout: [long uuidMostSig][long uuidLeastSig][int entityId][UTF emoteId].
 *
 * Full end-to-end broadcast coverage (Bukkit.getOnlinePlayers → PacketEvents)
 * is skipped because MockBukkit cannot load the plugin in this environment
 * (NoClassDefFoundError on packetevents classes at classpath boot).
 * The broadcast path is trusted by structural parallel with PerceptionBroadcaster,
 * which uses the identical WrapperPlayServerPluginMessage + sendPacket pattern.
 */
class IntentExecutorEmoteIconTest {

    @Test
    fun `encodeEmoteIconPayload writes uuid then entityId then UTF emoteId`() {
        val uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val bytes = IntentExecutor.encodeEmoteIconPayload(
            uuid = uuid,
            entityId = 42,
            emoteId = "laugh",
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(uuid.mostSignificantBits, i.readLong(), "uuid mostSig first")
            assertEquals(uuid.leastSignificantBits, i.readLong(), "uuid leastSig second")
            assertEquals(42, i.readInt(), "entityId next as 4 bytes big-endian")
            assertEquals("laugh", i.readUTF(), "emoteId tail as Java-modified UTF")
        }
    }

    @Test
    fun `encodeEmoteIconPayload encodes null uuid as zeroed longs`() {
        val bytes = IntentExecutor.encodeEmoteIconPayload(
            uuid = null,
            entityId = 999,
            emoteId = "shock",
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(0L, i.readLong(), "null uuid → mostSig = 0")
            assertEquals(0L, i.readLong(), "null uuid → leastSig = 0")
            assertEquals(999, i.readInt())
            assertEquals("shock", i.readUTF())
        }
    }

    @Test
    fun `encodeEmoteIconPayload produces correct byte length`() {
        val emoteId = "cry"
        val bytes = IntentExecutor.encodeEmoteIconPayload(
            uuid = UUID.randomUUID(),
            entityId = 1,
            emoteId = emoteId,
        )
        // 8 (long mostSig) + 8 (long leastSig) + 4 (int entityId) + 2 (UTF length prefix) + emoteId bytes
        val expectedLength = 8 + 8 + 4 + 2 + emoteId.toByteArray(Charsets.UTF_8).size
        assertEquals(expectedLength, bytes.size)
    }
}
