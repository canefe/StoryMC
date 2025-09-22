package net.citizensnpcs.api.npc

import org.bukkit.entity.Entity

interface NPCRegistry : Iterable<NPC> {
    fun isNPC(entity: Entity): Boolean

    fun getNPC(entity: Entity): NPC?
}
