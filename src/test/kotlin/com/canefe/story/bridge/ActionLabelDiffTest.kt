package com.canefe.story.bridge

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionLabelDiffTest {
    @BeforeTest
    fun reset() = IntentExecutor.resetActionLabelCacheForTest()

    @Test
    fun firstLabelSends() {
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
    }

    @Test
    fun sameLabelTwiceSendsOnce() {
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
        assertFalse(IntentExecutor.shouldSendActionLabel("npc1", "Buying"))
    }

    @Test
    fun changedLabelSendsAgain() {
        IntentExecutor.shouldSendActionLabel("npc1", "Buying")
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", "Eating"))
    }

    @Test
    fun blankClearSendsOnceAfterALabel() {
        IntentExecutor.shouldSendActionLabel("npc1", "Buying")
        assertTrue(IntentExecutor.shouldSendActionLabel("npc1", ""))
        assertFalse(IntentExecutor.shouldSendActionLabel("npc1", ""))
    }
}
