package com.canefe.story.bridge

import com.canefe.story.Story
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Encodes and sends the `story:item_transfer` plugin message so the client can
 * render a floating item arcing from giver to receiver. Layout:
 *   int   fromEntityId
 *   int   toEntityId
 *   UTF   materialNamespacedId  (e.g. "minecraft:bread")
 *   int   customModelData       (-1 = none)
 *   short qty
 *   byte  reasonOrdinal         (0=trade, 1=gift, 2=give)
 */
class ItemTransferPacketBridge(private val plugin: Story) {
    fun send(
        audience: Collection<Player>,
        fromEntityId: Int,
        toEntityId: Int,
        materialId: String,
        customModelData: Int,
        qty: Int,
        reason: String,
    ) {
        val bytes = encode(fromEntityId, toEntityId, materialId, customModelData, qty, reasonOrdinal(reason))
        val packet = WrapperPlayServerPluginMessage(CHANNEL, bytes)
        for (player in audience) {
            try {
                PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
            } catch (e: Exception) {
                plugin.logger.warning("[ItemTransfer] send failed for ${player.name}: ${e.message}")
            }
        }
    }

    companion object {
        const val CHANNEL = "story:item_transfer"

        fun reasonOrdinal(reason: String): Int = when (reason.lowercase()) {
            "trade" -> 0
            "gift" -> 1
            else -> 2 // "give" / unknown
        }

        fun encode(
            fromEntityId: Int,
            toEntityId: Int,
            materialId: String,
            customModelData: Int,
            qty: Int,
            reasonOrdinal: Int,
        ): ByteArray {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.writeInt(fromEntityId)
                out.writeInt(toEntityId)
                out.writeUTF(materialId)
                out.writeInt(customModelData)
                out.writeShort(qty)
                out.writeByte(reasonOrdinal)
            }
            return baos.toByteArray()
        }
    }
}
