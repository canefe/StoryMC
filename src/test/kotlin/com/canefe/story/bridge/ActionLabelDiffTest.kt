package com.canefe.story.bridge

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionLabelDiffTest {
    @BeforeTest
    fun reset() = IntentExecutor.resetActionLabelCacheForTest()

    @Test
    fun firstNonEmptyLabelIsSent() {
        assertEquals("Buying", IntentExecutor.actionLabelToSend("npc1", "Buying"))
    }

    @Test
    fun unchangedLabelIsNotResent() {
        IntentExecutor.actionLabelToSend("npc1", "Buying")
        assertNull(IntentExecutor.actionLabelToSend("npc1", "Buying"))
    }

    @Test
    fun changedLabelIsSent() {
        IntentExecutor.actionLabelToSend("npc1", "Buying")
        assertEquals("Eating", IntentExecutor.actionLabelToSend("npc1", "Eating"))
    }

    @Test
    fun emptyLabelIsIgnored() {
        // The client popup is sticky; empty is "nothing new", never a clear.
        IntentExecutor.actionLabelToSend("npc1", "Idling")
        assertNull(IntentExecutor.actionLabelToSend("npc1", ""))
        assertNull(IntentExecutor.actionLabelToSend("npc1", "   "))
    }

    @Test
    fun emptyDoesNotResetChangeDiff() {
        // An ignored empty between two identical labels must not cause a re-send.
        assertEquals("Idling", IntentExecutor.actionLabelToSend("npc1", "Idling"))
        assertNull(IntentExecutor.actionLabelToSend("npc1", ""))
        assertNull(IntentExecutor.actionLabelToSend("npc1", "Idling"))
    }

    @Test
    fun labelAfterEmptyGapStillSendsOnChange() {
        IntentExecutor.actionLabelToSend("npc1", "Idling")
        IntentExecutor.actionLabelToSend("npc1", "")
        assertEquals("Looking around", IntentExecutor.actionLabelToSend("npc1", "Looking around"))
    }

    @Test
    fun emptyWithNoPriorLabelSendsNothing() {
        assertNull(IntentExecutor.actionLabelToSend("npc1", ""))
    }
}
