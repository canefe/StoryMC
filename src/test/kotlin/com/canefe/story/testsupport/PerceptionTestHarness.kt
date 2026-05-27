package com.canefe.story.testsupport

import com.canefe.story.perception.AffordanceSnapshot
import com.canefe.story.perception.LocationSnapshot
import com.canefe.story.perception.NpcSnapshot
import com.canefe.story.perception.PerceptionContext
import com.google.protobuf.Message
import org.bukkit.Location

/**
 * In-memory [PerceptionContext] for unit-testing [PerceptionBroadcaster.runOnce]
 * without a live Bukkit world.
 *
 * Defaults:
 *   - `lightLevelAt` → 15 (full daylight; no light attenuation)
 *   - `hasLineOfSight` → true
 *   - `simPaused` → false
 *   - `gameTimeMs` → 0
 */
class FakePerceptionContext(
    val npcs: List<NpcSnapshot> = emptyList(),
    val locations: List<LocationSnapshot> = emptyList(),
    val affordances: List<AffordanceSnapshot> = emptyList(),
    val simPaused: Boolean = false,
    val gameTimeMs: Long = 0,
    val losFn: (String, Location) -> Boolean = { _, _ -> true },
    val lightFn: (Location) -> Int = { 15 },
) : PerceptionContext {
    /** Captured proto messages, in emit order. */
    val sentProtos: MutableList<Message> = mutableListOf()

    override fun npcs(): List<NpcSnapshot> = npcs
    override fun locations(): List<LocationSnapshot> = locations
    override fun affordances(): List<AffordanceSnapshot> = affordances

    override fun lightLevelAt(at: Location): Int = lightFn(at)
    override fun hasLineOfSight(perceiverCharId: String, to: Location): Boolean = losFn(perceiverCharId, to)

    override fun gameTimeMs(): Long = gameTimeMs
    override fun simPaused(): Boolean = simPaused

    override fun sendProto(message: Message) {
        sentProtos += message
    }
}
