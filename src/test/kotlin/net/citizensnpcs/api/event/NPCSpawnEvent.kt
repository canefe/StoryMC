package net.citizensnpcs.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class NPCSpawnEvent : Event(false) {
    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }

    override fun getHandlers(): HandlerList = handlerList
}
