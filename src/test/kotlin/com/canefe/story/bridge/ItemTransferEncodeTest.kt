package com.canefe.story.bridge

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemTransferEncodeTest {
    @Test
    fun encodeRoundTripsThroughTheDocumentedLayout() {
        val bytes = ItemTransferPacketBridge.encode(
            fromEntityId = 11,
            toEntityId = 22,
            materialId = "minecraft:bread",
            customModelData = -1,
            qty = 3,
            reasonOrdinal = 0,
        )
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            assertEquals(11, i.readInt())
            assertEquals(22, i.readInt())
            assertEquals("minecraft:bread", i.readUTF())
            assertEquals(-1, i.readInt())
            assertEquals(3, i.readShort().toInt())
            assertEquals(0, i.readByte().toInt())
        }
    }

    @Test
    fun reasonOrdinalMapsTradeGiftGive() {
        assertEquals(0, ItemTransferPacketBridge.reasonOrdinal("trade"))
        assertEquals(1, ItemTransferPacketBridge.reasonOrdinal("gift"))
        assertEquals(2, ItemTransferPacketBridge.reasonOrdinal("give"))
        assertEquals(2, ItemTransferPacketBridge.reasonOrdinal("unknown"))
    }
}
