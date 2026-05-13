package com.canefe.story.combat

import com.canefe.story.combat.resolution.HitDetector
import com.canefe.story.combat.resolution.WeaponClass
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Lightweight in-memory Combatant for geometry tests — no Bukkit plugin load. */
private class FakeCombatant(
    private val world: World,
    private val xPos: Double,
    private val zPos: Double,
    private val yawDeg: Float = 0f,
    private val state: CombatState = CombatState.Idle,
) : Combatant {
    override val entityId: Int = nextId.getAndIncrement()
    override val uniqueId: UUID = UUID.randomUUID()

    override fun facingYaw(): Float = yawDeg

    override fun eyeLocation(): Location = Location(world, xPos, 64.0, zPos, yawDeg, 0f)

    override fun stamina(): Float = 100f

    override fun maxStamina(): Float = 100f

    override fun applyStaminaDelta(delta: Float) {}

    override fun takeDamage(
        amount: Double,
        attacker: Combatant?,
    ) {}

    override fun currentState(): CombatState = state

    override fun transitionTo(state: CombatState) {}

    companion object {
        val nextId = java.util.concurrent.atomic.AtomicInteger(1)
    }
}

class HitDetectorTest {
    private val world: World = org.mockito.Mockito.mock(World::class.java)

    @Test
    fun `thrust hits target directly in front within reach`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f) // facing +Z
        val target = FakeCombatant(world, 0.0, 3.0)
        val hits = HitDetector.detect(attacker, SwingDir.THRUST, WeaponClass.SWORD, listOf(attacker, target))
        assertEquals(listOf(target), hits)
    }

    @Test
    fun `thrust misses target out of reach`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f)
        val target = FakeCombatant(world, 0.0, 10.0) // sword thrust reach = 3.2 * 1.3 = 4.16
        val hits = HitDetector.detect(attacker, SwingDir.THRUST, WeaponClass.SWORD, listOf(attacker, target))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `thrust misses target behind attacker`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f) // facing +Z
        val target = FakeCombatant(world, 0.0, -2.0)
        val hits = HitDetector.detect(attacker, SwingDir.THRUST, WeaponClass.SWORD, listOf(attacker, target))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `overhead cone hits target within 45deg arc`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f) // facing +Z
        // 30 degrees off forward, within reach
        val angled = FakeCombatant(world, -1.5, 2.5)
        val hits =
            HitDetector.detect(
                attacker,
                SwingDir.OVERHEAD,
                WeaponClass.SWORD,
                listOf(attacker, angled),
            )
        assertEquals(listOf(angled), hits)
    }

    @Test
    fun `overhead cone misses target behind attacker`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f) // facing +Z
        val behind = FakeCombatant(world, 0.0, -2.0)
        val hits =
            HitDetector.detect(
                attacker,
                SwingDir.OVERHEAD,
                WeaponClass.SWORD,
                listOf(attacker, behind),
            )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `cone hits all candidates inside reach and arc`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f)
        val a = FakeCombatant(world, 0.5, 2.0)
        val b = FakeCombatant(world, -0.5, 2.0)
        val c = FakeCombatant(world, 0.0, 1.5)
        val hits =
            HitDetector.detect(
                attacker,
                SwingDir.OVERHEAD,
                WeaponClass.SWORD,
                listOf(attacker, a, b, c),
            )
        assertEquals(setOf(a, b, c), hits.toSet())
    }

    @Test
    fun `attacker is excluded from its own hit list`() {
        val attacker = FakeCombatant(world, 0.0, 0.0, yawDeg = 0f)
        val hits =
            HitDetector.detect(attacker, SwingDir.OVERHEAD, WeaponClass.SWORD, listOf(attacker))
        assertTrue(hits.isEmpty())
    }
}
