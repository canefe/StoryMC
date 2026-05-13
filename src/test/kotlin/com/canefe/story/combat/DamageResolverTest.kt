package com.canefe.story.combat

import com.canefe.story.combat.resolution.DamageResolver
import com.canefe.story.combat.resolution.HitOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DamageResolverTest {
    @Test
    fun `unblocked when defender idle`() {
        val outcome = DamageResolver.resolve(CombatState.Idle, SwingDir.OVERHEAD)
        assertEquals(HitOutcome.Unblocked, outcome)
    }

    @Test
    fun `parry when block matches direction inside parry window`() {
        val state = CombatState.Blocking(SwingDir.OVERHEAD, parryWindowTicksLeft = 3)
        assertEquals(HitOutcome.Parry, DamageResolver.resolve(state, SwingDir.OVERHEAD))
    }

    @Test
    fun `perfect block when match past parry window`() {
        val state = CombatState.Blocking(SwingDir.LEFT, parryWindowTicksLeft = 0)
        assertEquals(HitOutcome.PerfectBlock, DamageResolver.resolve(state, SwingDir.LEFT))
    }

    @Test
    fun `partial block on adjacency`() {
        val state = CombatState.Blocking(SwingDir.LEFT, parryWindowTicksLeft = 0)
        assertEquals(HitOutcome.PartialBlock, DamageResolver.resolve(state, SwingDir.OVERHEAD))
    }

    @Test
    fun `bad block on opposite`() {
        val state = CombatState.Blocking(SwingDir.LEFT, parryWindowTicksLeft = 0)
        assertEquals(HitOutcome.BadBlock, DamageResolver.resolve(state, SwingDir.RIGHT))
    }

    @Test
    fun `adjacency table covers all 16 cells`() {
        val expected =
            mapOf(
                (SwingDir.OVERHEAD to SwingDir.OVERHEAD) to HitOutcome.PerfectBlock,
                (SwingDir.OVERHEAD to SwingDir.LEFT) to HitOutcome.PartialBlock,
                (SwingDir.OVERHEAD to SwingDir.RIGHT) to HitOutcome.PartialBlock,
                (SwingDir.OVERHEAD to SwingDir.THRUST) to HitOutcome.BadBlock,
                (SwingDir.LEFT to SwingDir.OVERHEAD) to HitOutcome.PartialBlock,
                (SwingDir.LEFT to SwingDir.LEFT) to HitOutcome.PerfectBlock,
                (SwingDir.LEFT to SwingDir.RIGHT) to HitOutcome.BadBlock,
                (SwingDir.LEFT to SwingDir.THRUST) to HitOutcome.PartialBlock,
                (SwingDir.RIGHT to SwingDir.OVERHEAD) to HitOutcome.PartialBlock,
                (SwingDir.RIGHT to SwingDir.LEFT) to HitOutcome.BadBlock,
                (SwingDir.RIGHT to SwingDir.RIGHT) to HitOutcome.PerfectBlock,
                (SwingDir.RIGHT to SwingDir.THRUST) to HitOutcome.PartialBlock,
                (SwingDir.THRUST to SwingDir.OVERHEAD) to HitOutcome.BadBlock,
                (SwingDir.THRUST to SwingDir.LEFT) to HitOutcome.PartialBlock,
                (SwingDir.THRUST to SwingDir.RIGHT) to HitOutcome.PartialBlock,
                (SwingDir.THRUST to SwingDir.THRUST) to HitOutcome.PerfectBlock,
            )
        for ((pair, want) in expected) {
            val (atk, blk) = pair
            val got = DamageResolver.resolve(CombatState.Blocking(blk, parryWindowTicksLeft = 0), atk)
            assertEquals(want, got, "atk=$atk vs blk=$blk")
        }
    }

    @Test
    fun `damage formula at mid stats matches base`() {
        val d =
            DamageResolver.damage(
                outcome = HitOutcome.Unblocked,
                weaponDmg = 4.0,
                attackerStrength = 0.5,
                attackerCombatSkill = 0.5,
                defenderCombatSkill = 0.5,
            )
        // base = 4 * (1 + 0) * (0.7 + 0.3) = 4.0
        assertEquals(4.0, d, 1e-9)
    }

    @Test
    fun `damage scales with strength and combat skill`() {
        val low =
            DamageResolver.damage(HitOutcome.Unblocked, 4.0, 0.0, 0.0, 0.5)
        val high =
            DamageResolver.damage(HitOutcome.Unblocked, 4.0, 1.0, 1.0, 0.5)
        assertTrue(high > low, "high stats should out-damage low")
    }

    @Test
    fun `partial block scales with defender combat skill (better defender = less damage)`() {
        val novice = DamageResolver.damage(HitOutcome.PartialBlock, 4.0, 0.5, 0.5, 0.0)
        val master = DamageResolver.damage(HitOutcome.PartialBlock, 4.0, 0.5, 0.5, 1.0)
        assertTrue(novice > master)
    }

    @Test
    fun `parry deals zero damage`() {
        val d = DamageResolver.damage(HitOutcome.Parry, 99.0, 1.0, 1.0, 0.0)
        assertEquals(0.0, d, 1e-9)
    }
}
