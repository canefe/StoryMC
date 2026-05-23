package com.canefe.story.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-decision tests for the DM-grab supersede path.
 *
 * When a DM grabs an NPC for live puppeteering, any in-flight go_to must be
 * cancelled cleanly: rejected as SUPERSEDED (so the sim's PendingIntents entry
 * is cleared) and its arrival watcher stopped. The grab reuses
 * [IntentExecutor.supersededIntentId] with an empty newIntentId so an in-flight
 * intent is ALWAYS selected for rejection (no go_to ever has an empty id).
 *
 * [IntentExecutor] is an `object`, so these call the function statically —
 * mirroring GoToSupersedeTest, which keeps the supersede decision testable
 * without a live Bukkit scheduler.
 */
class GrabSupersedeTest {
    @Test fun grabSupersedesInFlightIntentId() {
        assertEquals("intent-A", IntentExecutor.supersededIntentId("intent-A" to "go_to", ""))
    }

    @Test fun grabWithNoInFlightIntentRejectsNothing() {
        assertNull(IntentExecutor.supersededIntentId(null, ""))
    }
}
