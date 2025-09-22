package me.libraryaddict.disguise

import org.bukkit.entity.Entity

class DisguiseAPI {
    companion object {
        @JvmStatic
        fun isDisguised(entity: Entity): Boolean = false

        @JvmStatic
        fun undisguise(entity: Entity) { // no-op
        }
    }
}
