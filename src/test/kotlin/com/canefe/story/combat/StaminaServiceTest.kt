package com.canefe.story.combat

import com.canefe.story.combat.resolution.StaminaService
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private class StaminaFake(
    private var state: CombatState = CombatState.Idle,
    initialStamina: Float = 100f,
    private val cap: Float = 100f,
) : Combatant {
    override val entityId: Int = 1
    override val uniqueId: UUID = UUID.randomUUID()

    var s: Float = initialStamina
        private set

    override fun facingYaw(): Float = 0f

    override fun eyeLocation(): Location = error("not used")

    override fun stamina(): Float = s

    override fun maxStamina(): Float = cap

    override fun applyStaminaDelta(delta: Float) {
        s = (s + delta).coerceIn(0f, cap)
    }

    override fun takeDamage(
        amount: Double,
        attacker: Combatant?,
    ) {}

    override fun currentState(): CombatState = state

    override fun transitionTo(state: CombatState) {
        this.state = state
    }
}

class StaminaServiceTest {
    @Test
    fun `idle regens passively`() {
        val c = StaminaFake(state = CombatState.Idle, initialStamina = 50f)
        StaminaService.tick(listOf(c))
        assertEquals(50f + StaminaService.PASSIVE_REGEN_PER_TICK, c.s, 1e-4f)
    }

    @Test
    fun `blocking drains per tick`() {
        val c = StaminaFake(state = CombatState.Blocking(SwingDir.OVERHEAD, 5), initialStamina = 50f)
        StaminaService.tick(listOf(c))
        assertEquals(50f - StaminaService.BLOCK_HOLD_DRAIN_PER_TICK, c.s, 1e-4f)
    }

    @Test
    fun `staggered neither drains nor regens`() {
        val c = StaminaFake(state = CombatState.Staggered(10), initialStamina = 50f)
        StaminaService.tick(listOf(c))
        assertEquals(50f, c.s, 1e-4f)
    }

    @Test
    fun `cannot swing below commit cost`() {
        val c = StaminaFake(initialStamina = 5f)
        assertFalse(StaminaService.canSwing(c))
        val ok = StaminaFake(initialStamina = 8f)
        assertTrue(StaminaService.canSwing(ok))
    }

    @Test
    fun `passive regen clamped at max`() {
        val c = StaminaFake(initialStamina = 99.9f)
        StaminaService.tick(listOf(c))
        assertEquals(100f, c.s, 1e-4f)
    }
}
