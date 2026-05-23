package com.canefe.story.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-decision tests for go_to supersede handling.
 *
 * When a second go_to arrives for the same NPC while the first is still
 * walking, [IntentExecutor.startNavWatcher] silently cancels the first watcher,
 * so the first intent's onArrive/onFail never fires and the sim's PendingIntents
 * entry leaks. To prevent the leak, executeGoTo emits intent.rejected(SUPERSEDED)
 * for the OLD intentId before starting the new watcher.
 *
 * [IntentExecutor.supersededIntentId] is the pure decision (which old intentId,
 * if any, must be rejected as SUPERSEDED) kept testable without a live Bukkit
 * scheduler — full MockBukkit watcher simulation is unavailable on this branch.
 */
class GoToSupersedeTest {
    @Test fun newGoToSupersedesDifferentInFlightIntent() {
        assertEquals("A", IntentExecutor.supersededIntentId("A" to "go_to", "B"))
    }

    @Test fun sameIntentIdIsNotSuperseded() {
        assertNull(IntentExecutor.supersededIntentId("A" to "go_to", "A"))
    }

    @Test fun noPriorIntentNotSuperseded() {
        assertNull(IntentExecutor.supersededIntentId(null, "B"))
    }

    @Test fun supersededReasonExists() {
        assertEquals(RejectionReason.SUPERSEDED, RejectionReason.valueOf("SUPERSEDED"))
    }
}
