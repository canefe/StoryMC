# Intent Vocabulary Redesign — `go_to` Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a protobuf source-of-truth with codegen in all three repos (Rust/Go/Kotlin), remove the gRPC transport (re-homing its queries onto WebSocket), and unify the two movement lanes (`navigate_to` + `npc.move`) into one outcome-tracked `go_to` intent.

**Architecture:** Proto messages in `story-proto` (git submodule) are the single source of truth; each repo codegens types and exchanges them as proto-canonical-JSON over the existing WebSocket+NATS transport (no gRPC binary on the wire). Three phases: (1) stand up the codegen pipeline + freeze the vocabulary, (2) delete the gRPC transport after re-homing its two queries, (3) collapse both movement lanes into `go_to` with full outcome round-trip and supersede semantics.

**Tech Stack:** protobuf3 + protoc; Rust `prost`/`prost-build` + `build.rs`; Go `protoc-gen-go` (`make proto`, existing); Kotlin Gradle `com.google.protobuf` plugin; Bevy ECS (story-sim); Paper/MockBukkit + JUnit5 + kotlinx.serialization (StoryMC).

**Spec:** `docs/superpowers/specs/2026-05-23-intent-vocabulary-redesign-design.md`

---

## File Structure

**story-proto** (`/Users/canefe/Projects/personal/story-proto`)
- Modify: `story/v1/intents.proto` — add `go_to` + the frozen vocabulary messages
- Modify: `story/v1/events.proto` — add outcome messages (`IntentCompleted`/`IntentRejected`), `RejectReason` enum
- Modify: `story/v1/service.proto` — remove `StoryBridge` service (Phase 2)
- Create: `buf.gen.yaml` (optional, reproducible multi-target codegen) — see Task 1.2

**story-go** (`/Users/canefe/Projects/personal/story-go`)
- Modify: `proto/` submodule pointer; regenerate `gen/story/v1/*.pb.go` via `make proto`
- Modify: `internal/sim/events.go` — add `go_to` to `IsSimEvent`
- Modify: `pkg/events/events.go` — `GoTo` constant
- Modify: `internal/server/server.go` — re-home queries, drop gRPC from MultiSender + listener
- Delete: `internal/grpc/` (server.go, convert.go, etc.)

**story-sim** (`/Users/canefe/Projects/personal/story-sim`)
- Modify: `Cargo.toml` — add `prost`, `prost-build` (build-dep)
- Create: `build.rs` — compile `.proto` via `prost-build`
- Create: `src/proto.rs` (or `src/wire/mod.rs`) — `include!` the generated module + JSON helpers
- Modify: `src/plugins/frontend_intent.rs` — `navigate_to` → `go_to`
- Modify: `src/plugins/behavior/movement.rs` — ECS `npc.move` → `go_to` with intentId + PendingIntents
- Modify: `packs/BaseGame/lua/defs/behaviors/head_to_known_location.lua` — `"navigate_to"` → `"go_to"`

**StoryMC** (`/Users/canefe/Projects/personal/Story`)
- Modify: `build.gradle.kts` — add `com.google.protobuf` plugin + proto source dir
- Create: proto symlink/copy under `src/main/proto/story/v1/` (plugin source root)
- Modify: `bridge/IntentExecutor.kt` — `go_to` handler, delete `executeMoveIntent` + `navigate_to` branch, supersede-reject
- Modify: `bridge/WebSocketTransport.kt` — `go_to` dispatch
- Modify: `bridge/DomainEvents.kt` / `bridge/StoryEvent.kt` — replace movement/outcome DTOs with generated types (or thin adapters)
- Test: `src/test/kotlin/com/canefe/story/bridge/GoToWireTest.kt`, `GoToSupersedeTest.kt`

---

## A note on TDD across language boundaries

Each repo has its own test runner. Per step, the test command is given explicitly:
- **Rust (story-sim):** `cargo test --bin story-sim <name>` (binary-only crate; NOT `--lib`)
- **Go (story-go):** `go test ./... -run <Name>`
- **Kotlin (StoryMC):** `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test --tests "<FQCN>"`
- **Lua (story-sim packs):** existing harness `cargo test --bin story-sim lua_` or the pack test runner used by `need_tasks_test.lua`

Commit after each green step. Branch first (this is HEAD/detached per git status):
```bash
git checkout -b feat/intent-vocab-go-to
```

---

# PHASE 1 — Proto pipeline + codegen (foundation, no behavior change)

## Task 1.1: Add the `go_to` + outcome vocabulary to proto

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-proto/story/v1/intents.proto`
- Modify: `/Users/canefe/Projects/personal/story-proto/story/v1/events.proto`

- [ ] **Step 1: Add the movement + frozen-vocabulary messages to `intents.proto`**

Append after the existing `CharacterMoveIntent` (keep that one for now; it is the gRPC-era message and is removed in Phase 2 with the service). Add:

```proto
// ── Frontend intents (Sim → Plugin, JSON wire) ───────────────────────
// These carry intent_id + expect an IntentOutcome back.

enum TargetKind {
  TARGET_KIND_UNSPECIFIED = 0;
  TARGET_KIND_PLAYER = 1;
  TARGET_KIND_NPC = 2;
}

enum SwingDir {
  SWING_DIR_UNSPECIFIED = 0;
  SWING_DIR_OVERHEAD = 1;
  SWING_DIR_LEFT = 2;
  SWING_DIR_RIGHT = 3;
  SWING_DIR_THRUST = 4;
}

enum AnimationKind {
  ANIMATION_KIND_NONE = 0;
  ANIMATION_KIND_EAT = 1;
  ANIMATION_KIND_DRINK = 2;
  ANIMATION_KIND_REST = 3;
  ANIMATION_KIND_SOCIALIZE = 4;
  ANIMATION_KIND_TRADE = 5;
}

// Unified movement intent: replaces navigate_to + npc.move.
message GoToIntent {
  string character_id = 1;
  string intent_id = 2;
  double x = 3;
  double y = 4;
  double z = 5;
  string world = 6;
  double arrival_range = 7;   // default applied by consumer when 0
  double stall_timeout = 8;
  double max_duration = 9;
}

// ── The rest of the frozen vocabulary (defined now, wired later) ─────
message BehaviorSetIntent {
  string character_id = 1;
  string behavior_id = 2;
  AnimationKind animation = 3;
  optional string label = 4;
}
message BehaviorClearIntent { string character_id = 1; }

message FleeIntent {
  string character_id = 1;
  string intent_id = 2;
  double x = 3;
  double z = 4;
  string world = 5;
}
message AttackIntent {
  string character_id = 1;
  string intent_id = 2;
  string target = 3;
  TargetKind target_kind = 4;
  SwingDir swing = 5;
}
message LookAtIntent {
  string character_id = 1;
  string intent_id = 2;
  optional string target = 3;
  optional double x = 4;
  optional double y = 5;
  optional double z = 6;
  double max_yaw = 7;
  double max_pitch = 8;
}
message SetTargetIntent {
  string character_id = 1;
  string intent_id = 2;
  string target = 3;
  TargetKind target_kind = 4;
}
message ClearTargetIntent {
  string character_id = 1;
  string intent_id = 2;
}
```

- [ ] **Step 2: Add the outcome messages + RejectReason to `events.proto`**

```proto
// ── Intent outcomes (Plugin → Sim, JSON wire) ────────────────────────

enum RejectReason {
  REJECT_REASON_UNSPECIFIED = 0;
  REJECT_REASON_NPC_NOT_FOUND = 1;
  REJECT_REASON_TARGET_NOT_FOUND = 2;
  REJECT_REASON_OUT_OF_RANGE = 3;
  REJECT_REASON_NPC_DEAD = 4;
  REJECT_REASON_NPC_BUSY = 5;
  REJECT_REASON_INVALID_PRIMITIVE = 6;
  REJECT_REASON_UNSUPPORTED_BACKEND = 7;
  REJECT_REASON_EXECUTION_ERROR = 8;
  REJECT_REASON_UNREACHABLE = 9;
  REJECT_REASON_TIMEOUT = 10;
  REJECT_REASON_SUPERSEDED = 11;
}

message IntentCompleted {
  string character_id = 1;
  string intent_id = 2;
  string primitive = 3;
}
message IntentRejected {
  string character_id = 1;
  string intent_id = 2;
  string primitive = 3;
  RejectReason reason = 4;
}
```

- [ ] **Step 3: Validate proto compiles (story-go is the existing compiler)**

The fastest validation is running story-go's existing `make proto` against a local copy. From story-go with the submodule temporarily pointed at the local edit (or copy the two files into `proto/story/v1/`):

Run:
```bash
cd /Users/canefe/Projects/personal/story-go
protoc --proto_path=proto --go_out=/tmp/genproof --go_opt=paths=source_relative \
  proto/story/v1/intents.proto proto/story/v1/events.proto
```
Expected: exit 0, files appear in `/tmp/genproof/story/v1/`. Any field-number collision or syntax error fails here.

- [ ] **Step 4: Commit (story-proto)**

```bash
cd /Users/canefe/Projects/personal/story-proto
git add story/v1/intents.proto story/v1/events.proto
git commit -m "feat: add go_to + frontend-intent vocabulary and intent outcomes"
```

---

## Task 1.2: Regenerate Go types from the new proto (pipeline EXISTS)

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/.gitmodules` pointer (submodule bump)
- Modify: `/Users/canefe/Projects/personal/story-go/gen/story/v1/*.pb.go` (generated)

- [ ] **Step 1: Bump the proto submodule to the new commit**

```bash
cd /Users/canefe/Projects/personal/story-go
cd proto && git fetch && git checkout main && git pull && cd ..
git submodule status   # expect the new story-proto commit hash
```

- [ ] **Step 2: Install codegen tools if missing, then regenerate**

Run:
```bash
cd /Users/canefe/Projects/personal/story-go
make tools   # installs protoc-gen-go + protoc-gen-go-grpc (idempotent)
make proto
```
Expected: `gen/story/v1/intents.pb.go` and `events.pb.go` now contain `GoToIntent`, `IntentCompleted`, `IntentRejected`, `RejectReason`, the enums.

- [ ] **Step 3: Verify generated symbols exist**

Run:
```bash
cd /Users/canefe/Projects/personal/story-go
grep -l "type GoToIntent struct" gen/story/v1/intents.pb.go && \
grep -l "RejectReason_REJECT_REASON_SUPERSEDED" gen/story/v1/events.pb.go
```
Expected: both filenames printed (grep -l prints the file on match).

- [ ] **Step 4: Confirm the module still builds**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./...`
Expected: exit 0.

- [ ] **Step 5: Commit (story-go)**

```bash
cd /Users/canefe/Projects/personal/story-go
git add proto gen/story/v1
git commit -m "chore(proto): bump submodule + regen go types for go_to vocabulary"
```

---

## Task 1.3: Stand up prost codegen in story-sim (NET-NEW)

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/Cargo.toml`
- Create: `/Users/canefe/Projects/personal/story-sim/build.rs`
- Create: `/Users/canefe/Projects/personal/story-sim/proto/` (vendored copy of the two `.proto` + their deps) OR a submodule
- Create: `/Users/canefe/Projects/personal/story-sim/src/wire/mod.rs`

- [ ] **Step 1: Vendor the proto files story-sim needs**

prost-build needs every imported proto on disk. Copy the story/v1 tree:
```bash
cd /Users/canefe/Projects/personal/story-sim
mkdir -p proto/story/v1
cp /Users/canefe/Projects/personal/story-proto/story/v1/*.proto proto/story/v1/
```
(Or add story-proto as a git submodule at `proto/`. Vendoring is simpler for a binary crate; submodule keeps it in sync — pick one and note it in the commit. This plan vendors.)

- [ ] **Step 2: Add prost deps to Cargo.toml**

In `[dependencies]` add:
```toml
prost = "0.13"
```
Add a new section:
```toml
[build-dependencies]
prost-build = "0.13"
```

- [ ] **Step 3: Write `build.rs`**

```rust
fn main() {
    let protos = [
        "proto/story/v1/intents.proto",
        "proto/story/v1/events.proto",
        "proto/story/v1/intelligence.proto",
        "proto/story/v1/query.proto",
    ];
    for p in protos {
        println!("cargo:rerun-if-changed={p}");
    }
    prost_build::compile_protos(&protos, &["proto/"])
        .expect("failed to compile protos");
}
```

- [ ] **Step 4: Create the wire module that includes generated code**

`src/wire/mod.rs`:
```rust
// Generated by prost-build at compile time; package story.v1 → module story::v1.
pub mod story {
    pub mod v1 {
        include!(concat!(env!("OUT_DIR"), "/story.v1.rs"));
    }
}
pub use story::v1::*;
```

Register the module in `src/main.rs` (add near the other `mod` decls):
```rust
mod wire;
```

- [ ] **Step 5: Build to verify prost generates and compiles**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build --bin story-sim`
Expected: exit 0; `target/.../build/.../out/story.v1.rs` exists.

- [ ] **Step 6: Write a round-trip test proving the generated type encodes to proto-JSON**

prost itself does not emit JSON; for proto-canonical-JSON we serialize via `serde`. prost types do NOT derive serde by default — enable it. In `build.rs`, before `compile_protos`, configure:

```rust
fn main() {
    let protos = [
        "proto/story/v1/intents.proto",
        "proto/story/v1/events.proto",
        "proto/story/v1/intelligence.proto",
        "proto/story/v1/query.proto",
    ];
    for p in protos { println!("cargo:rerun-if-changed={p}"); }
    let mut cfg = prost_build::Config::new();
    cfg.type_attribute(".", "#[derive(serde::Serialize, serde::Deserialize)]");
    cfg.compile_protos(&protos, &["proto/"]).expect("compile protos");
}
```

Add a test in `src/wire/mod.rs`:
```rust
#[cfg(test)]
mod tests {
    use super::GoToIntent;

    #[test]
    fn go_to_round_trips_json() {
        let g = GoToIntent {
            character_id: "npc_1".into(),
            intent_id: "abc".into(),
            x: 1.0, y: 64.0, z: 2.0,
            world: "world".into(),
            arrival_range: 3.5, stall_timeout: 30.0, max_duration: 300.0,
        };
        let j = serde_json::to_string(&g).unwrap();
        let back: GoToIntent = serde_json::from_str(&j).unwrap();
        assert_eq!(g.character_id, back.character_id);
        assert_eq!(g.intent_id, back.intent_id);
        assert_eq!(g.x, back.x);
        assert_eq!(g.world, back.world);
    }
}
```

- [ ] **Step 7: Run the round-trip test (expect FAIL first if serde attr not yet applied, then PASS)**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo test --bin story-sim go_to_round_trips_json`
Expected: PASS after the serde `type_attribute` is in `build.rs`.

- [ ] **Step 8: Commit (story-sim)**

```bash
cd /Users/canefe/Projects/personal/story-sim
git add Cargo.toml build.rs proto src/wire/mod.rs src/main.rs
git commit -m "build: add prost codegen + serde-json round-trip for proto types"
```

---

## Task 1.4: Stand up protobuf Gradle codegen in StoryMC (NET-NEW)

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/build.gradle.kts`
- Create: `/Users/canefe/Projects/personal/Story/src/main/proto/story/v1/*.proto` (copied)
- Test: `/Users/canefe/Projects/personal/Story/src/test/kotlin/com/canefe/story/bridge/GoToWireTest.kt`

- [ ] **Step 1: Copy proto into the Gradle proto source root**

```bash
cd /Users/canefe/Projects/personal/Story
mkdir -p src/main/proto/story/v1
cp /Users/canefe/Projects/personal/story-proto/story/v1/*.proto src/main/proto/story/v1/
```

- [ ] **Step 2: Add the protobuf Gradle plugin**

In `build.gradle.kts` plugins block (lines ~35-43), add:
```kotlin
id("com.google.protobuf") version "0.9.4"
```
In dependencies, add the runtime + a JSON-capable runtime (protobuf-java-util for canonical JSON):
```kotlin
implementation("com.google.protobuf:protobuf-java:3.25.5")
implementation("com.google.protobuf:protobuf-java-util:3.25.5")
implementation("com.google.protobuf:protobuf-kotlin:3.25.5")
```
After the `dependencies { }` block, configure the plugin:
```kotlin
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
        }
    }
}
```

- [ ] **Step 3: Generate + confirm the build sees the types**

Run:
```bash
cd /Users/canefe/Projects/personal/Story
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew generateProto compileKotlin
```
Expected: BUILD SUCCESSFUL; generated `com.canefe.storyproto.v1.GoToIntent` available on the classpath. (The `.proto` `java_package` is already `com.canefe.storyproto.v1`.)

- [ ] **Step 4: Write the wire round-trip test (mirror the existing wire-test idiom)**

`src/test/kotlin/com/canefe/story/bridge/GoToWireTest.kt`:
```kotlin
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
        assertEquals(g.characterId, parsed.build().characterId)
        assertEquals(g.x, parsed.build().x)
    }
}
```

- [ ] **Step 5: Run it**

Run:
```bash
cd /Users/canefe/Projects/personal/Story
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
./gradlew test --tests "com.canefe.story.bridge.GoToWireTest"
```
Expected: PASS. (Note: proto-canonical-JSON uses camelCase field names by default — `characterId` — which matches the existing Kotlin DTO casing. Confirm this matches what the sim emits in Phase 3.)

- [ ] **Step 6: Commit (StoryMC)**

```bash
cd /Users/canefe/Projects/personal/Story
git add build.gradle.kts src/main/proto src/test/kotlin/com/canefe/story/bridge/GoToWireTest.kt
git commit -m "build: add protobuf gradle codegen + go_to canonical-json round-trip test"
```

---

## Task 1.5: Cross-repo golden-JSON pipeline proof

**Files:**
- Create: `/Users/canefe/Projects/personal/story-proto/testdata/go_to.golden.json`

- [ ] **Step 1: Define one canonical JSON document for a GoToIntent**

`testdata/go_to.golden.json`:
```json
{"characterId":"npc_1","intentId":"abc","x":1,"y":64,"z":2,"world":"world","arrivalRange":3.5,"stallTimeout":30,"maxDuration":300}
```

- [ ] **Step 2: Assert each repo decodes the golden identically**

Add a test in each repo that loads the golden file and asserts the parsed fields. Go:
```go
func TestGoToGolden(t *testing.T) {
    b, _ := os.ReadFile("../../story-proto/testdata/go_to.golden.json") // adjust path
    var g storyv1.GoToIntent
    if err := protojson.Unmarshal(b, &g); err != nil { t.Fatal(err) }
    if g.CharacterId != "npc_1" || g.IntentId != "abc" || g.X != 1 {
        t.Fatalf("bad decode: %+v", &g)
    }
}
```
(Rust + Kotlin equivalents follow the round-trip tests already written; point them at the golden file. Keep paths relative or copy the golden into each repo's testdata.)

- [ ] **Step 3: Run all three golden tests**

Run each repo's test command (see "A note on TDD"). Expected: all PASS — same JSON, three languages, identical decode. **This is the pipeline proof.**

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-proto
git add testdata/go_to.golden.json && git commit -m "test: cross-repo go_to golden json"
# plus the per-repo golden tests, committed in their repos
```

---

# PHASE 2 — gRPC removal + query re-home (independent of movement)

> Phase 2 can run in parallel with Phase 3; it touches queries + transport, not movement. Do it after Phase 1 (needs the regenerated proto without the service, Task 2.4).
>
> **KEY FINDING (verified):** the WS request/response mechanism ALREADY EXISTS end-to-end. `internal/query/handler.go` does `query.request`/`query.response` correlated by `requestId`; its `HandleResponse` is wired in `routeMessage` (`server.go:531`); the StoryMC plugin already SERVES `world_state`/`character_state`/`location_by_name` over WS via `bridge/QueryHandler.kt` (wired `Story.kt:754`). The plugin has NO gRPC code at all — the gRPC `QueryWorldState`/`QueryCharacterState` push to a gRPC `EventStream` client that does not exist, so that path has no responder in practice. `query.Handler.Query()` is built but **never called yet** — it is the ready-but-unused outbound half. Re-homing = point the gRPC callers at `queryHandler.Query(...)`.

## Task 2.1: Point QueryWorldState callers at the existing WS query handler

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/server/server.go` (`/api/world_state` ~:324-333; the sim handler's `worldStateQuerier` wiring)
- Reference: `/Users/canefe/Projects/personal/story-go/internal/query/handler.go` (`Query(method, params) (map[string]any, error)`)

- [ ] **Step 1: Failing test — queryHandler answers world_state**

`internal/query/handler_test.go` (add):
```go
func TestQueryWorldStateViaWS(t *testing.T) {
    sender := &fakeSender{} // captures Broadcast; replies on the requestId
    h := NewHandler(sender)
    go func() {
        req := sender.waitForRequest() // helper: blocks until Broadcast called
        h.HandleResponse(events.BridgeMessage{Type: "query.response", Data: map[string]any{
            "requestId": req.Data["requestId"], "method": "world_state",
            "result": map[string]any{"spawnedNpcs": []any{map[string]any{"characterId": "npc_1"}}},
        }})
    }()
    res, err := h.Query("world_state", nil)
    if err != nil { t.Fatal(err) }
    if res["spawnedNpcs"] == nil { t.Fatalf("missing spawnedNpcs: %+v", res) }
}
```

- [ ] **Step 2: Run — expect FAIL/compile-error** until the fake + assertions line up. `go test ./internal/query -run TestQueryWorldStateViaWS`.

- [ ] **Step 3: Repoint the `/api/world_state` HTTP handler**

In `server.go:324-333`, replace:
```go
resp, err := s.grpcBridge.QueryWorldState(10 * time.Second)
... protojson.Marshal(resp) ...
```
with:
```go
res, err := s.queryHandler.Query("world_state", nil)
if err != nil { http.Error(w, err.Error(), http.StatusGatewayTimeout); return }
w.Header().Set("Content-Type", "application/json")
json.NewEncoder(w).Encode(res)   // res is map[string]any, plugin already returns JSON DTO shape
```
Also repoint the sim handler's `worldStateQuerier` (`internal/sim/handler.go:26-29`, used at `:483`) to call `queryHandler.Query("world_state", nil)` instead of the gRPC bridge — adapt the `map[string]any` into the `[]npcPosition` shape `queryPluginNPCPositions` expects (read that function to match keys: `characterId`, `x`, `y`, `z`).

- [ ] **Step 4: Run — expect PASS.** Then `go build ./...`. Commit: `refactor(go): /api/world_state + sim querier use WS query handler`.

## Task 2.2: Point QueryCharacterState caller at the WS query handler

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/server/server.go` (`/api/character_state` ~:335-353)

- [ ] **Step 1: Failing test** — `TestQueryCharacterStateViaWS` mirroring Task 2.1 Step 1, with params `{"characterId": "npc_1", "radius": 20.0}` and asserting the result round-trips. Run `go test ./internal/query -run TestQueryCharacterStateViaWS` → FAIL.

- [ ] **Step 2: Repoint `/api/character_state`** (`server.go:335-353`):
```go
res, err := s.queryHandler.Query("character_state", map[string]any{
    "characterId": charID, "radius": radius,
})
if err != nil { http.Error(w, err.Error(), http.StatusGatewayTimeout); return }
w.Header().Set("Content-Type", "application/json")
json.NewEncoder(w).Encode(res)
```
(`charID`/`radius` are already parsed from the request in the existing handler — keep that parsing.)

- [ ] **Step 3: Run — expect PASS.** `go build ./...`. Commit: `refactor(go): /api/character_state uses WS query handler`.

## Task 2.3: Drop gRPC from MultiSender + remove the listener

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/server/server.go` (:158-159, :520-521)
- Modify: `/Users/canefe/Projects/personal/story-go/internal/bridge/sender.go`
- Delete: `/Users/canefe/Projects/personal/story-go/internal/grpc/` (server.go, convert.go)

- [ ] **Step 1: Remove the gRPC branch from MultiSender wiring**

In `server.go:158-159` change:
```go
s.grpcBridge = storygrpc.NewServer(s.routeMessage)
s.sender = bridge.NewMultiSender(s.hub, s.grpcBridge)
```
to:
```go
s.sender = bridge.NewMultiSender(s.hub)
```
Confirm `NewMultiSender` is variadic (`sender.go`); if not, change it to take only `hub`.

- [ ] **Step 2: Remove the gRPC listener**

Delete the `grpc.NewServer()` + `RegisterStoryBridgeServer` + listener block (`server.go:~520-521` and the goroutine that serves it).

- [ ] **Step 3: Delete the gRPC package**

```bash
cd /Users/canefe/Projects/personal/story-go
git rm -r internal/grpc
```

- [ ] **Step 4: Build — confirm nothing else referenced grpc/convert**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./...`
Expected: exit 0. Fix any remaining import of `internal/grpc` or `storyv1` service types (queries now use the message types directly, not the service).

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "refactor(go): remove gRPC transport (queries re-homed to websocket)"
```

## Task 2.4: Remove the StoryBridge service def from proto + regen

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-proto/story/v1/service.proto`
- Modify: `/Users/canefe/Projects/personal/story-go` regen

- [ ] **Step 1: Delete the `service StoryBridge { ... }` block** in `service.proto`. Keep the request/response messages it referenced (still used by the WS queries).

- [ ] **Step 2: Remove `--go-grpc_out` from `make proto`** in story-go's Makefile (no service → no grpc stubs), and delete `gen/story/v1/service_grpc.pb.go`.

- [ ] **Step 3: Regenerate + build**

Run:
```bash
cd /Users/canefe/Projects/personal/story-go
# bump proto submodule to the service-removed commit first
make proto && go build ./...
```
Expected: exit 0; `service_grpc.pb.go` gone.

- [ ] **Step 4: Commit** in both repos (`story-proto`: `refactor: remove StoryBridge gRPC service`; `story-go`: `chore(proto): regen without grpc service`).

---

# PHASE 3 — `go_to` movement feature (the visible slice)

## Task 3.1: story-sim — emit `go_to` from the Lua `navigate_to` path

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/frontend_intent.rs` (Descriptor :137-149, IntentKind :65-73)

- [ ] **Step 1: Write a failing test for the go_to wire shape**

In `frontend_intent.rs` `#[cfg(test)]`:
```rust
#[test]
fn navigate_to_emits_go_to_type() {
    // dispatch a NavigateTo intent and assert the envelope type is "go_to"
    // and data carries character_id, intent_id, x/y/z, world.
    let data = super::build_navigate_payload("npc_1", "iid", 1.0, 64.0, 2.0, "world");
    assert_eq!(data["type"], "go_to");
    assert!(data["data"]["intentId"].is_string());
    assert_eq!(data["data"]["world"], "world");
}
```
(If no extractable helper exists, first extract `build_navigate_payload` from the Descriptor closure so it is unit-testable — keep the Descriptor calling it.)

- [ ] **Step 2: Run — expect FAIL** (`cargo test --bin story-sim navigate_to_emits_go_to_type`): currently emits `frontend.intent`/`navigate_to`.

- [ ] **Step 3: Change the Descriptor to emit `go_to`**

In the `NavigateTo` Descriptor (`:137-149`): set `envelope_type: "go_to"`, drop the `primitive: "navigate_to"` wrapping (the type IS the primitive now), keep `expects_outcome: true`, and add `world` to the payload (thread the character's current world from the ECS query into the intent — read it where the Descriptor builds payload). Keep `intent_id` minting.

- [ ] **Step 4: Run — expect PASS.** Commit: `feat(sim): navigate_to lua path emits go_to`.

## Task 3.2: story-sim — ECS movement emits `go_to` with intentId + PendingIntents

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/behavior/movement.rs` (:14-124)
- Reference: `src/components/pending_intents.rs` (`push`, `last_for_primitive`), `src/plugins/frontend_intent.rs` (`mint_intent_id`)

- [ ] **Step 1: Failing test — ECS move publishes a tracked go_to**

```rust
#[test]
fn ecs_move_publishes_tracked_go_to() {
    // build payload as movement.rs will: must be type "go_to",
    // have a non-empty intentId, and a PendingIntents entry pushed.
    let (payload, minted_id) = super::build_ecs_go_to("npc_1", 1.0, 64.0, 2.0, "world");
    assert_eq!(payload["type"], "go_to");
    assert!(!minted_id.is_empty());
    assert_eq!(payload["data"]["intentId"], minted_id);
}
```

- [ ] **Step 2: Run — expect FAIL** (`cargo test --bin story-sim ecs_move_publishes_tracked_go_to`).

- [ ] **Step 3: Rewrite `publish_movement_intents_system`**

Replace the raw `format!` `npc.move` (`:116-122`) with: mint an intent id (`mint_intent_id()`), build a `GoToIntent` (the generated prost type) serialized via `serde_json` into the envelope `{"type":"go_to","data":{...},"source":"story-sim"}`, AND push a `PendingIntents` entry for the moving entity (`pending.push(intent_id, "go_to", now)`) so outcomes correlate. Add a `PendingIntents` query/component access to the system signature (mirror `frontend_intent.rs:275-282`). Extract `build_ecs_go_to` as the testable helper.

- [ ] **Step 4: Run — expect PASS.** Commit: `feat(sim): ECS movement emits tracked go_to (was untracked npc.move)`.

- [ ] **Step 5: Remove the old `npc.move` constant/string usages** — grep `npc.move` across story-sim, confirm zero remain. Run `cargo build --bin story-sim`. Commit if changed.

## Task 3.3: Lua — rename navigate_to lookup to go_to

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/packs/BaseGame/lua/defs/behaviors/head_to_known_location.lua:148`

- [ ] **Step 1: Failing test** — add to the Lua harness (`need_tasks_test.lua` style) a test asserting `head_to_known_location` detects rejection via `char.intents.lastFor("go_to")`. It fails while the file still queries `"navigate_to"`.

- [ ] **Step 2: Run the Lua test — expect FAIL.** Run: `cargo test --bin story-sim lua_head_to_known` (or the pack runner used for `need_tasks_test.lua`).

- [ ] **Step 3: Change line 148** `char.intents.lastFor("navigate_to")` → `char.intents.lastFor("go_to")`. Also fix the stale comment at line 131.

- [ ] **Step 4: Run — expect PASS.** Commit: `fix(lua): head_to_known_location reads go_to outcome (was navigate_to)`.

## Task 3.4: story-go — route go_to as a sim event

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-go/internal/sim/events.go` (`IsSimEvent`)
- Modify: `/Users/canefe/Projects/personal/story-go/pkg/events/events.go` (constant)

- [ ] **Step 1: Failing test**

```go
func TestGoToIsSimEvent(t *testing.T) {
    if !IsSimEvent("go_to") { t.Fatal("go_to should be a sim event") }
}
```
Run: `go test ./internal/sim -run TestGoToIsSimEvent` → FAIL.

- [ ] **Step 2: Add the constant** `GoTo = "go_to"` to `pkg/events/events.go`, and add `events.GoTo` to the `IsSimEvent` set in `internal/sim/events.go`, and a `case events.GoTo: h.broadcastToFrontends(msg)` in `handler.go` (mirror `frontend.intent` at :128-129).

- [ ] **Step 3: Run — expect PASS.** Then `go build ./...`. Commit: `feat(go): route go_to to frontends as sim event`.

## Task 3.5: StoryMC — go_to handler reusing NavWatchState + outcomes

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` (:208-238 dispatch)
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` (handler + :313-332 delete + :646-663 delete)
- Modify: `bridge/DomainEvents.kt` — `GoToIntent` adapter DTO (or use generated type via a `@Serializable` mirror)

- [ ] **Step 1: Failing test — go_to handler starts a watcher and emits an outcome**

`src/test/kotlin/com/canefe/story/bridge/GoToExecTest.kt` (MockBukkit idiom):
```kotlin
@Test fun goToArrivalEmitsCompleted() {
    // resolve a stub NPC at the target, run executeGoTo,
    // simulate arrival, assert an IntentCompletedEvent with the same intentId fired.
}
```
(Follow the MockBukkit + eventBus capture pattern from existing executor tests.)

- [ ] **Step 2: Run — expect FAIL** (no `executeGoTo` yet). `./gradlew test --tests "com.canefe.story.bridge.GoToExecTest"`.

- [ ] **Step 3: Add dispatch + handler**

In `WebSocketTransport.deserializeEvent` add `"go_to" -> GoToExecIntent.fromJson(...)` (a `@Serializable` DTO mirroring the proto JSON: `characterId, intentId, x, y, z, world, arrivalRange, stallTimeout, maxDuration`). Wire it in `Story.kt` to `IntentExecutor.executeGoTo`.

Implement `executeGoTo` in `IntentExecutor`: resolve NPC, build `Location` honoring `intent.world` (fallback to NPC's world if blank), call `npc.navigateTo(target)`, then `startNavWatcher(characterId, target, onArrive = { completeIntent(characterId, intent.intentId, "go_to") }, onFail = { reason -> rejectIntent(characterId, intent.intentId, "go_to", reason) })`. Apply `arrivalRange`/`stallTimeout`/`maxDuration` from the intent (with the existing constants as defaults when 0).

- [ ] **Step 4: Run — expect PASS.** 

- [ ] **Step 5: Delete the old paths** — remove `executeMoveIntent` (:313-332), the `npc.move` dispatch case, the `NPCMoveIntent` DTO, and the `navigate_to` when-branch (:646-663). Run full `./gradlew compileKotlin test`. Fix the excluded-test list in `build.gradle.kts:180-184` if it referenced these.

- [ ] **Step 6: Commit** `feat(mc): go_to handler with outcome round-trip; remove npc.move + navigate_to`.

## Task 3.6: StoryMC — supersede-reject on watcher contention

**Files:**
- Modify: `bridge/IntentExecutor.kt` (`startNavWatcher` :155-226, `navWatchers` :148)
- Test: `src/test/kotlin/com/canefe/story/bridge/GoToSupersedeTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
@Test fun secondGoToSupersedesFirstWithRejected() {
    // issue go_to(intentId=A) then go_to(intentId=B) for same NPC.
    // assert: an IntentRejectedEvent(intentId=A, reason=SUPERSEDED) fired,
    // and the A watcher was cancelled (only B's watcher live).
}
```

- [ ] **Step 2: Run — expect FAIL** (today the old watcher is silently cancelled, no reject).

- [ ] **Step 3: Implement supersede**

`startNavWatcher` must track the in-flight `(intentId, onFail)` per characterId. Add `private val activeIntent = ConcurrentHashMap<String, Pair<String, (RejectionReason) -> Unit>>()`. When starting a new watcher for a characterId that already has an entry, invoke the old entry's `onFail(RejectionReason.SUPERSEDED)` BEFORE cancelling the old Bukkit task, then register the new one.

**Enum decision (settled):** Phase 3 does NOT swap the whole transport to generated proto enums (that is the follow-up). For this slice, add `SUPERSEDED` and `TIMEOUT` to the EXISTING hand-written `RejectionReason` (`DomainEvents.kt:411-429`) so the Kotlin code compiles against one enum. The proto `RejectReason` (Task 1.1) is the superset SoT; full replacement of `RejectionReason` with the generated type is deferred. This keeps Phase 3 a behavior change, not a type-system migration.

- [ ] **Step 4: Run — expect PASS.** Commit: `feat(mc): supersede prior go_to with SUPERSEDED reject (no leaked pending intent)`.

## Task 3.7: Wire-casing alignment check (sim emits ↔ Kotlin/proto expects)

**Files:**
- Verify only; fix whichever side diverges.

- [ ] **Step 1:** Confirm the sim's `serde_json` payload keys match proto-canonical-JSON camelCase (`characterId`, `intentId`, `arrivalRange`). prost+serde defaults to the Rust field name (`character_id`) unless `serde(rename_all="camelCase")` is configured. In `build.rs` add `cfg.type_attribute(".", "#[serde(rename_all = \"camelCase\")]");` so sim emits camelCase matching the proto-JSON Go/Kotlin produce.

- [ ] **Step 2:** Re-run the cross-repo golden test (Task 1.5) — all three must still pass after the rename_all. Commit if `build.rs` changed: `fix(sim): camelCase proto-json to match cross-repo wire`.

---

## Verification (in-game, after Phase 3)

- [ ] Drive an NPC to a known location (Lua `navigateTo` path) AND via the ECS action-target path; confirm both now travel as `go_to` and both report an outcome (arrival → `intent.completed`; blocked → `intent.rejected`).
- [ ] Issue a second `go_to` to a walking NPC; confirm the first gets `intent.rejected(SUPERSEDED)` and NO `PendingIntents` "lost mint" eviction log appears on the sim side.
- [ ] Cross-world target resolves correctly (no spurious UNREACHABLE from the old first-world assumption).
- [ ] `grep -r "npc.move" story-sim` and `grep -rn "navigate_to" story-sim StoryMC` return only historical/comment hits — no live emit/consume.

---

## Self-review notes (author)

- Phase 1 has no behavior change — pure tooling + a frozen vocabulary; safe to land independently.
- Phase 2 is independent of movement (queries + transport); can be parallelized.
- Phase 3 is the TDD'd feature; every task is red→green→commit.
- Risk carried forward: prost+serde JSON casing (Task 3.7) is the single most likely wire-mismatch; the cross-repo golden test (1.5) is the guard.
- The generated Kotlin proto type vs. the existing `@Serializable` DTO: this plan uses a thin `@Serializable` mirror DTO for inbound dispatch (kotlinx path) rather than swapping the whole transport to protobuf-java decoding in one step — keeps the WebSocket JSON decode path intact. Full DTO replacement is a follow-up.
