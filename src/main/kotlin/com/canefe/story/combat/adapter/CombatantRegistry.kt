package com.canefe.story.combat.adapter

import com.canefe.story.combat.Combatant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps Bukkit entity-id and UUID to the [Combatant] adapter. Adapters are
 * created lazily by [DirectionalCombatService] and registered here so the
 * state machine can look them up by either key.
 */
class CombatantRegistry {
    private val byEntityId = ConcurrentHashMap<Int, Combatant>()
    private val byUuid = ConcurrentHashMap<UUID, Combatant>()

    fun register(combatant: Combatant) {
        byEntityId[combatant.entityId] = combatant
        byUuid[combatant.uniqueId] = combatant
    }

    fun unregister(combatant: Combatant) {
        byEntityId.remove(combatant.entityId)
        byUuid.remove(combatant.uniqueId)
    }

    fun byEntityId(id: Int): Combatant? = byEntityId[id]

    fun byUuid(uuid: UUID): Combatant? = byUuid[uuid]

    fun all(): Collection<Combatant> = byEntityId.values
}
