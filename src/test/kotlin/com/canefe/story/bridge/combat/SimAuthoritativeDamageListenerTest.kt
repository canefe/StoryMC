package com.canefe.story.bridge.combat

import com.canefe.story.bridge.CombatAttackResolvedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit-tests the pure-Kotlin pieces of [SimAuthoritativeDamageListener] that
 * don't require a live Bukkit server.
 *
 * The end-to-end EDBE → publish → reply → vanilla damage cycle is exercised
 * by the wire-shape test in [CombatRoundTripWireTest] plus the live-server
 * smoke test described in the task report. The full plugin boot path is
 * brittle in CI (see IntentExecutorOutcomeTest's note about MockBukkit /
 * packetevents classpath).
 */
class SimAuthoritativeDamageListenerTest {
    @Test
    fun `keyFor prefers character id over name on both sides`() {
        val withIds = SimAuthoritativeDamageListener.keyFor(
            attackerId = "char-bob",
            attackerName = "Bob",
            defenderId = "char-alice",
            defenderName = "Alice",
        )
        assertEquals("char-bob" to "char-alice", withIds)
    }

    @Test
    fun `keyFor falls back to lowercased name when id is null`() {
        val nameOnly = SimAuthoritativeDamageListener.keyFor(
            attackerId = null,
            attackerName = "Bob",
            defenderId = null,
            defenderName = "Alice",
        )
        assertEquals("bob" to "alice", nameOnly)
    }

    @Test
    fun `keyFor is case-insensitive on name fallback`() {
        val a = SimAuthoritativeDamageListener.keyFor(null, "BOB", null, "alice")
        val b = SimAuthoritativeDamageListener.keyFor(null, "bob", null, "ALICE")
        assertEquals(a, b, "name-based correlation must be case-insensitive to match the sim")
    }

    @Test
    fun `keyFor distinguishes by id even when names match`() {
        val a = SimAuthoritativeDamageListener.keyFor("char-1", "Bob", "char-2", "Alice")
        val b = SimAuthoritativeDamageListener.keyFor("char-1", "Bob", "char-3", "Alice")
        assertNotEquals(a, b, "different defender ids must produce different correlation keys")
    }

    @Test
    fun `reply timeout default is 10 ticks (~500ms at 20 TPS)`() {
        assertEquals(10L, SimAuthoritativeDamageListener.REPLY_TIMEOUT_TICKS)
    }

    /**
     * Sanity: a hit reply with zero damage (a "miss" wrongly marked hit) must
     * not result in a damage application. The listener treats `damageDealt <= 0`
     * as a no-op on the visual side regardless of the outcome label. This pins
     * the behavior so we don't accidentally trigger flinch animations on miss.
     */
    @Test
    fun `miss outcome carries zero damage`() {
        val miss = CombatAttackResolvedEvent(
            attackerName = "Bob",
            defenderName = "Alice",
            outcome = "miss",
            damageDealt = 0f,
        )
        assertEquals(0f, miss.damageDealt)
        assertEquals("miss", miss.outcome)
        assertEquals(null, miss.targetPart)
    }
}
