package com.canefe.story.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the IntentOutcomeEvent wire contract: event-bus dispatch, sealed-marker
 * subscription, and JSON round-trip.
 *
 * End-to-end coverage of "every IntentExecutor path emits an outcome" relies on a
 * MockBukkit-loaded plugin, which is currently broken on this branch because the
 * combat package pulls packetevents classes that aren't on the test classpath.
 * Those paths are covered by code audit; this test pins the contract.
 */
class IntentExecutorOutcomeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `eventBus dispatches Rejected to IntentOutcomeEvent listener`() {
        val bus = StoryEventBus()
        val captured = CopyOnWriteArrayList<IntentOutcomeEvent>()
        bus.on<IntentOutcomeEvent> { captured.add(it) }

        bus.emit(
            IntentRejectedEvent(
                intentId = "intent-1",
                characterId = "char-1",
                primitive = "navigate_to",
                reason = RejectionReason.NPC_NOT_FOUND,
            ),
        )

        val outcome = captured.singleOrNull()
        assertNotNull(outcome, "expected exactly one outcome, got $captured")
        val rejected = outcome as IntentRejectedEvent
        assertEquals("intent-1", rejected.intentId)
        assertEquals("char-1", rejected.characterId)
        assertEquals("navigate_to", rejected.primitive)
        assertEquals(RejectionReason.NPC_NOT_FOUND, rejected.reason)
    }

    @Test
    fun `eventBus dispatches Completed to IntentOutcomeEvent listener`() {
        val bus = StoryEventBus()
        val captured = CopyOnWriteArrayList<IntentOutcomeEvent>()
        bus.on<IntentOutcomeEvent> { captured.add(it) }

        bus.emit(
            IntentCompletedEvent(
                intentId = "intent-2",
                characterId = "char-2",
                primitive = "set_target",
            ),
        )

        val outcome = captured.singleOrNull()
        assertNotNull(outcome)
        assertTrue(outcome is IntentCompletedEvent)
        assertEquals("set_target", outcome.primitive)
    }

    @Test
    fun `IntentRejectedEvent round-trips through JSON with typed reason`() {
        val event = IntentRejectedEvent(
            intentId = "intent-3",
            characterId = "char-3",
            primitive = "attempt_hit",
            reason = RejectionReason.OUT_OF_RANGE,
            timestamp = 1_700_000_000_000L,
        )

        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<IntentRejectedEvent>(encoded)

        assertEquals(event, decoded)
        assertEquals(RejectionReason.OUT_OF_RANGE, decoded.reason)
    }

    @Test
    fun `IntentCompletedEvent round-trips through JSON`() {
        val event = IntentCompletedEvent(
            intentId = "intent-4",
            characterId = "char-4",
            primitive = "navigate_to",
            timestamp = 1_700_000_000_000L,
        )

        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<IntentCompletedEvent>(encoded)

        assertEquals(event, decoded)
    }

    @Test
    fun `eventType strings match the wire contract`() {
        val rejected = IntentRejectedEvent(
            intentId = "x",
            characterId = "y",
            primitive = "z",
            reason = RejectionReason.EXECUTION_ERROR,
        )
        val completed = IntentCompletedEvent(
            intentId = "x",
            characterId = "y",
            primitive = "z",
        )

        assertEquals("intent.rejected", rejected.eventType)
        assertEquals("intent.completed", completed.eventType)
    }

    @Test
    fun `FrontendIntentEvent default intentId is empty string for legacy sim builds`() {
        val intent = FrontendIntentEvent(primitive = "noop", characterId = "char-x")
        assertEquals("", intent.intentId)
    }

    @Test
    fun `FrontendIntentEvent round-trips intentId through JSON`() {
        val intent = FrontendIntentEvent(
            primitive = "navigate_to",
            characterId = "char-5",
            intentId = "uuid-abc-123",
            x = 10.0, y = 64.0, z = 10.0,
        )
        val encoded = json.encodeToString(intent)
        val decoded = json.decodeFromString<FrontendIntentEvent>(encoded)
        assertEquals("uuid-abc-123", decoded.intentId)
    }
}
