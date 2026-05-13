package com.canefe.story.combat.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecisionWeightsTest {
    @Test
    fun `pAttackUnguarded ranges from 40percent at zero skill to 90percent at one`() {
        assertEquals(0.4, DecisionWeights.pAttackUnguarded(0.0), 1e-9)
        assertEquals(0.65, DecisionWeights.pAttackUnguarded(0.5), 1e-9)
        assertEquals(0.9, DecisionWeights.pAttackUnguarded(1.0), 1e-9)
    }

    @Test
    fun `pParry ranges from 10percent at zero to 80percent at one`() {
        assertEquals(0.1, DecisionWeights.pParry(0.0), 1e-9)
        assertEquals(0.45, DecisionWeights.pParry(0.5), 1e-9)
        assertEquals(0.8, DecisionWeights.pParry(1.0), 1e-9)
    }

    @Test
    fun `pFeint scales with both skill and pressure`() {
        assertEquals(0.0, DecisionWeights.pFeint(0.0, 1.0), 1e-9)
        assertEquals(0.3, DecisionWeights.pFeint(0.5, 1.0), 1e-9)
        assertEquals(0.45, DecisionWeights.pFeint(0.5, 1.5), 1e-9)
        assertEquals(0.6, DecisionWeights.pFeint(1.0, 1.0), 1e-9)
    }

    @Test
    fun `pFeint clamped to one when skill x pressure exceeds`() {
        assertEquals(1.0, DecisionWeights.pFeint(1.0, 5.0), 1e-9)
    }

    @Test
    fun `directionReadAccuracy equals combat skill`() {
        assertEquals(0.0, DecisionWeights.directionReadAccuracy(0.0))
        assertEquals(0.7, DecisionWeights.directionReadAccuracy(0.7))
        assertEquals(1.0, DecisionWeights.directionReadAccuracy(1.0))
    }

    @Test
    fun `clamping protects out-of-range inputs`() {
        assertTrue(DecisionWeights.pAttackUnguarded(2.0) <= 1.0)
        assertTrue(DecisionWeights.pParry(-1.0) >= 0.0)
    }
}
