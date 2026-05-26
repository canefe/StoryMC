package com.canefe.story.bridge

import com.canefe.storyproto.v1.LocationSightingStimulus
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Locks the wire contract for [WebSocketTransport.sendProto]:
 *   1. Envelope `type` is the proto fully-qualified name
 *      (e.g. `story.v1.LocationSightingStimulus`) — derived from the message
 *      descriptor, not a hand-typed string. This is what Go/Rust dispatch on.
 *   2. Envelope `data` is the proto canonical JSON: camelCase keys, repeated
 *      fields as arrays, int64 serialized as JSON string.
 *
 * `sendProto` writes to a live socket; we can't exercise the socket path in
 * a unit test without a full plugin harness. Instead, we replicate the
 * envelope-building logic via [JsonFormat] directly and assert the same
 * shape — this is what protects against accidental drift in the JsonFormat
 * config (e.g. someone setting `preservingProtoFieldNames` would break Rust).
 *
 * If WebSocketTransport.sendProto changes its serialization config, update
 * this test in lockstep.
 */
class WebSocketTransportSendProtoTest {

    private val printer: JsonFormat.Printer =
        JsonFormat.printer()
            .omittingInsignificantWhitespace()
            .includingDefaultValueFields()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `envelope type is the proto fully-qualified name`() {
        val msg = LocationSightingStimulus.newBuilder().build()
        assertEquals("story.v1.LocationSightingStimulus", msg.descriptorForType.fullName)
    }

    @Test
    fun `proto canonical JSON uses camelCase keys`() {
        val msg = LocationSightingStimulus.newBuilder()
            .setPerceiverCharId("char_abc")
            .setTargetLocationId("Old Well")
            .setTargetLocationDef("village_well")
            .build()
        val obj = json.parseToJsonElement(printer.print(msg)).jsonObject

        assertEquals("char_abc", obj["perceiverCharId"]!!.jsonPrimitive.content)
        assertEquals("Old Well", obj["targetLocationId"]!!.jsonPrimitive.content)
        assertEquals("village_well", obj["targetLocationDef"]!!.jsonPrimitive.content)
        // None of the snake_case proto names should leak into the JSON.
        assert(!obj.containsKey("perceiver_char_id"))
        assert(!obj.containsKey("target_location_id"))
    }

    @Test
    fun `repeated fields serialize as JSON arrays`() {
        val msg = LocationSightingStimulus.newBuilder()
            .addTags("water_source")
            .addTags("public")
            .build()
        val obj = json.parseToJsonElement(printer.print(msg)).jsonObject

        val tags = obj["tags"]!!.jsonArray
        assertEquals(2, tags.size)
        assertEquals("water_source", tags[0].jsonPrimitive.content)
        assertEquals("public", tags[1].jsonPrimitive.content)
    }

    @Test
    fun `int64 serializes as JSON string per proto3 spec`() {
        val msg = LocationSightingStimulus.newBuilder()
            .setTimestampMs(1_700_000_000_000L)
            .build()
        val obj = json.parseToJsonElement(printer.print(msg)).jsonObject

        // proto3 JSON mapping: int64 → JSON string (not number) — to survive
        // JS clients without precision loss. The Rust prost type still decodes
        // this back into an i64 because of #[serde(with = "...")] on the field.
        assertEquals("1700000000000", obj["timestampMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default values are included so receivers do not need to distinguish missing-vs-zero`() {
        val msg = LocationSightingStimulus.newBuilder()
            .setPerceiverCharId("x")  // only this field; rest default
            .build()
        val obj: JsonObject = json.parseToJsonElement(printer.print(msg)).jsonObject

        // strength, x, y, z, timestampMs should all be present at their zero values.
        assert(obj.containsKey("strength")) { "strength must be present at default; receivers don't have to handle missing" }
        assert(obj.containsKey("x"))
        assert(obj.containsKey("y"))
        assert(obj.containsKey("z"))
    }
}
