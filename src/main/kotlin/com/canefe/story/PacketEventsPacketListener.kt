package com.canefe.story

import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage

class PacketEventsPacketListener : PacketListener {
    public override fun onPacketReceive(event: PacketReceiveEvent) {
        // The user represents the player.
        val user: User = event.getUser()
        // Identify what kind of packet it is.
        if (event.getPacketType() !== PacketType.Play.Client.CHAT_MESSAGE) return
        // Use the correct wrapper to process this packet.
        val chatMessage = WrapperPlayClientChatMessage(event)
        // Access the data within the wrapper using its "getters"
        val message = chatMessage.getMessage()
        // Check if the message is "ping"
        if (message.equals("ping", ignoreCase = true)) {
            // Respond with a "pong" message to the client.
            user.sendMessage("pong")
        }
    }
}
