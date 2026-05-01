package com.canefe.story.affordance

import com.canefe.story.bridge.AffordanceTypeDef

/**
 * Holds the affordance type definitions received from story-sim via story-go.
 * Populated once on plugin connect, refreshed on reconnect.
 */
class AffordanceTypeRegistry {
    private val types = mutableMapOf<String, AffordanceTypeDef>()

    fun update(defs: List<AffordanceTypeDef>) {
        types.clear()
        defs.forEach { types[it.id] = it }
    }

    fun getById(id: String): AffordanceTypeDef? = types[id]

    fun all(): Collection<AffordanceTypeDef> = types.values

    val size: Int get() = types.size
}
