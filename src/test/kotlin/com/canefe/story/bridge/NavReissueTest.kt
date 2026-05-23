package com.canefe.story.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-decision tests for the navigate_to keep-walking re-issue cadence.
 *
 * StoryMC owns the keep-walking loop (mirroring NPCFollowTracker): the sim
 * issues navigate_to ONCE per walk, and the arrival watcher re-issues
 * npc.navigateTo on a steady ~1s cadence so MythicMobs' GoToMechanic — which
 * cuts the path short partway and stops — keeps re-pathing toward the target.
 *
 * The watcher polls every NAV_POLL_TICKS (0.5s); re-issuing every
 * NAV_REISSUE_SAMPLES poll samples gives the ~1s cadence. shouldReissueNav is
 * the pure cadence decision, kept testable without a live Bukkit scheduler.
 */
class NavReissueTest {
    private val interval = IntentExecutor.NAV_REISSUE_SAMPLES

    @Test
    fun doesNotReissueWithinTheInterval() {
        // Re-issuing every poll restarts MythicMobs pathing before the NPC can
        // step — the original freeze. Stay quiet inside the window.
        assertFalse(IntentExecutor.shouldReissueNav(0, interval))
        assertFalse(IntentExecutor.shouldReissueNav(interval - 1, interval))
    }

    @Test
    fun reissuesAtTheInterval() {
        assertTrue(IntentExecutor.shouldReissueNav(interval, interval))
    }

    @Test
    fun reissuesPastTheInterval() {
        assertTrue(IntentExecutor.shouldReissueNav(interval + 5, interval))
    }
}
