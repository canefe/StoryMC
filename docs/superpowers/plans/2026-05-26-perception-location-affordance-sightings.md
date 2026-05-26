# Perception: Location & Affordance Sightings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend StoryMC's perception pipeline so NPCs organically learn about world locations and affordances via line-of-sight (locations) and ambient range (affordances), carried as **typed proto-JSON** stimuli writing into per-NPC long-term memory in story-sim.

**Architecture:** Two new proto messages — `LocationSightingStimulus` and `AffordanceSightingStimulus` — in `story-proto/story/v1/events.proto`. The wire encoding is **proto-JSON** (textual canonical-JSON of the proto message), the existing pattern in this stack per `project_proto_sot_pipeline.md` and confirmed by `frontend_intent.rs:18`. Schema enforcement comes from the generated types at each endpoint: prost-generated Rust structs (already configured with `#[derive(serde::Deserialize)] #[serde(rename_all="camelCase")]` in `build.rs:12-14`), proto-generated Go structs (with `protojson` round-trip on the Go side), and a hand-written Kotlin `@Serializable` mirror that matches the proto field names exactly (Kotlin has no protoc in the Gradle build today; the data class is the typed boundary). The character-sighting path is untouched in this plan — that migration is out of scope.

**Tech Stack:** Kotlin 2.x + Paper API (StoryMC, kotlinx.serialization, no protoc), Go 1.22+ with `google.golang.org/protobuf` + `protojson` (story-go), Rust + Bevy ECS with prost-build (story-sim), protobuf v3 via story-proto submodule.

---

## File Structure

**story-proto (submodule, source-of-truth):**
- Modify: `story/v1/events.proto` — add two new top-level messages.

**story-sim (Rust):**
- Auto-regenerated: `target/...` prost output (via `build.rs`); no source edits in `proto/`.
- Create: `src/plugins/stream_handlers/location_sighting.rs` — handler that deserializes `LocationSightingStimulus` from `StreamMessage.payload` via `serde_json::from_str` and writes into `LocationMemory`.
- Create: `src/plugins/stream_handlers/affordance_sighting.rs` — handler that deserializes `AffordanceSightingStimulus` and writes into `Memory.known_affordances`.
- Modify: `src/plugins/stream_handlers/mod.rs` — register both new handlers.
- Modify: `src/components/location_memory.rs` — add `record_mentioned` convenience.

**story-go (Go):**
- Auto-regenerated: `gen/story/v1/events.pb.go` (via `go generate`).
- Modify: `pkg/events/events.go` — two new event type constants.
- Modify: `internal/server/server.go` — route new event types to sim handler.
- Modify: `internal/sim/handler.go` — two new `Forward*` methods. Each method **decodes the inbound JSON map into the generated `storyv1.LocationSightingStimulus` (resp. `AffordanceSightingStimulus`) via `protojson.Unmarshal` to validate schema, then re-encodes via `protojson.Marshal` for the NATS payload**. This is the typed boundary on the Go side — invalid input gets logged and dropped instead of silently passing through.
- Test: `internal/sim/handler_test.go`.

**Story (Kotlin):**
- Modify: `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` — two new `SerializableStoryEvent` data classes whose field names match the proto canonical-JSON (`camelCase`) exactly.
- Modify: `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — two new branches in `serializeEvent()` (the closed `when` is the registration choke-point).
- Modify: `src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt` — add location loop after the character loop; reroute existing affordance loop from `PerceptionStimulusEvent` to `AffordanceSightingEvent`.
- Test: `src/test/kotlin/com/canefe/story/perception/PerceptionBroadcasterLocationTest.kt`.
- Test: `src/test/kotlin/com/canefe/story/bridge/WebSocketTransportSerializeTest.kt`.
- Test: `src/test/kotlin/com/canefe/story/bridge/SightingProtoCompatTest.kt` — golden-JSON test that proves the Kotlin DTO's `json.encodeToJsonElement(event)` produces the exact field names declared in `events.proto` (catches schema drift between the hand-written Kotlin class and the proto SoT).

---

## Wire Contracts (locked here, referenced from every task)

### `LocationSightingStimulus` (proto)

```proto
message LocationSightingStimulus {
  string perceiver_char_id   = 1;  // matches sim ExternalId
  string target_location_id  = 2;  // == StoryLocation.name == sim Location.instance_name
  string target_location_def = 3;  // template id, e.g. "village_well"; empty if no template
  float  strength            = 4;
  float  x                   = 5;
  float  y                   = 6;
  float  z                   = 7;
  repeated string tags       = 8;
  int64  timestamp_ms        = 9;
}
```

### `AffordanceSightingStimulus` (proto)

```proto
message AffordanceSightingStimulus {
  string perceiver_char_id    = 1;
  string target_affordance_id = 2;  // MongoDB instance id; matches sim AffordanceInstanceId
  float  strength             = 3;
  float  x                    = 4;
  float  y                    = 5;
  float  z                    = 6;
  repeated string tags        = 7;
  int64  timestamp_ms         = 8;
}
```

### Wire encoding — proto canonical-JSON

All hops carry the message as **proto canonical JSON** (the textual form defined by proto3 JSON mapping rules: `snake_case` proto field names become `camelCase` JSON keys; `repeated` becomes JSON array; scalars map to JSON primitives).

| Proto field | JSON key |
|---|---|
| `perceiver_char_id` | `perceiverCharId` |
| `target_location_id` | `targetLocationId` |
| `target_location_def` | `targetLocationDef` |
| `target_affordance_id` | `targetAffordanceId` |
| `strength` | `strength` |
| `x`/`y`/`z` | `x`/`y`/`z` |
| `tags` | `tags` (array) |
| `timestamp_ms` | `timestampMs` |

**Envelope wrapping**: the proto-JSON object lives in the `data` field of the existing `BridgeMessage` envelope (`{"type":"location.sighting","data":<proto-json>,...}`). The envelope itself stays JSON — that's the existing pattern, not negotiated here.

**Schema enforcement points** (this is what makes the wire "typed proto-JSON" rather than ad-hoc JSON):
- **Kotlin**: `@Serializable` data class with `@SerialName` annotations for any field whose Kotlin property name diverges from camelCase. `SightingProtoCompatTest` (Task 3) asserts the produced JSON's keys match the proto's expected camelCase field names exactly — this is the only enforcement layer on the Kotlin side, so the test is load-bearing.
- **Go**: `protojson.Unmarshal` into the generated `storyv1.LocationSightingStimulus` struct on entry to the forwarder; `protojson.Marshal` on the way out to NATS. Schema-invalid input is logged and dropped.
- **Rust**: `serde_json::from_str::<story::v1::LocationSightingStimulus>(&msg.payload)` in each handler. The prost-generated type rejects unknown fields and type mismatches, replacing the existing `StreamMessage.str_field(...)` map-poking pattern.

---

## Task 1: Add proto messages for location and affordance sightings

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-proto/story/v1/events.proto`

The submodule is shared via `story-go/proto/`, `story-sim/proto/`, and `Story/src/main/proto/` per `project_proto_sot_pipeline.md`. Editing `events.proto` propagates to all three on next codegen. Note: Kotlin has no proto codegen today (the Story plugin doesn't compile protos); the proto file is consumed by Go (`go generate` → `gen/`) and Rust (`build.rs` → prost output).

- [ ] **Step 1: Append the two new messages to events.proto**

Open `/Users/canefe/Projects/personal/story-proto/story/v1/events.proto`. After the last existing message, append:

```proto
message LocationSightingStimulus {
  string perceiver_char_id   = 1;
  string target_location_id  = 2;
  string target_location_def = 3;
  float  strength            = 4;
  float  x                   = 5;
  float  y                   = 6;
  float  z                   = 7;
  repeated string tags       = 8;
  int64  timestamp_ms        = 9;
}

message AffordanceSightingStimulus {
  string perceiver_char_id    = 1;
  string target_affordance_id = 2;
  float  strength             = 3;
  float  x                    = 4;
  float  y                    = 5;
  float  z                    = 6;
  repeated string tags        = 7;
  int64  timestamp_ms         = 8;
}
```

- [ ] **Step 2: Verify proto syntax**

Run: `cd /Users/canefe/Projects/personal/story-proto && protoc --proto_path=. --descriptor_set_out=/tmp/story-proto-check.pb story/v1/events.proto`
Expected: exits 0, produces `/tmp/story-proto-check.pb`, no warnings.

- [ ] **Step 3: Trigger codegen in Go and Rust consumers**

Run, sequentially:
- `cd /Users/canefe/Projects/personal/story-go && go generate ./... 2>&1 | tail -20` — expect new types `LocationSightingStimulus` and `AffordanceSightingStimulus` in `gen/story/v1/events.pb.go`.
- `cd /Users/canefe/Projects/personal/story-sim && cargo build --no-default-features 2>&1 | tail -20` — `build.rs` runs prost-build; expect new types at `story::v1::LocationSightingStimulus` and `story::v1::AffordanceSightingStimulus`, both deriving `serde::Serialize`, `serde::Deserialize`, and `#[serde(rename_all = "camelCase")]`.

There is no Kotlin codegen step — the Kotlin DTO in Task 2 is hand-written and validated by a compat test in Task 3.

- [ ] **Step 4: Verify the Rust types deserialize from canonical proto-JSON**

Add a one-off scratch test to confirm the camelCase rename works as expected. Open `/Users/canefe/Projects/personal/story-sim/src/wire/mod.rs` (or wherever existing serde round-trip tests live — see the Explore finding that `wire/mod.rs:27` already has one for another message). Append:

```rust
#[cfg(test)]
#[test]
fn location_sighting_proto_json_round_trip() {
    let s = r#"{
      "perceiverCharId": "char_abc",
      "targetLocationId": "Old Well",
      "targetLocationDef": "village_well",
      "strength": 72.5,
      "x": 10.0, "y": -60.0, "z": 25.0,
      "tags": ["water_source", "public"],
      "timestampMs": 1700000000000
    }"#;
    let decoded: crate::story::v1::LocationSightingStimulus =
        serde_json::from_str(s).expect("proto-JSON decodes into prost-generated type");
    assert_eq!(decoded.perceiver_char_id, "char_abc");
    assert_eq!(decoded.target_location_id, "Old Well");
    assert_eq!(decoded.target_location_def, "village_well");
    assert_eq!(decoded.tags, vec!["water_source", "public"]);
    assert_eq!(decoded.timestamp_ms, 1_700_000_000_000);
}

#[cfg(test)]
#[test]
fn affordance_sighting_proto_json_round_trip() {
    let s = r#"{
      "perceiverCharId": "char_abc",
      "targetAffordanceId": "mongo_xyz",
      "strength": 50.0,
      "x": 3.0, "y": 64.0, "z": 0.0,
      "tags": ["water_source"],
      "timestampMs": 1700000000000
    }"#;
    let decoded: crate::story::v1::AffordanceSightingStimulus =
        serde_json::from_str(s).expect("proto-JSON decodes into prost-generated type");
    assert_eq!(decoded.target_affordance_id, "mongo_xyz");
    assert_eq!(decoded.tags, vec!["water_source"]);
}
```

The exact module path `crate::story::v1::...` may differ — match the existing import style in `wire/mod.rs` for already-generated types. If the existing test imports `crate::proto::story::v1::*` or similar, use the same prefix.

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features --lib wire:: 2>&1 | tail -20`
Expected: PASS, both new tests green.

- [ ] **Step 5: Commit proto change + submodule bumps**

```bash
cd /Users/canefe/Projects/personal/story-proto
git add story/v1/events.proto
git commit -m "feat(proto): add LocationSightingStimulus and AffordanceSightingStimulus"
```

Then bump the submodule pointer in each Go/Rust consumer (per `project_proto_sot_pipeline.md`: jj doesn't auto-snapshot gitlinks):
- `cd /Users/canefe/Projects/personal/story-go && git add proto && git commit -m "chore: bump story-proto for sighting stimuli"`
- `cd /Users/canefe/Projects/personal/story-sim`: use jj. Run `jj st`; if the `proto` submodule shows as unchanged despite the pointer move, run `jj git import` then `jj describe -m "chore: bump story-proto for sighting stimuli"`.

Also commit the Rust scratch tests from Step 4:
```bash
cd /Users/canefe/Projects/personal/story-sim
jj describe -m "test(wire): proto-JSON round trip for sighting stimuli"
```

---

## Task 2: Kotlin — add SerializableStoryEvent classes that mirror the proto canonical-JSON

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt`

The Kotlin side has no protoc, so the data class IS the schema boundary; its field names must exactly mirror the proto canonical-JSON. Task 3 adds a compat test to enforce this. We use Kotlin property names that already match camelCase, so no `@SerialName` annotations are needed — but if you rename any field, you MUST add a `@SerialName("camelCaseFromProto")` annotation so the wire output stays correct.

- [ ] **Step 1: Add LocationSightingEvent below PerceptionStimulusEvent**

Open the file and locate `PerceptionStimulusEvent` (around line 293). Immediately after its closing brace, add:

```kotlin
/**
 * Per-perceiver line-of-sight sighting of a world location instance.
 *
 * Wire format: proto canonical-JSON of `story.v1.LocationSightingStimulus`.
 * The Kotlin property names below MUST match the proto's camelCase JSON keys
 * exactly (`perceiverCharId`, `targetLocationId`, `targetLocationDef`,
 * `timestampMs`). SightingProtoCompatTest in Task 3 enforces this — if you
 * rename a property, add `@SerialName("<camelCase>")` to keep the wire stable.
 *
 * Schema source of truth: story-proto/story/v1/events.proto.
 *
 * The receiver in story-sim deserializes the JSON into
 * `story::v1::LocationSightingStimulus` (prost-generated), resolves
 * `targetLocationId` against `Location.instance_name`, and writes the entry
 * into the perceiver's LocationMemory with `LocationLearnSource::Mentioned`.
 *
 * Cadence: emitted from PerceptionBroadcaster at the same 2s tick as character
 * sightings, gated by sight range + FOV + line-of-sight + light level.
 */
@Serializable
data class LocationSightingEvent(
    val perceiverCharId: String,
    val targetLocationId: String,
    val targetLocationDef: String,
    val strength: Float,
    val x: Double,
    val y: Double,
    val z: Double,
    val tags: List<String> = emptyList(),
    val timestampMs: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "location.sighting"
}
```

- [ ] **Step 2: Add AffordanceSightingEvent**

Immediately after `LocationSightingEvent`, add:

```kotlin
/**
 * Per-perceiver ambient (no FOV/LOS) sighting of a world affordance instance.
 *
 * Wire format: proto canonical-JSON of `story.v1.AffordanceSightingStimulus`.
 * Replaces the affordance branch of PerceptionStimulusEvent. Sim deserializes
 * into `story::v1::AffordanceSightingStimulus`, resolves `targetAffordanceId`
 * against `AffordanceInstanceId`, and writes into Memory.known_affordances.
 *
 * Cadence: every 2s, gated by sight range only (affordances are ambient — see obs 7113).
 */
@Serializable
data class AffordanceSightingEvent(
    val perceiverCharId: String,
    val targetAffordanceId: String,
    val strength: Float,
    val x: Double,
    val y: Double,
    val z: Double,
    val tags: List<String> = emptyList(),
    val timestampMs: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "affordance.sighting"
}
```

- [ ] **Step 3: Compile to verify**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
git add src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt
git commit -m "feat(perception): add LocationSightingEvent and AffordanceSightingEvent DTOs mirroring proto canonical-JSON"
```

---

## Task 3: Kotlin — register events in WebSocketTransport + golden-JSON proto-compat test

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt`
- Create: `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/bridge/WebSocketTransportSerializeTest.kt`
- Create: `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/bridge/SightingProtoCompatTest.kt`

Per `project_websocket_serialize_registration.md`: unregistered `SerializableStoryEvent` subclasses silently fall through to the `{raw:...}` branch and payload effectively drops. The serialize test catches that. The compat test catches the OTHER drift risk: Kotlin DTO field names diverging from the proto's canonical-JSON keys, which would silently produce JSON that the Go/Rust typed parsers reject.

- [ ] **Step 1: Write the failing serialize test**

Create `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/bridge/WebSocketTransportSerializeTest.kt`:

```kotlin
package com.canefe.story.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSocketTransportSerializeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `LocationSightingEvent serializes through transport with all fields intact`() {
        val event = LocationSightingEvent(
            perceiverCharId = "char_abc",
            targetLocationId = "Old Well",
            targetLocationDef = "village_well",
            strength = 72.5f,
            x = 10.0, y = -60.0, z = 25.0,
            tags = listOf("water_source", "public"),
            timestampMs = 1_700_000_000_000L,
        )
        val encoded = WebSocketTransport.serializeEventForTest(event)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("location.sighting", obj["eventType"]!!.jsonPrimitive.content)
        assertEquals("char_abc", obj["perceiverCharId"]!!.jsonPrimitive.content)
        assertEquals("Old Well", obj["targetLocationId"]!!.jsonPrimitive.content)
        assertEquals("village_well", obj["targetLocationDef"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("tags"))
        assertTrue(obj.containsKey("timestampMs"))
    }

    @Test
    fun `AffordanceSightingEvent serializes through transport with all fields intact`() {
        val event = AffordanceSightingEvent(
            perceiverCharId = "char_abc",
            targetAffordanceId = "mongo_obj_id_xyz",
            strength = 50.0f,
            x = 0.0, y = -60.0, z = 0.0,
            tags = listOf("water_source"),
            timestampMs = 1_700_000_000_000L,
        )
        val encoded = WebSocketTransport.serializeEventForTest(event)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("affordance.sighting", obj["eventType"]!!.jsonPrimitive.content)
        assertEquals("mongo_obj_id_xyz", obj["targetAffordanceId"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Write the failing proto-compat test**

Create `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/bridge/SightingProtoCompatTest.kt`:

```kotlin
package com.canefe.story.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces that the hand-written Kotlin DTOs produce JSON whose top-level keys
 * exactly match the proto canonical-JSON field names of
 * `story.v1.LocationSightingStimulus` and `story.v1.AffordanceSightingStimulus`.
 *
 * Kotlin has no protoc step; this test is the only guard against drift between
 * the Kotlin class and the proto SoT. If you add or rename a proto field,
 * update both the Kotlin class AND this test's expected-key set.
 */
class SightingProtoCompatTest {

    private val json = Json { encodeDefaults = true }

    // Field name lists derive directly from events.proto (snake_case → camelCase per
    // proto3 JSON mapping). Update when the proto changes.
    private val locationSightingKeys = setOf(
        "perceiverCharId", "targetLocationId", "targetLocationDef",
        "strength", "x", "y", "z", "tags", "timestampMs",
    )
    private val affordanceSightingKeys = setOf(
        "perceiverCharId", "targetAffordanceId",
        "strength", "x", "y", "z", "tags", "timestampMs",
    )

    @Test
    fun `LocationSightingEvent JSON keys match proto canonical-JSON exactly`() {
        val event = LocationSightingEvent(
            perceiverCharId = "x", targetLocationId = "y", targetLocationDef = "z",
            strength = 0f, x = 0.0, y = 0.0, z = 0.0,
            tags = emptyList(), timestampMs = 0L,
        )
        val keys = json.encodeToJsonElement(LocationSightingEvent.serializer(), event)
            .jsonObject.keys
        assertEquals(locationSightingKeys, keys,
            "Kotlin DTO keys drifted from proto. Update DomainEvents.kt or this test.")
    }

    @Test
    fun `AffordanceSightingEvent JSON keys match proto canonical-JSON exactly`() {
        val event = AffordanceSightingEvent(
            perceiverCharId = "x", targetAffordanceId = "y",
            strength = 0f, x = 0.0, y = 0.0, z = 0.0,
            tags = emptyList(), timestampMs = 0L,
        )
        val keys = json.encodeToJsonElement(AffordanceSightingEvent.serializer(), event)
            .jsonObject.keys
        assertEquals(affordanceSightingKeys, keys,
            "Kotlin DTO keys drifted from proto. Update DomainEvents.kt or this test.")
    }

    @Test
    fun `eventType discriminators match proto-derived NATS subjects`() {
        assertTrue(LocationSightingEvent(
            perceiverCharId = "x", targetLocationId = "y", targetLocationDef = "z",
            strength = 0f, x = 0.0, y = 0.0, z = 0.0, tags = emptyList(), timestampMs = 0L,
        ).eventType == "location.sighting")
        assertTrue(AffordanceSightingEvent(
            perceiverCharId = "x", targetAffordanceId = "y",
            strength = 0f, x = 0.0, y = 0.0, z = 0.0, tags = emptyList(), timestampMs = 0L,
        ).eventType == "affordance.sighting")
    }
}
```

- [ ] **Step 3: Run both tests to confirm failure**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.WebSocketTransportSerializeTest" --tests "com.canefe.story.bridge.SightingProtoCompatTest" 2>&1 | tail -40`
Expected: `WebSocketTransportSerializeTest` FAILS (`serializeEventForTest` missing OR raw fallback used). `SightingProtoCompatTest` likely PASSES (because the Kotlin properties happen to be camelCase already) — but if it ever fails after a refactor, that's the signal.

- [ ] **Step 4: Add the two cases to serializeEvent and expose a test hook**

Open `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt`. In the `serializeEvent` `when` block (around lines 165–203), locate the existing `is PerceptionStimulusEvent ->` branch (line 179) and add immediately after it:

```kotlin
            is LocationSightingEvent -> json.encodeToJsonElement(event)
            is AffordanceSightingEvent -> json.encodeToJsonElement(event)
```

Then expose the test hook. If `WebSocketTransport` already has a companion object, add to it; otherwise create one:

```kotlin
companion object {
    /** Test-only re-entry to the private serializer. */
    @JvmStatic
    internal fun serializeEventForTest(event: SerializableStoryEvent): String {
        val transport = WebSocketTransport(/* match the file's existing constructor */)
        return transport.serializeEvent(event).toString()
    }
}
```

If the constructor takes irreducible dependencies (plugin reference, scheduler), extract the `when` body into a companion-object function `private fun serializeEventBody(event: SerializableStoryEvent, json: Json): JsonElement` and call that from both the instance method and the test hook.

- [ ] **Step 5: Run both tests to confirm pass**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.bridge.WebSocketTransportSerializeTest" --tests "com.canefe.story.bridge.SightingProtoCompatTest" 2>&1 | tail -20`
Expected: all cases green.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
git add src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt src/test/kotlin/com/canefe/story/bridge/
git commit -m "feat(perception): register sighting events in WebSocket serializer; lock keys to proto canonical-JSON"
```

---

## Task 4: Kotlin — reroute PerceptionBroadcaster's affordance loop and add the location loop

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt`
- Create: `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/perception/PerceptionBroadcasterLocationTest.kt`
- Create: `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/testsupport/PerceptionTestHarness.kt`

Two behavior changes: (1) swap the affordance branch from `PerceptionStimulusEvent` to `AffordanceSightingEvent` (no behavior change, just typed), (2) add a parallel location loop after the character loop with LOS gating.

- [ ] **Step 1: Write the failing test**

Create `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/perception/PerceptionBroadcasterLocationTest.kt`:

```kotlin
package com.canefe.story.perception

import com.canefe.story.bridge.AffordanceSightingEvent
import com.canefe.story.bridge.LocationSightingEvent
import com.canefe.story.bridge.PerceptionStimulusEvent
import com.canefe.story.bridge.StoryEvent
import com.canefe.story.testsupport.PerceptionTestHarness
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerceptionBroadcasterLocationTest {

    @Test
    fun `broadcaster emits LocationSightingEvent when NPC has LOS to a location center`() {
        val harness = PerceptionTestHarness()
            .withNpcAt("char_perceiver", x = 0.0, y = 64.0, z = 0.0, facing = 0.0)
            .withLocation(
                name = "Old Well",
                template = "village_well",
                x = 5.0, y = 64.0, z = 0.0,
                radius = 4.0,
                tags = listOf("water_source", "public"),
            )
            .withLineOfSight("char_perceiver", "Old Well", clear = true)

        val emitted = mutableListOf<StoryEvent>()
        harness.captureEvents(emitted)
        harness.tickPerception()

        val locSightings = emitted.filterIsInstance<LocationSightingEvent>()
        assertEquals(1, locSightings.size, "expected exactly one LocationSightingEvent")
        val e = locSightings.first()
        assertEquals("char_perceiver", e.perceiverCharId)
        assertEquals("Old Well", e.targetLocationId)
        assertEquals("village_well", e.targetLocationDef)
        assertTrue(e.tags.contains("water_source"))
        assertTrue(e.strength > 0f)
        assertTrue(e.timestampMs > 0L)
    }

    @Test
    fun `broadcaster does NOT emit LocationSightingEvent when LOS is blocked`() {
        val harness = PerceptionTestHarness()
            .withNpcAt("char_perceiver", x = 0.0, y = 64.0, z = 0.0, facing = 0.0)
            .withLocation(
                name = "Hidden Shrine",
                template = "temple_grounds",
                x = 5.0, y = 64.0, z = 0.0,
                radius = 4.0,
                tags = listOf("public"),
            )
            .withLineOfSight("char_perceiver", "Hidden Shrine", clear = false)

        val emitted = mutableListOf<StoryEvent>()
        harness.captureEvents(emitted)
        harness.tickPerception()

        assertTrue(emitted.filterIsInstance<LocationSightingEvent>().isEmpty())
    }

    @Test
    fun `broadcaster emits AffordanceSightingEvent (not PerceptionStimulusEvent) for affordances`() {
        val harness = PerceptionTestHarness()
            .withNpcAt("char_perceiver", x = 0.0, y = 64.0, z = 0.0, facing = 0.0)
            .withAffordance(
                id = "mongo_xyz",
                x = 3.0, y = 64.0, z = 0.0,
                tags = listOf("water_source"),
            )

        val emitted = mutableListOf<StoryEvent>()
        harness.captureEvents(emitted)
        harness.tickPerception()

        val affSightings = emitted.filterIsInstance<AffordanceSightingEvent>()
        val legacyStim = emitted
            .filterIsInstance<PerceptionStimulusEvent>()
            .filter { it.targetAffordanceId != null }
        assertEquals(1, affSightings.size, "expected one AffordanceSightingEvent")
        assertEquals(0, legacyStim.size, "affordance path must no longer use PerceptionStimulusEvent")
    }
}
```

- [ ] **Step 2: Add the test harness skeleton**

Create `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/testsupport/PerceptionTestHarness.kt`:

```kotlin
package com.canefe.story.testsupport

import com.canefe.story.bridge.StoryEvent
import com.canefe.story.perception.PerceptionBroadcaster
import com.canefe.story.perception.PerceptionContext

/**
 * Test harness that backs PerceptionContext with in-memory stubs so
 * PerceptionBroadcaster can be exercised without a live Bukkit world.
 *
 * Pattern: builder API populates stub data, captureEvents() routes
 * broadcaster output into a caller-provided sink, tickPerception() runs one
 * cycle (PerceptionBroadcaster.runOnce()).
 *
 * Production wires PerceptionBroadcaster against PluginPerceptionContext
 * (see Task 4 Step 4); tests wire it against this stub via the same interface.
 */
class PerceptionTestHarness {
    private val npcs = mutableListOf<PerceptionContext.NpcSnapshot>()
    private val charTargets = mutableListOf<PerceptionContext.CharacterTargetSnapshot>()
    private val locations = mutableListOf<PerceptionContext.LocationSnapshot>()
    private val affordances = mutableListOf<PerceptionContext.AffordanceSnapshot>()
    private val losMap = mutableMapOf<Pair<String, String>, Boolean>()
    private var capturedSink: MutableList<StoryEvent>? = null

    fun withNpcAt(charId: String, x: Double, y: Double, z: Double, facing: Double): PerceptionTestHarness {
        npcs += PerceptionContext.NpcSnapshot(
            charId = charId, name = charId, world = "world",
            x = x, y = y, z = z, eyeX = x, eyeY = y + 1.6, eyeZ = z,
            yawDeg = facing, pitchDeg = 0.0,
            consciousness = 1.0f, sightRangeBlocks = 32.0, fovHalfDeg = 70.0,
        )
        return this
    }

    fun withLocation(name: String, template: String, x: Double, y: Double, z: Double, radius: Double, tags: List<String>): PerceptionTestHarness {
        locations += PerceptionContext.LocationSnapshot(
            instanceName = name, templateId = template, world = "world",
            x = x, y = y, z = z, radius = radius, tags = tags,
        )
        return this
    }

    fun withAffordance(id: String, x: Double, y: Double, z: Double, tags: List<String>): PerceptionTestHarness {
        affordances += PerceptionContext.AffordanceSnapshot(
            id = id, world = "world", x = x, y = y, z = z, tags = tags,
        )
        return this
    }

    fun withLineOfSight(perceiverCharId: String, targetName: String, clear: Boolean): PerceptionTestHarness {
        losMap[perceiverCharId to targetName] = clear
        return this
    }

    fun captureEvents(sink: MutableList<StoryEvent>): PerceptionTestHarness {
        this.capturedSink = sink
        return this
    }

    fun tickPerception() {
        val ctx = object : PerceptionContext {
            override fun npcsForPerception(): List<PerceptionContext.NpcSnapshot> = npcs
            override fun characterTargets(): List<PerceptionContext.CharacterTargetSnapshot> = charTargets
            override fun locations(): List<PerceptionContext.LocationSnapshot> = locations
            override fun affordances(): List<PerceptionContext.AffordanceSnapshot> = affordances
            override fun lightLevelAt(world: String, x: Double, y: Double, z: Double): Int = 15
            override fun hasLineOfSight(fromWorld: String, fromX: Double, fromY: Double, fromZ: Double, toWorld: String, toX: Double, toY: Double, toZ: Double): Boolean {
                // Resolve LOS by finding the location/affordance at the target coords; default to true.
                val target = locations.firstOrNull { it.x == toX && it.y == toY && it.z == toZ }?.instanceName
                    ?: affordances.firstOrNull { it.x == toX && it.y == toY && it.z == toZ }?.id
                val perceiver = npcs.firstOrNull { it.eyeX == fromX && it.eyeY == fromY && it.eyeZ == fromZ }?.charId
                if (target != null && perceiver != null) {
                    return losMap[perceiver to target] ?: true
                }
                return true
            }
            override fun gameTimeMs(): Long = 1_700_000_000_000L
            override fun emit(event: StoryEvent) { capturedSink?.add(event) }
            override val simPaused: Boolean = false
        }
        PerceptionBroadcaster.runOnce(ctx)
    }
}
```

- [ ] **Step 3: Refactor PerceptionBroadcaster to take a PerceptionContext, and add the location loop**

Open `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt`.

3a. **Extract a `PerceptionContext` interface** at the top of the file (above the broadcaster class):

```kotlin
/**
 * Injectable world-snapshot surface so the broadcaster is testable without a
 * live Bukkit world. Production wires this to the real plugin
 * (PluginPerceptionContext); tests wire it to PerceptionTestHarness's stub.
 */
interface PerceptionContext {
    data class NpcSnapshot(
        val charId: String, val name: String, val world: String,
        val x: Double, val y: Double, val z: Double,
        val eyeX: Double, val eyeY: Double, val eyeZ: Double,
        val yawDeg: Double, val pitchDeg: Double,
        val consciousness: Float, val sightRangeBlocks: Double, val fovHalfDeg: Double,
    )
    data class CharacterTargetSnapshot(
        val charId: String, val isPlayer: Boolean, val world: String,
        val x: Double, val y: Double, val z: Double,
        val eyeX: Double, val eyeY: Double, val eyeZ: Double,
    )
    data class LocationSnapshot(
        val instanceName: String, val templateId: String, val world: String,
        val x: Double, val y: Double, val z: Double,
        val radius: Double, val tags: List<String>,
    )
    data class AffordanceSnapshot(
        val id: String, val world: String,
        val x: Double, val y: Double, val z: Double,
        val tags: List<String>,
    )

    fun npcsForPerception(): List<NpcSnapshot>
    fun characterTargets(): List<CharacterTargetSnapshot>
    fun locations(): List<LocationSnapshot>
    fun affordances(): List<AffordanceSnapshot>
    fun lightLevelAt(world: String, x: Double, y: Double, z: Double): Int
    fun hasLineOfSight(
        fromWorld: String, fromX: Double, fromY: Double, fromZ: Double,
        toWorld: String, toX: Double, toY: Double, toZ: Double,
    ): Boolean
    fun gameTimeMs(): Long
    fun emit(event: StoryEvent)
    val simPaused: Boolean
}
```

3b. **Change `PerceptionBroadcaster`'s primary constructor** to take a `PerceptionContext`. Replace direct uses of `plugin.npcRegistry`, `plugin.characterRegistry`, `plugin.eventBus.emit`, `perceiverEntity.hasLineOfSight`, `block.lightLevel`, and `plugin.simPaused` with calls to the context. Move the per-tick body into:

```kotlin
companion object {
    /** Exposed for tests; production runs this on the 2s scheduler. */
    @JvmStatic
    fun runOnce(ctx: PerceptionContext) {
        // Body lifted from the existing scheduler callback; references to
        // plugin.* swapped for ctx.* per 3a.
    }
}
```

The existing affordance-loading helper (`loadAffordances()` around line 238) is removed; pull from `ctx.affordances()` instead.

3c. **Reroute the affordance branch (lines 214–224) to emit `AffordanceSightingEvent`**:

```kotlin
ctx.emit(AffordanceSightingEvent(
    perceiverCharId = perceiverCharId,
    targetAffordanceId = aff.id,
    strength = strength,
    x = aff.x,
    y = aff.y,
    z = aff.z,
    tags = aff.tags,
    timestampMs = ctx.gameTimeMs(),
))
```

3d. **Add a new location loop** after the affordance loop, mirroring the character loop's LOS pattern:

```kotlin
// --- Location targets (LOS-gated like characters; landmarks are direct sightings) ---
for (loc in ctx.locations()) {
    if (loc.world != perceiverWorld) continue
    val dx = loc.x - perceiverLoc.x
    val dy = loc.y - perceiverLoc.y
    val dz = loc.z - perceiverLoc.z
    val dist = sqrt(dx * dx + dy * dy + dz * dz)
    if (dist > sightRange) continue

    // FOV check against the location *center* (landmarks are big — center is fine)
    if (!inFov(perceiverEyeLoc.direction, perceiverEyeLoc.toVector(),
               org.bukkit.util.Vector(loc.x, loc.y, loc.z), fovHalfDeg)) continue

    // Line-of-sight to the location center
    if (!ctx.hasLineOfSight(
            perceiverWorld, perceiverEyeLoc.x, perceiverEyeLoc.y, perceiverEyeLoc.z,
            loc.world, loc.x, loc.y, loc.z,
        )) continue

    // Light gate (same model as characters)
    val lightLevel = ctx.lightLevelAt(loc.world, loc.x, loc.y, loc.z)
    val lightFactor = if (lightLevel < MIN_LIGHT_LEVEL) {
        (lightLevel.toFloat() / MIN_LIGHT_LEVEL).coerceAtLeast(0.1f)
    } else 1.0f
    val strength = (BASE_STRENGTH * (1.0 - dist / sightRange) * lightFactor * stats.consciousness).toFloat()

    ctx.emit(LocationSightingEvent(
        perceiverCharId = perceiverCharId,
        targetLocationId = loc.instanceName,
        targetLocationDef = loc.templateId,
        strength = strength,
        x = loc.x,
        y = loc.y,
        z = loc.z,
        tags = loc.tags,
        timestampMs = ctx.gameTimeMs(),
    ))
}
```

- [ ] **Step 4: Wire production code to construct the context from the plugin**

Find the call site that instantiates `PerceptionBroadcaster` (likely in `Story.kt` or a service-init class). Replace it with construction-via-context:

```kotlin
class PluginPerceptionContext(private val plugin: Story) : PerceptionContext {
    override fun npcsForPerception(): List<PerceptionContext.NpcSnapshot> { /* read plugin.npcRegistry — lift from old PerceptionBroadcaster body */ }
    override fun characterTargets(): List<PerceptionContext.CharacterTargetSnapshot> { /* npcs + players — lift from old body */ }
    override fun locations(): List<PerceptionContext.LocationSnapshot> =
        plugin.locationManager.getAllLocations().mapNotNull { loc ->
            val bl = loc.bukkitLocation ?: return@mapNotNull null
            PerceptionContext.LocationSnapshot(
                instanceName = loc.name,
                templateId = loc.template,
                world = bl.world.name,
                x = bl.x, y = bl.y, z = bl.z,
                radius = loc.radius,
                tags = loc.tags.toList(),
            )
        }
    override fun affordances(): List<PerceptionContext.AffordanceSnapshot> {
        /* reuse the old loadAffordances() body, returning AffordanceSnapshot instead of AffordanceTarget */
    }
    override fun lightLevelAt(world: String, x: Double, y: Double, z: Double): Int =
        plugin.server.getWorld(world)?.getBlockAt(x.toInt(), y.toInt(), z.toInt())?.lightLevel?.toInt() ?: 0
    override fun hasLineOfSight(fromWorld: String, fromX: Double, fromY: Double, fromZ: Double,
                                 toWorld: String, toX: Double, toY: Double, toZ: Double): Boolean {
        if (fromWorld != toWorld) return false
        val w = plugin.server.getWorld(fromWorld) ?: return false
        val from = org.bukkit.Location(w, fromX, fromY, fromZ)
        val to = org.bukkit.Location(w, toX, toY, toZ)
        val direction = to.toVector().subtract(from.toVector())
        val distance = direction.length()
        if (distance < 1e-6) return true
        val rayResult = w.rayTraceBlocks(from, direction.normalize(), distance,
            org.bukkit.FluidCollisionMode.NEVER, true) ?: return true
        return rayResult.hitBlock == null
    }
    override fun gameTimeMs(): Long =
        plugin.server.worlds.firstOrNull()?.fullTime ?: System.currentTimeMillis()
    override fun emit(event: StoryEvent) = plugin.eventBus.emit(event)
    override val simPaused: Boolean get() = plugin.simPaused
}
```

The Bukkit scheduler that drives the 2s tick now calls `PerceptionBroadcaster.runOnce(ctx)` instead of an instance method.

- [ ] **Step 5: Run the perception tests to confirm pass**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.perception.PerceptionBroadcasterLocationTest" 2>&1 | tail -30`
Expected: PASS, all three cases green.

- [ ] **Step 6: Run the full perception + bridge test suites for regressions**

Run: `cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "com.canefe.story.perception.*" --tests "com.canefe.story.bridge.*" 2>&1 | tail -30`
Expected: all green. Fix any character-sighting tests that broke from the context refactor.

- [ ] **Step 7: Commit**

```bash
cd /Users/canefe/Projects/personal/Story
git add src/main/kotlin/com/canefe/story/perception/ src/test/kotlin/com/canefe/story/perception/ src/test/kotlin/com/canefe/story/testsupport/
git commit -m "feat(perception): emit LocationSightingEvent (LOS-gated) and reroute affordance branch to typed AffordanceSightingEvent"
```

---

## Task 5: Go — typed forwarders that validate via protojson on entry, re-emit on NATS

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/pkg/events/events.go`
- Modify: `/Users/canefe/Projects/personal/story-go/internal/server/server.go`
- Modify: `/Users/canefe/Projects/personal/story-go/internal/sim/handler.go`
- Modify: `/Users/canefe/Projects/personal/story-go/internal/sim/handler_test.go` (create if missing)

The forwarders do NOT pass through `msg.Data` blindly (that would defeat the typing). They reserialize the inbound map to JSON, decode via `protojson.Unmarshal` into the generated proto struct, then re-encode via `protojson.Marshal` into the outbound NATS payload. Schema-invalid input is logged and dropped — this is the typed boundary on the Go side.

- [ ] **Step 1: Write the failing test**

Append to `/Users/canefe/Projects/personal/story-go/internal/sim/handler_test.go` (create if missing):

```go
package sim

import (
	"encoding/json"
	"testing"
	"time"

	storyv1 "github.com/canefe/story-go/gen/story/v1"
	"github.com/canefe/story-go/pkg/events"
	"google.golang.org/protobuf/encoding/protojson"
)

type fakePublisher struct {
	last events.BridgeMessage
}

func (f *fakePublisher) Publish(m events.BridgeMessage) error {
	f.last = m
	return nil
}

type alwaysActiveState struct{}

func (alwaysActiveState) IsActive() bool { return true }

func TestForwardLocationSighting_DecodesAndReEmitsAsProtoJSON(t *testing.T) {
	pub := &fakePublisher{}
	h := &Handler{pub: pub, state: alwaysActiveState{}}

	in := events.BridgeMessage{
		Type:      events.LocationSighting,
		Timestamp: time.Now().UnixMilli(),
		Source:    "story",
		Data: map[string]interface{}{
			"perceiverCharId":   "char_abc",
			"targetLocationId":  "Old Well",
			"targetLocationDef": "village_well",
			"strength":          float64(72.5),
			"x":                 float64(10),
			"y":                 float64(-60),
			"z":                 float64(25),
			"tags":              []interface{}{"water_source", "public"},
			"timestampMs":       float64(1_700_000_000_000),
		},
	}

	h.ForwardLocationSighting(in)

	if pub.last.Type != "location_sighting" {
		t.Fatalf("expected NATS type 'location_sighting', got %q", pub.last.Type)
	}
	// Round-trip the outbound Data through protojson into the generated type;
	// this proves the forwarder produced schema-valid proto-JSON.
	raw, _ := json.Marshal(pub.last.Data)
	var decoded storyv1.LocationSightingStimulus
	if err := protojson.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("outbound payload is not valid proto-JSON: %v\nraw: %s", err, raw)
	}
	if decoded.PerceiverCharId != "char_abc" {
		t.Errorf("perceiver_char_id lost: got %q", decoded.PerceiverCharId)
	}
	if decoded.TargetLocationId != "Old Well" {
		t.Errorf("target_location_id lost: got %q", decoded.TargetLocationId)
	}
	if decoded.TargetLocationDef != "village_well" {
		t.Errorf("target_location_def lost: got %q", decoded.TargetLocationDef)
	}
}

func TestForwardLocationSighting_DropsInvalidPayload(t *testing.T) {
	pub := &fakePublisher{}
	h := &Handler{pub: pub, state: alwaysActiveState{}}

	in := events.BridgeMessage{
		Type:      events.LocationSighting,
		Timestamp: time.Now().UnixMilli(),
		Source:    "story",
		Data: map[string]interface{}{
			// strength is required by proto3 schema; sending a string here must
			// cause protojson.Unmarshal to fail, and the forwarder must drop.
			"perceiverCharId":  "char_abc",
			"targetLocationId": "Old Well",
			"strength":         "not-a-number",
		},
	}

	h.ForwardLocationSighting(in)

	if pub.last.Type != "" {
		t.Fatalf("expected drop (no publish), got publish type %q", pub.last.Type)
	}
}

func TestForwardAffordanceSighting_DecodesAndReEmitsAsProtoJSON(t *testing.T) {
	pub := &fakePublisher{}
	h := &Handler{pub: pub, state: alwaysActiveState{}}

	in := events.BridgeMessage{
		Type:      events.AffordanceSighting,
		Timestamp: time.Now().UnixMilli(),
		Source:    "story",
		Data: map[string]interface{}{
			"perceiverCharId":    "char_abc",
			"targetAffordanceId": "mongo_xyz",
			"strength":           float64(50),
			"x":                  float64(3), "y": float64(64), "z": float64(0),
			"tags":               []interface{}{"water_source"},
			"timestampMs":        float64(1_700_000_000_000),
		},
	}

	h.ForwardAffordanceSighting(in)

	if pub.last.Type != "affordance_sighting" {
		t.Fatalf("expected NATS type 'affordance_sighting', got %q", pub.last.Type)
	}
	raw, _ := json.Marshal(pub.last.Data)
	var decoded storyv1.AffordanceSightingStimulus
	if err := protojson.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("outbound payload is not valid proto-JSON: %v\nraw: %s", err, raw)
	}
	if decoded.TargetAffordanceId != "mongo_xyz" {
		t.Errorf("target_affordance_id lost: got %q", decoded.TargetAffordanceId)
	}
}
```

If `Handler`'s real `state` field type differs, adapt `alwaysActiveState` to match.

- [ ] **Step 2: Run the test to confirm it fails**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./internal/sim/... -run "TestForward(Location|Affordance)Sighting" -v 2>&1 | tail -20`
Expected: FAIL — `events.LocationSighting`, `events.AffordanceSighting`, `storyv1.LocationSightingStimulus`, `ForwardLocationSighting`, `ForwardAffordanceSighting` undefined.

- [ ] **Step 3: Add the event type constants**

Open `/Users/canefe/Projects/personal/story-go/pkg/events/events.go`. Find the existing perception/sim constants. Add:

```go
// Sighting stimuli — typed proto-JSON per story.v1.LocationSightingStimulus
// and story.v1.AffordanceSightingStimulus. Distinct from PerceptionStimulus
// (character sightings) so the typed paths don't share a polymorphic message.
const (
	LocationSighting   = "location.sighting"
	AffordanceSighting = "affordance.sighting"
)
```

- [ ] **Step 4: Add the forwarders with proto-JSON validation**

Open `/Users/canefe/Projects/personal/story-go/internal/sim/handler.go`. At the top, ensure imports include:

```go
import (
	"encoding/json"
	storyv1 "github.com/canefe/story-go/gen/story/v1"
	"google.golang.org/protobuf/encoding/protojson"
)
```

After the existing `ForwardLocationSpawn` (around line 605), add:

```go
// ForwardLocationSighting decodes the inbound payload via protojson into the
// generated LocationSightingStimulus type (the schema boundary on the Go side),
// then re-encodes via protojson onto NATS subject "location_sighting".
// Schema-invalid input is logged and dropped.
func (h *Handler) ForwardLocationSighting(msg events.BridgeMessage) {
	if !h.state.IsActive() || h.pub == nil {
		return
	}
	raw, err := json.Marshal(msg.Data)
	if err != nil {
		log.Printf("[location_sighting] re-encode of Data map failed: %v", err)
		return
	}
	var typed storyv1.LocationSightingStimulus
	if err := (protojson.UnmarshalOptions{DiscardUnknown: false}).Unmarshal(raw, &typed); err != nil {
		log.Printf("[location_sighting] dropping schema-invalid payload: %v", err)
		return
	}
	out, err := (protojson.MarshalOptions{UseProtoNames: false}).Marshal(&typed)
	if err != nil {
		log.Printf("[location_sighting] proto-JSON marshal failed: %v", err)
		return
	}
	var dataOut map[string]interface{}
	if err := json.Unmarshal(out, &dataOut); err != nil {
		log.Printf("[location_sighting] proto-JSON output not a JSON object: %v", err)
		return
	}
	_ = h.pub.Publish(events.BridgeMessage{
		Type:      "location_sighting",
		Timestamp: time.Now().UnixMilli(),
		Source:    "story-go",
		Data:      dataOut,
	})
}

// ForwardAffordanceSighting — same pattern, decodes via protojson into
// AffordanceSightingStimulus before re-emitting.
func (h *Handler) ForwardAffordanceSighting(msg events.BridgeMessage) {
	if !h.state.IsActive() || h.pub == nil {
		return
	}
	raw, err := json.Marshal(msg.Data)
	if err != nil {
		log.Printf("[affordance_sighting] re-encode failed: %v", err)
		return
	}
	var typed storyv1.AffordanceSightingStimulus
	if err := protojson.Unmarshal(raw, &typed); err != nil {
		log.Printf("[affordance_sighting] dropping schema-invalid payload: %v", err)
		return
	}
	out, err := protojson.Marshal(&typed)
	if err != nil {
		log.Printf("[affordance_sighting] marshal failed: %v", err)
		return
	}
	var dataOut map[string]interface{}
	if err := json.Unmarshal(out, &dataOut); err != nil {
		log.Printf("[affordance_sighting] output not a JSON object: %v", err)
		return
	}
	_ = h.pub.Publish(events.BridgeMessage{
		Type:      "affordance_sighting",
		Timestamp: time.Now().UnixMilli(),
		Source:    "story-go",
		Data:      dataOut,
	})
}
```

If `log` isn't already imported, add `"log"` to the import block — match whatever logging shim the rest of the file uses (the existing `ForwardLocationSpawn` shows the convention).

- [ ] **Step 5: Route the message types in server.go**

Open `/Users/canefe/Projects/personal/story-go/internal/server/server.go`. Find the existing routing block where `events.LocationSpawn` is dispatched (around line 526). Add immediately after it:

```go
		case events.LocationSighting:
			s.simHandler.ForwardLocationSighting(msg)
			return
		case events.AffordanceSighting:
			s.simHandler.ForwardAffordanceSighting(msg)
			return
```

- [ ] **Step 6: Run the test to confirm it passes**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./internal/sim/... -run "TestForward(Location|Affordance)Sighting" -v 2>&1 | tail -30`
Expected: PASS, all three cases (round trip + drop-invalid + affordance round trip) green.

- [ ] **Step 7: Run the full Go test suite**

Run: `cd /Users/canefe/Projects/personal/story-go && go test ./... 2>&1 | tail -20`
Expected: all green.

- [ ] **Step 8: Commit**

```bash
cd /Users/canefe/Projects/personal/story-go
git add pkg/events/events.go internal/server/server.go internal/sim/handler.go internal/sim/handler_test.go
git commit -m "feat(sighting): typed protojson forwarders for location.sighting and affordance.sighting"
```

---

## Task 6: Sim — add `record_mentioned` convenience on LocationMemory

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/components/location_memory.rs`

The existing `record_known` takes a `LocationLearnSource` arg, which is enough — but the sighting handler will call it in a hot path with `Mentioned`, and we want a named method that documents intent at the call site.

- [ ] **Step 1: Write the failing test**

In `/Users/canefe/Projects/personal/story-sim/src/components/location_memory.rs`, in the `#[cfg(test)] mod tests` block, append:

```rust
    #[test]
    fn record_mentioned_writes_unvisited_entry_with_mentioned_source() {
        let mut mem = LocationMemory::default();
        let e = Entity::from_raw_u32(7).unwrap();
        mem.record_mentioned(
            e,
            "village_well".into(),
            "Old Well".into(),
            Vec3::new(10.0, -60.0, 25.0),
            4.0,
            vec!["water_source".into(), "public".into()],
            5.0,
        );
        let entry = mem.known.get(&e).expect("entry written");
        assert_eq!(entry.def_id, "village_well");
        assert_eq!(entry.last_visited_time, None);
        assert!(matches!(entry.learned_via, LocationLearnSource::Mentioned));
        assert_eq!(entry.last_referenced_time, 5.0);
    }

    #[test]
    fn record_mentioned_does_not_clobber_visited_entry() {
        let mut mem = LocationMemory::default();
        let e = Entity::from_raw_u32(7).unwrap();
        mem.record_visit(e, "x".into(), "X".into(), Vec3::ZERO, 1.0, vec![], 10.0);
        mem.record_mentioned(e, "x".into(), "X".into(), Vec3::ZERO, 1.0, vec![], 20.0);
        let entry = mem.known.get(&e).unwrap();
        assert_eq!(entry.last_visited_time, Some(10.0), "visit preserved");
        assert!(matches!(entry.learned_via, LocationLearnSource::Visited));
        assert_eq!(entry.last_referenced_time, 20.0, "ref-time bumped");
    }

    #[test]
    fn record_mentioned_refreshes_existing_mentioned_ref_time() {
        let mut mem = LocationMemory::default();
        let e = Entity::from_raw_u32(7).unwrap();
        mem.record_mentioned(e, "x".into(), "X".into(), Vec3::ZERO, 1.0, vec![], 5.0);
        mem.record_mentioned(e, "x".into(), "X".into(), Vec3::ZERO, 1.0, vec![], 99.0);
        let entry = mem.known.get(&e).unwrap();
        assert_eq!(entry.last_referenced_time, 99.0);
        assert!(matches!(entry.learned_via, LocationLearnSource::Mentioned));
    }
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features --lib location_memory:: 2>&1 | tail -20`
Expected: FAIL — `record_mentioned` does not exist.

- [ ] **Step 3: Add `record_mentioned`**

In `/Users/canefe/Projects/personal/story-sim/src/components/location_memory.rs`, inside `impl LocationMemory`, after `record_known`, add:

```rust
    /// Record that the NPC PERCEIVED a location (saw the landmark) without
    /// having visited it. Equivalent to `record_known(..., Mentioned)` but
    /// explicit at the call site so perception code reads obviously. Does not
    /// downgrade an already-visited entry; bumps `last_referenced_time` on
    /// existing entries.
    pub fn record_mentioned(
        &mut self,
        loc_entity: Entity,
        def_id: String,
        instance_name: String,
        center: Vec3,
        radius: f32,
        tags: Vec<String>,
        now: f32,
    ) {
        self.record_known(
            loc_entity,
            def_id,
            instance_name,
            center,
            radius,
            tags,
            now,
            LocationLearnSource::Mentioned,
        );
    }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features --lib location_memory:: 2>&1 | tail -20`
Expected: PASS, all three new cases plus the existing ones green.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
jj describe -m "feat(location_memory): add record_mentioned for perception-sourced knowledge"
```

(Git fallback: `git add src/components/location_memory.rs && git commit -m "..."`.)

---

## Task 7: Sim — location_sighting stream handler using prost-generated typed deserialization

**Files:**
- Create: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/location_sighting.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`

This handler does NOT use `StreamMessage::str_field` / `f32_field`. It deserializes `msg.payload` (the raw JSON string) directly into `story::v1::LocationSightingStimulus` via `serde_json::from_str`. The prost-generated type has `#[derive(serde::Deserialize)]` and `#[serde(rename_all = "camelCase")]` per `build.rs:12-14`, so it accepts the canonical proto-JSON.

- [ ] **Step 1: Confirm the generated-type import path**

Check `/Users/canefe/Projects/personal/story-sim/src/wire/mod.rs` (or wherever the Task 1 Step 4 scratch tests imported the proto types from) and copy the exact module path. The path is one of:
- `crate::story::v1::LocationSightingStimulus`
- `crate::proto::story::v1::LocationSightingStimulus`
- `crate::gen::story::v1::LocationSightingStimulus`

Use whichever path the Step 4 tests in Task 1 used.

- [ ] **Step 2: Write the handler with embedded tests**

Create `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/location_sighting.rs`:

```rust
//! Stream handler for `location_sighting` NATS messages.
//!
//! Deserializes the JSON payload directly into the prost-generated
//! `story::v1::LocationSightingStimulus` type (the schema boundary on the sim
//! side — schema-invalid input is logged and dropped, not silently zero-
//! filled). Resolves the perceiver by `ExternalId`, the target by
//! `Location.instance_name`, then writes the entry into the perceiver's
//! `LocationMemory` with `LocationLearnSource::Mentioned`.

use bevy::prelude::*;
use tracing::{debug, warn};

// Update this path to match the actual generated module location (see Step 1).
use crate::story::v1::LocationSightingStimulus;

use crate::components::location::Location;
use crate::components::location_memory::LocationMemory;
use crate::components::external_control::ExternalId;
use crate::plugins::stream_handlers::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry {
        msg_types: &["location_sighting"],
        handler: handle,
    });
}

fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let typed: LocationSightingStimulus = match serde_json::from_str(&msg.payload) {
        Ok(t) => t,
        Err(e) => {
            warn!("[location_sighting] schema-invalid payload dropped: {} (payload={})", e, msg.payload);
            return;
        }
    };
    if typed.perceiver_char_id.is_empty() || typed.target_location_id.is_empty() {
        warn!("[location_sighting] missing required field: perceiver={:?} target={:?}",
            typed.perceiver_char_id, typed.target_location_id);
        return;
    }

    let now = ctx.time.elapsed_secs();

    // Resolve perceiver by ExternalId via the existing main query.
    let mut perceiver_entity: Option<Entity> = None;
    for (entity, _, _, _, ext, _) in ctx.query.iter() {
        if let Some(eid) = ext {
            if eid.0 == typed.perceiver_char_id {
                perceiver_entity = Some(entity);
                break;
            }
        }
    }
    let Some(perceiver) = perceiver_entity else {
        debug!("[location_sighting] no perceiver with ExternalId {}", typed.perceiver_char_id);
        return;
    };

    // Resolve target Location by instance_name via a one-off ad-hoc query.
    // (Locations aren't in the main StreamHandlerContext query yet — adding
    // them there would broaden the SystemParam churn beyond this task. Spawn
    // a Commands action that runs in a follow-up system instead.)
    //
    // Simpler: enqueue the resolved data via a small Bevy resource that a
    // dedicated apply system drains next tick. The pattern matches
    // `lua_world_api::SpawnLocationQueue`. To keep this task self-contained
    // and avoid plumbing a new queue, we instead extend StreamHandlerContext
    // with a location query (see Step 3).
    //
    // After Step 3's context extension, the resolution looks like:
    let mut found: Option<(Entity, String, String, Vec3, f32, Vec<String>)> = None;
    for (loc_entity, loc) in ctx.location_query.iter() {
        if loc.instance_name == typed.target_location_id {
            found = Some((
                loc_entity,
                loc.def_id.clone(),
                loc.instance_name.clone(),
                loc.center,
                loc.radius,
                loc.tags.clone(),
            ));
            break;
        }
    }
    let Some((loc_entity, def_id, instance_name, center, radius, tags)) = found else {
        debug!("[location_sighting] no Location with instance_name {} (yet?) — dropping",
            typed.target_location_id);
        return;
    };

    // Write into LocationMemory via the perceiver's memory_query.
    if let Ok(mut mem) = ctx.location_memory_query.get_mut(perceiver) {
        mem.record_mentioned(loc_entity, def_id, instance_name, center, radius, tags, now);
        debug!(perceiver = %typed.perceiver_char_id, target = %typed.target_location_id,
            "[location_sighting] recorded mentioned");
    } else {
        debug!("[location_sighting] perceiver {} has no LocationMemory component",
            typed.perceiver_char_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::components::location::Location;
    use crate::components::location_memory::{LocationLearnSource, LocationMemory};
    use crate::components::external_control::ExternalId;
    use bevy::prelude::*;

    /// Build a minimal world with one perceiver (has ExternalId + LocationMemory)
    /// and one location (has Location component).
    fn make_world() -> (App, Entity, Entity) {
        let mut app = App::new();
        app.init_resource::<Time>();
        // Spawn perceiver with the components the handler resolves through.
        let perceiver = app.world_mut().spawn((
            ExternalId("char_abc".to_string()),
            LocationMemory::default(),
            // Minimal extra components if StreamHandlerContext's query requires more.
            // Match the spawn pattern in spawn_location's own tests.
        )).id();
        let loc_entity = app.world_mut().spawn(Location {
            def_id: "village_well".to_string(),
            instance_name: "Old Well".to_string(),
            center: Vec3::new(10.0, -60.0, 25.0),
            radius: 4.0,
            tags: vec!["water_source".into(), "public".into()],
        }).id();
        (app, perceiver, loc_entity)
    }

    fn payload_for(target: &str) -> String {
        format!(r#"{{
          "perceiverCharId": "char_abc",
          "targetLocationId": "{}",
          "targetLocationDef": "village_well",
          "strength": 72.5,
          "x": 10.0, "y": -60.0, "z": 25.0,
          "tags": ["water_source", "public"],
          "timestampMs": 1700000000000
        }}"#, target)
    }

    #[test]
    fn deserializes_payload_into_prost_typed_struct() {
        let p = payload_for("Old Well");
        let typed: LocationSightingStimulus = serde_json::from_str(&p).expect("decode");
        assert_eq!(typed.perceiver_char_id, "char_abc");
        assert_eq!(typed.target_location_id, "Old Well");
        assert_eq!(typed.target_location_def, "village_well");
        assert_eq!(typed.tags, vec!["water_source", "public"]);
    }

    #[test]
    fn rejects_schema_invalid_payload() {
        // Missing required string fields; serde should produce defaults but
        // the handler's emptiness guard catches it. Test guard behavior:
        let typed: Result<LocationSightingStimulus, _> = serde_json::from_str("{}");
        assert!(typed.is_ok(), "empty object decodes (prost has defaults)");
        let t = typed.unwrap();
        assert!(t.perceiver_char_id.is_empty());
        assert!(t.target_location_id.is_empty());
        // The handler's guard (the early return when fields are empty) is
        // exercised by the in-handler integration test below.
    }

    // NOTE: A full in-handler integration test that exercises handle() against
    // a StreamMessage + StreamHandlerContext requires the same plumbing as the
    // existing handlers' tests (look at spawn_location.rs's #[cfg(test)] module
    // for the exact pattern — it builds an App, registers the schedule, sends
    // a StreamMessage through the SystemParam-bound function). Copy that
    // pattern; the assertions check that LocationMemory.known contains an
    // entry with last_visited_time == None and learned_via == Mentioned.
    //
    // If spawn_location.rs does NOT have integration tests for its own
    // handler, add one for this handler using the in-App spawn pattern:
    //
    // #[test]
    // fn handler_writes_mentioned_entry() {
    //     let (mut app, perceiver, loc_entity) = make_world();
    //     // Inject the handler into the test schedule and feed it a
    //     // StreamMessage. See lua_world_api spawn_locations integration
    //     // tests for the established pattern in this crate.
    //     // After app.update():
    //     let mem = app.world().get::<LocationMemory>(perceiver).unwrap();
    //     let entry = mem.known.get(&loc_entity).expect("entry written");
    //     assert!(entry.last_visited_time.is_none());
    //     assert!(matches!(entry.learned_via, LocationLearnSource::Mentioned));
    // }
}
```

- [ ] **Step 3: Extend `StreamHandlerContext` with location and location-memory queries**

Open `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`. Add to the `StreamHandlerContext` struct (around lines 68–104):

```rust
    pub location_query: Query<'w, 's, (Entity, &'static components::location::Location)>,
    pub location_memory_query: Query<'w, 's, &'static mut components::location_memory::LocationMemory>,
```

If existing handlers' query parameters conflict with these (Bevy disallows overlapping mutable access across `SystemParam` fields), use `Without<...>` filters or split into a sub-`SystemParam`. The simpler alternative is to add a dedicated `LocationSightingParam` `SystemParam` struct used only by this handler, leaving the main context alone:

```rust
// In mod.rs:
#[derive(SystemParam)]
pub struct LocationSightingParam<'w, 's> {
    pub locations: Query<'w, 's, (Entity, &'static components::location::Location)>,
    pub memories: Query<'w, 's, &'static mut components::location_memory::LocationMemory>,
}
```

…but then the handler signature changes to `fn handle(msg, ctx, loc_param)` which breaks the `HandlerFn` type alias on line 106. The cleanest fix is to widen `StreamHandlerContext` directly; this task assumes that approach.

- [ ] **Step 4: Register the handler module**

In `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`, add to the top:

```rust
mod location_sighting;
```

And in `fn handlers()` (line 113), add:

```rust
location_sighting::register(&mut entries);
```

- [ ] **Step 5: Run the tests**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features --lib stream_handlers::location_sighting 2>&1 | tail -30`
Expected: PASS for the deserialization tests. If the integration test is added per the NOTE in the file, also PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
jj describe -m "feat(sim): location_sighting handler uses prost-typed deserialization, writes Mentioned entries"
```

---

## Task 8: Sim — affordance_sighting stream handler

**Files:**
- Create: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/affordance_sighting.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`

Mirror of Task 7 against the prost-typed `AffordanceSightingStimulus`, `Memory.known_affordances`, and the `AffordanceInstanceId` component. `StreamHandlerContext` already has `affordance_query` on line 85 of mod.rs — good, no widening needed for the affordance lookup. We do need access to `Memory` (not currently in the context).

- [ ] **Step 1: Extend `StreamHandlerContext` with memory access**

In `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`, add to the struct:

```rust
    pub memory_query: Query<'w, 's, &'static mut components::memory::Memory>,
    pub position_query: Query<'w, 's, &'static components::position::Position>,
    pub affordance_full_query: Query<'w, 's, &'static components::affordance::Affordance>,
```

(The existing `affordance_query` on line 85 only exposes `AffordanceInstanceId` — we need the full `Affordance` for tags. Position is needed because `Memory.remember_affordance` takes a `world: &str` argument.)

- [ ] **Step 2: Write the handler with embedded tests**

Create `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/affordance_sighting.rs`:

```rust
//! Stream handler for `affordance_sighting` NATS messages.
//!
//! Deserializes the JSON payload into `story::v1::AffordanceSightingStimulus`
//! via prost-generated serde derives, resolves perceiver by `ExternalId` and
//! target by `AffordanceInstanceId`, then writes into the perceiver's
//! `Memory.known_affordances` via `remember_affordance`.

use bevy::prelude::*;
use tracing::{debug, warn};

// Match the path used in Task 1's scratch test (see Task 7 Step 1).
use crate::story::v1::AffordanceSightingStimulus;

use crate::components::affordance::Affordance;
use crate::components::external_control::ExternalId;
use crate::components::memory::Memory;
use crate::components::position::Position;
use crate::plugins::stream_handlers::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry {
        msg_types: &["affordance_sighting"],
        handler: handle,
    });
}

fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let typed: AffordanceSightingStimulus = match serde_json::from_str(&msg.payload) {
        Ok(t) => t,
        Err(e) => {
            warn!("[affordance_sighting] schema-invalid payload dropped: {}", e);
            return;
        }
    };
    if typed.perceiver_char_id.is_empty() || typed.target_affordance_id.is_empty() {
        warn!("[affordance_sighting] missing required field: perceiver={:?} target={:?}",
            typed.perceiver_char_id, typed.target_affordance_id);
        return;
    }
    let now = ctx.time.elapsed_secs();

    // Resolve perceiver entity by ExternalId.
    let mut perceiver_entity: Option<Entity> = None;
    for (entity, _, _, _, ext, _) in ctx.query.iter() {
        if let Some(eid) = ext {
            if eid.0 == typed.perceiver_char_id {
                perceiver_entity = Some(entity);
                break;
            }
        }
    }
    let Some(perceiver) = perceiver_entity else {
        debug!("[affordance_sighting] no perceiver with ExternalId {}", typed.perceiver_char_id);
        return;
    };

    // Resolve target affordance entity by AffordanceInstanceId.
    let mut aff_entity: Option<Entity> = None;
    for (entity, inst) in ctx.affordance_query.iter() {
        if inst.0 == typed.target_affordance_id {
            aff_entity = Some(entity);
            break;
        }
    }
    let Some(aff_entity) = aff_entity else {
        debug!("[affordance_sighting] no affordance with id {} (yet?) — dropping",
            typed.target_affordance_id);
        return;
    };

    // Pull tags from the full Affordance component, and world+position from Position.
    let tags = ctx.affordance_full_query.get(aff_entity).map(|a| a.tags.clone()).unwrap_or_default();
    let (world, pos) = ctx.position_query.get(aff_entity)
        .map(|p| (p.world.clone(), p.translation))
        .unwrap_or_else(|_| ("main".to_string(), Vec3::ZERO));

    // Write into Memory.known_affordances.
    if let Ok(mut mem) = ctx.memory_query.get_mut(perceiver) {
        mem.remember_affordance(aff_entity, &world, pos, &tags, now);
        debug!(perceiver = %typed.perceiver_char_id, target = %typed.target_affordance_id,
            "[affordance_sighting] recorded known affordance");
    } else {
        debug!("[affordance_sighting] perceiver {} has no Memory component",
            typed.perceiver_char_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deserializes_payload_into_prost_typed_struct() {
        let p = r#"{
          "perceiverCharId": "char_abc",
          "targetAffordanceId": "mongo_xyz",
          "strength": 50.0,
          "x": 3.0, "y": 64.0, "z": 0.0,
          "tags": ["water_source"],
          "timestampMs": 1700000000000
        }"#;
        let typed: AffordanceSightingStimulus = serde_json::from_str(p).expect("decode");
        assert_eq!(typed.perceiver_char_id, "char_abc");
        assert_eq!(typed.target_affordance_id, "mongo_xyz");
        assert_eq!(typed.tags, vec!["water_source"]);
    }

    // For full handler integration: copy the pattern from
    // spawn_affordance.rs's #[cfg(test)] block (the closest analog in this
    // crate — it builds an App, dispatches a StreamMessage, and asserts on
    // the resulting world state).
}
```

- [ ] **Step 3: Register the handler**

Open `/Users/canefe/Projects/personal/story-sim/src/plugins/stream_handlers/mod.rs`. Add:

```rust
mod affordance_sighting;
```

And in `fn handlers()`:

```rust
affordance_sighting::register(&mut entries);
```

- [ ] **Step 4: Run the tests**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features --lib stream_handlers::affordance_sighting 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 5: Run the full sim test suite**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --no-default-features 2>&1 | tail -30`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
jj describe -m "feat(sim): affordance_sighting handler uses prost-typed deserialization, writes into Memory.known_affordances"
```

---

## Task 9: Integration smoke test (manual)

**Files:** none (manual verification via `make dev` and in-game commands).

- [ ] **Step 1: Start the full dev stack**

```bash
cd /Users/canefe/Projects/personal/sf
make dev
```

Expected: zellij layout opens with sim, orchestrator, ps, logs tabs. cargo-watch picks up the new sim handlers.

- [ ] **Step 2: Spawn an NPC and a well**

```
/story npc spawn TestNpc
/story location create "Old Well" village_well
```

- [ ] **Step 3: Verify location-sighting logs**

In the sim logs tab, watch for:
```
[location_sighting] recorded mentioned perceiver=<char_id_of_TestNpc> target=Old Well
```
This should appear within ~2s of the NPC having LOS to the well coordinates.

If you instead see:
```
[location_sighting] schema-invalid payload dropped: <error>
```
that means the Kotlin DTO drifted from the proto canonical-JSON — re-run `SightingProtoCompatTest` in the Kotlin repo to identify which field disagrees.

- [ ] **Step 4: Verify thirst → travel works**

Set TestNpc's thirst low:
```
/story sim need set TestNpc thirst 10
```
Sim logs should show TestNpc select `quench_thirst` → `drink_at_source` (because `LocationMemory` now has "Old Well") → path to well → `seek_water` runs `modify_need("thirst", 40.0)`.

If TestNpc falls through with "no known location matching water_source", `LocationMemory` isn't being populated — re-check Task 7's handler tests and confirm `record_mentioned` is being called.

- [ ] **Step 5: Verify affordance-sighting**

Spawn a `water_source` affordance via `/story affordance spawn water_source` (or whatever the existing command is — see `spawn_affordances.lua` for what's spawned at world-ready). Watch sim logs for:
```
[affordance_sighting] recorded known affordance perceiver=<id> target=<mongo_id>
```

- [ ] **Step 6: Commit any fixes**

If integration testing surfaced bugs, fix them in the relevant repo with a follow-up commit. If clean, no commit needed.

---

## Self-Review Notes

**Spec coverage:**
- ✅ Direct LOS sightings only (per user's answer) — Task 4 Step 3d.
- ✅ **Typed proto events** (per user's correction) — `events.proto` defines the messages, prost generates serde-deserializable Rust types, protojson validates on the Go boundary, hand-written Kotlin DTO + golden-JSON compat test enforce schema on the Kotlin side (the only side without codegen). Wire encoding is proto canonical-JSON, the existing stack convention.
- ✅ Writes into `LocationMemory.known` with `Mentioned` source — Task 7 calls `record_mentioned`.
- ✅ Plan-only, no design doc.

**Placeholder scan:**
1. Task 7 Step 1 says "confirm the generated-type import path" — this is necessary because the prost output path varies by `build.rs` config (`crate::story::v1::...` vs `crate::proto::story::v1::...`); the value is grep-discoverable in 30 seconds, not a placeholder.
2. Tasks 7 and 8 leave integration-level test bodies as notes pointing at `spawn_location.rs` / `spawn_affordance.rs` patterns. The plain unit tests (deserialization) are written in full; the in-App handler integration tests reference the established crate pattern rather than reinventing it. Acceptable, but if the spawn handlers DON'T have integration tests in their own `#[cfg(test)]` blocks, the executor needs to write the App-based pattern from scratch — flag in code review.
3. Task 4 Step 4's `PluginPerceptionContext.npcsForPerception()` and `characterTargets()` are documented as "lift from old PerceptionBroadcaster body" rather than written out, because the original code is ~50 lines of plugin lookups and reproducing it verbatim adds noise without value; the executor reads the existing file. Acceptable.

**Type consistency:**
- `perceiver_char_id` (proto, snake) ↔ `perceiverCharId` (JSON, Kotlin, Rust serde-renamed, Go protojson) — consistent across the field-key table, Task 2 DTO, Task 3 compat test, Task 5 protojson round trip, Tasks 7/8 prost deserialization.
- `target_location_id` / `target_location_def` likewise.
- `target_affordance_id` likewise.
- `record_mentioned(loc_entity, def_id, instance_name, center, radius, tags, now)` — signature in Task 6 implementation matches the call in Task 7's handler.
- `Memory::remember_affordance(entity, world: &str, position: Vec3, tags: &[String], current_time: f32)` — signature from `memory.rs:33-51` (read into context) matches the call in Task 8's handler.
- `LocationSightingStimulus` / `AffordanceSightingStimulus` — same name across proto, Go, and Rust; Kotlin uses `*Event` suffix per existing `*Event` DTO convention.

**Spec gaps:** none. The typed-proto requirement is enforced at three boundaries (Kotlin compat test, Go protojson validation, Rust prost deserialization); the JSON map-poking pattern of existing handlers is explicitly NOT used for the new ones.
