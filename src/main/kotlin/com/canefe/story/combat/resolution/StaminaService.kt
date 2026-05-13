package com.canefe.story.combat.resolution

import com.canefe.story.combat.CombatState
import com.canefe.story.combat.Combatant

/**
 * Per-tick stamina drain/regen per spec §4. Pure logic; no Bukkit deps.
 *
 * Plugin owns the runtime; sim owns the *max* (driven by `endurance`). Phase 3
 * wires the max from the sim snapshot. For now [Combatant.maxStamina] is
 * adapter-local.
 */
object StaminaService {
    /** Regen per tick when not blocking and not staggered. */
    const val PASSIVE_REGEN_PER_TICK = 0.5f

    /** Drain per tick while holding a passive block. */
    const val BLOCK_HOLD_DRAIN_PER_TICK = 2f

    /** Cost of committing a swing. Refunded on feint. */
    const val SWING_COMMIT_COST = 8f

    /** Cost of switching swing direction in first-half-of-windup. */
    const val DIRECTION_SWITCH_COST = 4f

    /**
     * Apply per-tick passive economy: blocking drains, idle regens. Skips
     * staggered combatants (they neither drain nor regen).
     */
    fun tick(combatants: Collection<Combatant>) {
        for (c in combatants) {
            when (c.currentState()) {
                is CombatState.Blocking -> c.applyStaminaDelta(-BLOCK_HOLD_DRAIN_PER_TICK)
                is CombatState.Staggered -> Unit
                is CombatState.Windup, is CombatState.Active, is CombatState.Recovery -> Unit
                CombatState.Idle -> c.applyStaminaDelta(PASSIVE_REGEN_PER_TICK)
            }
        }
    }

    /** Returns true if [c] has enough stamina to swing. */
    fun canSwing(c: Combatant): Boolean = c.stamina() >= SWING_COMMIT_COST
}
