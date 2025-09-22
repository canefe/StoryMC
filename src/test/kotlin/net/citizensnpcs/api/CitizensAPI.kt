package net.citizensnpcs.api

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.entity.Entity

object CitizensAPI {
    private var registry: NPCRegistry =
        object : NPCRegistry {
            override fun isNPC(entity: Entity) = false

            override fun getNPC(entity: Entity) = null

            override fun iterator(): Iterator<NPC> = emptyList<NPC>().iterator()
        }

    @JvmStatic
    fun getNPCRegistry(): NPCRegistry = registry

    @JvmStatic
    fun setNPCRegistry(custom: NPCRegistry) {
        registry = custom
    }

    @JvmStatic
    fun getNPCRegistries(): Iterable<NPCRegistry> = listOf(registry)
}
