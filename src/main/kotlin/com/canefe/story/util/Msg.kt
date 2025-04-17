package com.canefe.story.util

import com.canefe.story.Story
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

object Msg {

    private val prefix = "<dark_gray>[<gold>Story</gold>]</dark_gray> "
    private val mm = Story.instance.miniMessage

    fun format(message: String): Component {
        return mm.deserialize(prefix + message)
    }

    fun send(sender: CommandSender, message: String) {
        sender.sendMessage(format(message))
    }

    fun sendNoPrefix(sender: CommandSender, message: String) {
        sender.sendMessage(mm.deserialize(message))
    }

    fun error(sender: CommandSender, message: String) {
        send(sender, "<red>$message")
    }

    fun success(sender: CommandSender, message: String) {
        send(sender, "<green>$message")
    }

    fun info(sender: CommandSender, message: String) {
        send(sender, "<gray>$message")
    }

    fun CommandSender.sendSuccess(message: String) {
        success(this, message)
    }

    fun CommandSender.sendError(message: String) {
        error(this, message)
    }

    fun CommandSender.sendInfo(message: String) {
        info(this, message)
    }

    fun CommandSender.sendRaw(message: String) {
        sendNoPrefix(this, message)
    }


}
