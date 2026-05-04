package com.canefe.story.bridge

import com.canefe.story.intelligence.DecisionNpcVoiceDTO
import com.canefe.story.intelligence.DecisionObserveDTO
import com.canefe.story.intelligence.DecisionOptionDTO
import com.canefe.story.intelligence.DecisionPromptDTO
import com.canefe.story.intelligence.DecisionResponseDTO
import com.canefe.story.intelligence.EventType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionRelayTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `DecisionPromptDTO round-trips through JSON`() {
        val dto = DecisionPromptDTO(
            decisionId = "test-uuid",
            mode = "leader",
            leaderId = "char-1",
            playerTargets = listOf("char-1", "char-2"),
            title = "The battle line is breaking",
            context = "Left flank overwhelmed.",
            urgency = "critical",
            npcVoices = listOf(
                DecisionNpcVoiceDTO("npc-1", "Valen", "Fall back.", "cautious")
            ),
            options = listOf(
                DecisionOptionDTO("a", "Retreat", "Safer"),
                DecisionOptionDTO("b", "Attack", "Risky"),
            ),
            allowFreeform = true,
            timeoutSeconds = 60,
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionPromptDTO>(encoded)

        assertEquals(dto.decisionId, decoded.decisionId)
        assertEquals(dto.mode, decoded.mode)
        assertEquals(dto.urgency, decoded.urgency)
        assertEquals(1, decoded.npcVoices.size)
        assertEquals(2, decoded.options.size)
        assertEquals("cautious", decoded.npcVoices[0].stance)
    }

    @Test
    fun `DecisionObserveDTO round-trips through JSON`() {
        val dto = DecisionObserveDTO(
            decisionId = "test-uuid",
            leaderName = "Valen",
            options = listOf(DecisionOptionDTO("a", "Retreat", "Safer")),
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionObserveDTO>(encoded)

        assertEquals("test-uuid", decoded.decisionId)
        assertEquals("Valen", decoded.leaderName)
        assertEquals(1, decoded.options.size)
    }

    @Test
    fun `DecisionResponseDTO with choiceId serializes correctly`() {
        val dto = DecisionResponseDTO(
            decisionId = "test-uuid",
            characterId = "char-1",
            choiceId = "a",
            freeformText = null,
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionResponseDTO>(encoded)

        assertEquals("a", decoded.choiceId)
        assertNull(decoded.freeformText)
    }

    @Test
    fun `DecisionResponseDTO with freeformText serializes correctly`() {
        val dto = DecisionResponseDTO(
            decisionId = "test-uuid",
            characterId = "char-1",
            choiceId = null,
            freeformText = "We should flank from the east.",
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<DecisionResponseDTO>(encoded)

        assertNull(decoded.choiceId)
        assertEquals("We should flank from the east.", decoded.freeformText)
    }

    @Test
    fun `EventType constants are correct`() {
        assertEquals("decision.prompt", EventType.DECISION_PROMPT)
        assertEquals("decision.observe", EventType.DECISION_OBSERVE)
        assertEquals("decision.response", EventType.DECISION_RESPONSE)
    }
}
