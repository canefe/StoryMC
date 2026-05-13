package com.canefe.story.combat.ai

/**
 * Pure probability formulas per spec §7. Stateless; trivially unit-testable.
 *
 * `combatSkill` is in [0,1]. `pressure` is a multiplier ≥ 0 supplied by the
 * brain (rises when the target is also winding up, or has low stamina).
 */
object DecisionWeights {
    /** P(smart-pick the unguarded direction). Random pick is the complement. */
    fun pAttackUnguarded(combatSkill: Double): Double =
        (0.4 + 0.5 * combatSkill).coerceIn(0.0, 1.0)

    /** P(parry instead of passive block) on a defend decision. */
    fun pParry(combatSkill: Double): Double =
        (0.1 + 0.7 * combatSkill).coerceIn(0.0, 1.0)

    /** P(feint check passes during first-half-of-windup). */
    fun pFeint(
        combatSkill: Double,
        pressure: Double,
    ): Double = (0.0 + 0.6 * combatSkill * pressure).coerceIn(0.0, 1.0)

    /** Defender-direction read accuracy on a defend decision. */
    fun directionReadAccuracy(combatSkill: Double): Double = combatSkill.coerceIn(0.0, 1.0)
}
