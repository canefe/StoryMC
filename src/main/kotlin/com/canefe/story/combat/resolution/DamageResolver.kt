package com.canefe.story.combat.resolution

import com.canefe.story.combat.CombatState
import com.canefe.story.combat.SwingDir

/**
 * Pure outcome + damage math per spec §6. Stateless object, fully unit-testable.
 */
object DamageResolver {
    /** Stamina drain on the defender for a given outcome (spec §4 cost table). */
    fun defenderStaminaDrain(outcome: HitOutcome): Float =
        when (outcome) {
            HitOutcome.Unblocked -> 0f
            HitOutcome.Parry -> -6f // refund
            HitOutcome.PerfectBlock -> 4f
            HitOutcome.PartialBlock -> 8f
            HitOutcome.BadBlock -> 12f
        }

    fun resolve(
        defenderState: CombatState,
        attackDir: SwingDir,
    ): HitOutcome {
        val blocking = defenderState as? CombatState.Blocking
            ?: return HitOutcome.Unblocked
        val rel = relation(attackDir, blocking.dir)
        return when {
            rel == DirRelation.MATCH && blocking.parryWindowTicksLeft > 0 -> HitOutcome.Parry
            rel == DirRelation.MATCH -> HitOutcome.PerfectBlock
            rel == DirRelation.ADJACENT -> HitOutcome.PartialBlock
            rel == DirRelation.OPPOSITE -> HitOutcome.BadBlock
            else -> HitOutcome.Unblocked
        }
    }

    /**
     * Final damage per spec §6:
     *   base_dmg = weapon_dmg × (1 + strength_mod) × combat_skill_mod
     *   strength_mod = (strength - 0.5) × 0.6
     *   combat_skill_mod = 0.7 + combat_skill × 0.6
     *   final = base_dmg × outcome_multiplier(defenderCombatSkill)
     */
    fun damage(
        outcome: HitOutcome,
        weaponDmg: Double,
        attackerStrength: Double,
        attackerCombatSkill: Double,
        defenderCombatSkill: Double,
    ): Double {
        val strengthMod = (attackerStrength - 0.5) * 0.6
        val skillMod = 0.7 + attackerCombatSkill * 0.6
        val base = weaponDmg * (1.0 + strengthMod) * skillMod
        val multiplier =
            when (outcome) {
                HitOutcome.Unblocked -> 1.0
                HitOutcome.Parry -> 0.0
                HitOutcome.PerfectBlock -> 0.0
                HitOutcome.PartialBlock -> 0.30 + (1.0 - defenderCombatSkill) * 0.20
                HitOutcome.BadBlock -> 0.70 + (1.0 - defenderCombatSkill) * 0.20
            }
        return base * multiplier
    }

    private enum class DirRelation { MATCH, ADJACENT, OPPOSITE }

    /** Adjacency table per spec §6. */
    private fun relation(
        attack: SwingDir,
        block: SwingDir,
    ): DirRelation =
        when (attack) {
            SwingDir.OVERHEAD ->
                when (block) {
                    SwingDir.OVERHEAD -> DirRelation.MATCH
                    SwingDir.LEFT, SwingDir.RIGHT -> DirRelation.ADJACENT
                    SwingDir.THRUST -> DirRelation.OPPOSITE
                }
            SwingDir.LEFT ->
                when (block) {
                    SwingDir.LEFT -> DirRelation.MATCH
                    SwingDir.OVERHEAD, SwingDir.THRUST -> DirRelation.ADJACENT
                    SwingDir.RIGHT -> DirRelation.OPPOSITE
                }
            SwingDir.RIGHT ->
                when (block) {
                    SwingDir.RIGHT -> DirRelation.MATCH
                    SwingDir.OVERHEAD, SwingDir.THRUST -> DirRelation.ADJACENT
                    SwingDir.LEFT -> DirRelation.OPPOSITE
                }
            SwingDir.THRUST ->
                when (block) {
                    SwingDir.THRUST -> DirRelation.MATCH
                    SwingDir.LEFT, SwingDir.RIGHT -> DirRelation.ADJACENT
                    SwingDir.OVERHEAD -> DirRelation.OPPOSITE
                }
        }
}

enum class HitOutcome {
    Unblocked,
    Parry,
    PerfectBlock,
    PartialBlock,
    BadBlock,
}
