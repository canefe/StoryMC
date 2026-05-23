package com.canefe.story.bridge

import com.canefe.storyproto.v1.GoToIntent
import com.google.protobuf.util.JsonFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoToWireTest {
    @Test fun goToRoundTripsCanonicalJson() {
        val g = GoToIntent.newBuilder()
            .setCharacterId("npc_1")
            .setIntentId("abc")
            .setX(1.0).setY(64.0).setZ(2.0)
            .setWorld("world")
            .setArrivalRange(3.5)
            .build()
        val json = JsonFormat.printer().omittingInsignificantWhitespace().print(g)
        assertTrue(json.contains("\"characterId\""), "expected camelCase characterId, got: $json")

        val parsed = GoToIntent.newBuilder()
        JsonFormat.parser().merge(json, parsed)
        val back = parsed.build()
        assertEquals(g.characterId, back.characterId)
        assertEquals(g.x, back.x)
    }

    @Test fun goToGoldenDecodes() {
        val json = this::class.java.classLoader
            .getResourceAsStream("go_to.golden.json")!!
            .bufferedReader().readText()
        val b = GoToIntent.newBuilder()
        JsonFormat.parser().merge(json, b)
        val g = b.build()
        assertEquals("npc_1", g.characterId)
        assertEquals("abc", g.intentId)
        assertEquals(1.0, g.x)
        assertEquals(64.0, g.y)
        assertEquals(2.0, g.z)
        assertEquals("world", g.world)
        assertEquals(3.5, g.arrivalRange)
    }
}
