package com.canefe.story.bridge

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimationDiffTest {
    @BeforeTest
    fun reset() = IntentExecutor.resetAnimationCacheForTest()

    @Test
    fun firstMappedBehaviorFires() {
        assertEquals("eat", IntentExecutor.animationToPlay("npc1", "eat_bread"))
        assertEquals("drink", IntentExecutor.animationToPlay("npc2", "seek_water"))
    }

    @Test
    fun unchangedBehaviorIsNotReplayed() {
        IntentExecutor.animationToPlay("npc1", "eat_bread")
        assertNull(IntentExecutor.animationToPlay("npc1", "eat_bread"))
    }

    @Test
    fun changedBehaviorFires() {
        IntentExecutor.animationToPlay("npc1", "eat_bread")
        assertEquals("drink", IntentExecutor.animationToPlay("npc1", "seek_water"))
    }

    @Test
    fun unmappedBehaviorIsIgnored() {
        // head_to_known_location has no body animation (pathing shows it).
        assertNull(IntentExecutor.animationToPlay("npc1", "head_to_known_location"))
        assertNull(IntentExecutor.animationToPlay("npc1", "idle_look_around"))
    }

    @Test
    fun emptyBehaviorIsIgnored() {
        IntentExecutor.animationToPlay("npc1", "eat_bread")
        assertNull(IntentExecutor.animationToPlay("npc1", ""))
        assertNull(IntentExecutor.animationToPlay("npc1", "   "))
    }

    @Test
    fun emptyGapDoesNotReplaySameBehavior() {
        // An ignored empty/unmapped tick between two runs of the same behavior
        // must not cause the animation to fire again.
        assertEquals("eat", IntentExecutor.animationToPlay("npc1", "eat_bread"))
        assertNull(IntentExecutor.animationToPlay("npc1", ""))
        assertNull(IntentExecutor.animationToPlay("npc1", "eat_bread"))
    }

    @Test
    fun mappedBehaviorAfterGapStillFiresOnChange() {
        IntentExecutor.animationToPlay("npc1", "eat_bread")
        IntentExecutor.animationToPlay("npc1", "")
        assertEquals("socialize", IntentExecutor.animationToPlay("npc1", "socialize"))
    }

    @Test
    fun perCharacterCachesAreIndependent() {
        assertEquals("eat", IntentExecutor.animationToPlay("npc1", "eat_bread"))
        // Same behavior on a different NPC still fires (separate cache key).
        assertEquals("eat", IntentExecutor.animationToPlay("npc2", "eat_bread"))
    }
}
