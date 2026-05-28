# Binary protobuf SimEvent wire — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JSON with binary protobuf on both SimEvent wire hops (sim→go NATS, go→plugin WebSocket). Drop all JSON shims and fallback paths.

**Architecture:** Three parallel repo migrations (story-sim, story-go, StoryMC) after a precondition story-proto field addition. Each side emits/consumes raw protobuf bytes — `prost::Message::encode_to_vec` on Rust, `proto.Marshal`/`Unmarshal` on Go, `SimEvent.parseFrom` on Kotlin. WebSocket frame type (text vs binary) is the discriminator between legacy `BridgeMessage`-shaped traffic and SimEvent traffic. No magic byte, no header.

**Tech Stack:** protobuf (story-proto SoT), prost 0.13 (sim), google.golang.org/protobuf (go), com.google.protobuf:protobuf-java 3.25.5 (plugin). async-nats 0.39, gorilla/websocket v1.5.3, java.net.http.WebSocket.

**Spec:** `docs/superpowers/specs/2026-05-28-binary-proto-wire-design.md`

---

## File Structure

### story-proto (Phase 0)
- Modify: `story/v1/events.proto:216-237` — add three fields to `NpcState` and `EntitySpawned`.

### story-sim (Phase 1 Agent A — Rust)
- Modify: `proto/` submodule pointer → Phase-0 commit SHA. Run `cargo build` to regen prost types.
- Modify: `src/plugins/event_bridge.rs` (~30 LOC) — change `BridgePubSubOut.payload`, `BridgeStreamOut.payload`, `BridgeCommand::Publish.payload`, `BridgeCommand::XAdd.payload` from `String` to `Vec<u8>`.
- Modify: `src/plugins/nats_bridge.rs:111-125` — payload moves are already `.into()` so just need to compile.
- Modify: `src/plugins/redis_bridge.rs:69-74` — same `payload.into()` move; needs to compile against bytes (Redis publish accepts `&[u8]`).
- Modify: 11 producers that emit `BridgePubSubOut`/`BridgeStreamOut`:
  - `src/systems/health_system.rs:165,285` — drop `serde_json::to_string(&ev)`, replace with `ev.encode_to_vec()`.
  - `src/systems/combat_system.rs:92-98` — `attack_resolved_to_payload` helper changes signature `→ Vec<u8>`.
  - `src/plugins/sim_heartbeat.rs:30-35` — same swap.
  - `src/plugins/sim_init.rs:31-37` — same swap.
  - `src/plugins/affordance_registry_publish.rs:39-49` — same swap.
  - `src/plugins/entity_state_broadcast.rs:148` — same swap (NpcState emit).
  - `src/plugins/frontend_intent.rs:388` — same swap.
  - `src/plugins/behavior/movement.rs:174` — same swap (GoTo emit).
  - `src/plugins/lua_world_api.rs:721,3271` — same swap.
  - `src/plugins/stream_handlers/nearby_query.rs:60` — same swap.
  - `src/plugins/stream_handlers/location_template_get.rs:50` — same swap.
  - `src/systems/recognition_reinforce_system.rs:78,98` — same swap.
- Modify: Test helpers parsing `BridgePubSubOut.payload`:
  - `src/systems/health_system.rs:443,870` — change `serde_json::from_str::<SimEvent>(&m.payload)` → `SimEvent::decode(m.payload.as_slice())`.
  - `src/systems/combat_system.rs:552` — same change.

### story-go (Phase 1 Agent B — Go)
- Modify: `proto/` submodule pointer → Phase-0 SHA. Run `make proto`.
- Delete: `internal/bridge/sim_event_decode.go` (151 lines).
- Delete: `internal/bridge/sim_event_decode_test.go` — pure tests of the shim being removed.
- Modify: `internal/bridge/nats.go:58` — replace `decodeSimEvent(msg.Data)` with `proto.Unmarshal(msg.Data, &sim)`.
- Modify: `internal/bridge/sender.go:6` — add `BroadcastBinary(data []byte)` to `Sender` interface; implement on `MultiSender`.
- Modify: `internal/bridge/ws.go:49` — add `Hub.BroadcastBinary(data []byte)` writing `websocket.BinaryMessage` (mirroring `Broadcast`).
- Modify: `internal/sim/handler.go:165-201` — replace `broadcastProto(msgType, m, label)` with `broadcastSimEvent(simEvent)` that does `proto.Marshal` + `sender.BroadcastBinary`. Update three call sites at lines 165, 167, 171 to pass `e` (the outer SimEvent) instead of the inner variant.

### StoryMC (Phase 1 Agent C — Kotlin)
- Modify: `src/main/proto/` submodule pointer → Phase-0 SHA. Run `./gradlew generateProto`.
- Modify: `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt:186-240` — delete `tryParseSimEvent` (proto-JSON parser) entirely; add `onBinary` listener override with ByteBuffer accumulator; route to `adaptSimEvent` via new dispatch path. Listener accumulator field at line 330-371.
- Modify: `src/main/kotlin/com/canefe/story/bridge/SimEventAdapter.kt:59,74,78` — wire the three new proto fields (`s.world`, `e.world`, `e.mobTemplate`) into the existing `NpcStateIntent` and `NpcSpawnIntent` constructions.
- Modify: imports in `WebSocketTransport.kt` — drop `com.google.protobuf.util.JsonFormat` from inbound path (the `sendProto` outbound path still uses it; keep import).

---

## Phase 0: Add proto fields (main thread, prerequisite)

### Task 0.1: Add NpcState.world and EntitySpawned.world/mob_template

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-proto/story/v1/events.proto:216-237`

- [ ] **Step 1: Edit `events.proto` to add the three fields**

Apply the edits using Edit tool, not Write. Target the two messages.

Change `NpcState` from (lines 216-227):

```protobuf
message NpcState {
  string character_id = 1;
  string name = 2;
  float x = 3;
  float y = 4;
  float z = 5;
  float health = 6;
  string action_id = 7;
  string behavior_id = 8;
  optional string action_label = 9;
  NpcStateStats stats = 10;
}
```

to:

```protobuf
message NpcState {
  string character_id = 1;
  string name = 2;
  float x = 3;
  float y = 4;
  float z = 5;
  float health = 6;
  string action_id = 7;
  string behavior_id = 8;
  optional string action_label = 9;
  NpcStateStats stats = 10;
  string world = 11;
}
```

Change `EntitySpawned` from (lines 230-237):

```protobuf
message EntitySpawned {
  string character_id = 1;
  string name = 2;
  string race = 3;
  float x = 4;
  float y = 5;
  float z = 6;
}
```

to:

```protobuf
message EntitySpawned {
  string character_id = 1;
  string name = 2;
  string race = 3;
  float x = 4;
  float y = 5;
  float z = 6;
  string world = 7;
  string mob_template = 8;
}
```

- [ ] **Step 2: Verify proto compiles**

Run: `cd /Users/canefe/Projects/personal/story-proto && protoc --proto_path=. --descriptor_set_out=/dev/null story/v1/events.proto`

Expected: no output (success). If `protoc` is unavailable, fall back to manual inspection — the additions follow trivial proto3 string-field grammar and a build verification happens transitively in each consumer's Phase 1 task.

- [ ] **Step 3: Commit and push to origin/main**

```bash
cd /Users/canefe/Projects/personal/story-proto
jj describe -m "feat(proto): add NpcState.world and EntitySpawned.world/mob_template"
jj git push -b main
git log -1 --pretty=format:'%H' origin/main
```

Capture the new commit SHA — call it `$PROTO_SHA`. All three Phase-1 agents will vendor this SHA.

- [ ] **Step 4: Verify push succeeded**

Run: `cd /Users/canefe/Projects/personal/story-proto && git log -1 origin/main --oneline`

Expected: shows `feat(proto): add NpcState.world and EntitySpawned.world/mob_template` as the top commit.

---

## Phase 1: Parallel migrations

Phase 1 dispatches three general-purpose subagents in parallel (single message, three Agent tool calls). Each agent works in its own repo, vendors `$PROTO_SHA`, makes a single commit prefixed `feat(wire):`, runs the in-repo test suite, and reports back the commit SHA + test results.

The handoff template for each agent appears below as Task 1.A / 1.B / 1.C. The main thread does NOT execute these tasks directly — it dispatches each as an agent. The task body IS the agent prompt.

### Task 1.A: story-sim — emit SimEvent as binary on NATS

Dispatch as: `Agent(subagent_type=general-purpose, description="story-sim binary wire", prompt=<below>)`

```
You are migrating story-sim (Bevy/Rust) to emit SimEvent as binary protobuf bytes on NATS instead of JSON.

Repo: /Users/canefe/Projects/personal/story-sim
Spec: /Users/canefe/Projects/personal/Story/docs/superpowers/specs/2026-05-28-binary-proto-wire-design.md
Plan: /Users/canefe/Projects/personal/Story/docs/superpowers/plans/2026-05-28-binary-proto-wire.md (this file; you only need to execute Task 1.A)

Prerequisite: story-proto has commit $PROTO_SHA (passed in) on origin/main. Vendor it before touching anything else.

Constraints:
- ONE jj commit, message starts with "feat(wire):". Do NOT push.
- No Co-Authored-By line.
- Do not "fix" pre-existing flaky tests (e.g. plugins::behavior::planner_systems::system_tests). List them in your report, move on.
- The repo has uncommitted WIP from a sibling work-stream — verify `jj st` shows an empty working copy at the start (it should, per the handoff). If it does not, STOP and ask the main thread.
- Use the Edit tool, never replace_all for renames.

Steps:

1. Bump proto submodule:
   cd /Users/canefe/Projects/personal/story-sim/proto
   git fetch origin && git checkout $PROTO_SHA
   cd ..
   cargo build --quiet 2>&1 | tail -20
   This regenerates prost types; the new NpcState.world / EntitySpawned.world / mob_template fields should be available.

2. Change BridgePubSubOut, BridgeStreamOut, BridgeCommand::Publish, BridgeCommand::XAdd payload type from String to Vec<u8>. File: src/plugins/event_bridge.rs.

3. Update producers. There are ~13 emit sites; the pattern is uniform:
   - Before: `payload: serde_json::to_string(&ev).expect("X serializes")`
   - After:  `payload: ev.encode_to_vec()`
   `encode_to_vec` is on `prost::Message`. Add `use prost::Message;` to producer files that don't import it.

   Sites (these are byte-for-byte mechanical):
   - src/systems/health_system.rs:165, ~285  (uses already-built `m` from helper; just swap the `.payload` value)
   - src/systems/combat_system.rs: the `attack_resolved_to_payload(...)` helper at ~line 92 returns String → return Vec<u8>; caller already uses the helper. The helper builds the SimEvent inside itself.
   - src/plugins/sim_heartbeat.rs:30-35
   - src/plugins/sim_init.rs:31-37
   - src/plugins/affordance_registry_publish.rs:39-49
   - src/plugins/entity_state_broadcast.rs:148 (emits NpcState — populate the new `world` field from whatever world context the entity has; if there is no world context plumbed today, set `world: String::new()` and add a `// TODO: plumb world when multi-world lands` comment — multi-world routing is out of scope per the spec)
   - src/plugins/frontend_intent.rs:388
   - src/plugins/behavior/movement.rs:174 (GoTo emit)
   - src/plugins/lua_world_api.rs:721, ~3271 (the line at 721 emits EntitySpawned — populate new `world` and `mob_template` fields the same way; if those values aren't available yet, set them to `String::new()` and `"Character".to_string()` respectively to match existing plugin-side defaults)
   - src/plugins/stream_handlers/nearby_query.rs:60
   - src/plugins/stream_handlers/location_template_get.rs:50
   - src/systems/recognition_reinforce_system.rs:78, 98

4. Update test helpers that decode payload:
   - src/systems/health_system.rs:443 — change `serde_json::from_str::<SimEvent>(&m.payload).expect("payload not SimEvent")` → `SimEvent::decode(m.payload.as_slice()).expect("payload not SimEvent")`. Add `use prost::Message;` if missing.
   - src/systems/health_system.rs:~870 — same change.
   - src/systems/combat_system.rs:~552 — same change.
   Any other `serde_json::from_str::<SimEvent>` callsites you find — same change.

5. Build:
   cargo build --quiet 2>&1 | tail -30
   Expected: clean build. Any compile error means a producer or test helper was missed in steps 3-4.

6. Test:
   cargo test --bin story_sim --quiet 2>&1 | tail -50
   (Per project memory: story-sim is a binary crate, tests require --bin.)
   Expected: pre-existing flaky `plugins::behavior::planner_systems::system_tests` may fail (9 cases). All other tests should pass. The new wire-relevant tests in health_system / combat_system should pass.

7. Sanity-check that no JSON SimEvent emit/decode remains:
   grep -rn "serde_json::to_string\|serde_json::from_str::<SimEvent\|serde_json::from_str::<crate::wire" src/ | grep -v "// "
   Expected: empty output (or only matches in non-SimEvent emit paths, which you should not have touched).

8. Commit:
   jj describe -m "feat(wire): emit SimEvent as binary protobuf on NATS

   - BridgePubSubOut/BridgeStreamOut payload: String → Vec<u8>
   - BridgeCommand::Publish/XAdd payload: String → Vec<u8>
   - All 13 SimEvent producers emit via prost::Message::encode_to_vec
   - Test helpers decode via SimEvent::decode instead of serde_json
   - Populate new NpcState.world and EntitySpawned.world/mob_template fields
     (defaults for now; multi-world routing is out of scope)"
   jj st  # confirm clean
   jj log -r @- --no-graph  # capture commit hash

Report back with:
- The commit hash
- Pinned $PROTO_SHA
- Test results: pass count, fail count, names of any failures (separate pre-existing from new)
- Any decision you had to guess (e.g. how `world` is plumbed in entity_state_broadcast)
- Files modified
```

### Task 1.B: story-go — consume binary NATS, broadcast binary WebSocket

Dispatch as: `Agent(subagent_type=general-purpose, description="story-go binary wire", prompt=<below>)`

```
You are migrating story-go (Go) to decode SimEvent NATS payloads as binary protobuf (deleting the JSON shim) and to re-broadcast SimEvent traffic to plugin clients as binary WebSocket frames (instead of JSON-wrapped BridgeMessage envelopes).

Repo: /Users/canefe/Projects/personal/story-go
Spec: /Users/canefe/Projects/personal/Story/docs/superpowers/specs/2026-05-28-binary-proto-wire-design.md
Plan: /Users/canefe/Projects/personal/Story/docs/superpowers/plans/2026-05-28-binary-proto-wire.md (this file; only execute Task 1.B)

Prerequisite: story-proto has commit $PROTO_SHA (passed in) on origin/main.

Constraints:
- ONE jj commit, message starts with "feat(wire):". Do NOT push.
- No Co-Authored-By line.
- Do not fix pre-existing flaky tests (e.g. TestDamageEventForwarding).
- Use Edit tool, never replace_all for renames.

Steps:

1. Bump proto submodule:
   cd /Users/canefe/Projects/personal/story-go/proto
   git fetch origin && git checkout $PROTO_SHA
   cd ..
   make proto 2>&1 | tail -20
   This regenerates gen/story/v1/*.pb.go with the new fields.

2. Delete the JSON SimEvent shim:
   rm internal/bridge/sim_event_decode.go
   rm internal/bridge/sim_event_decode_test.go

3. Update internal/bridge/nats.go:58. The current line is:
       ev, err := decodeSimEvent(msg.Data)
   Replace with:
       var sim storyv1.SimEvent
       err := proto.Unmarshal(msg.Data, &sim)
   And replace subsequent `ev` references with `&sim` for the same scope.

   Add imports if not already present:
       "google.golang.org/protobuf/proto"
       storyv1 "github.com/canefe/story-go/gen/story/v1"

4. Extend the Sender interface. File: internal/bridge/sender.go.
   Current:
       type Sender interface {
           Broadcast(msg events.BridgeMessage)
       }
   New:
       type Sender interface {
           Broadcast(msg events.BridgeMessage)
           BroadcastBinary(data []byte)
       }
   And implement on MultiSender:
       func (ms *MultiSender) BroadcastBinary(data []byte) {
           for _, s := range ms.senders {
               s.BroadcastBinary(data)
           }
       }

5. Implement Hub.BroadcastBinary. File: internal/bridge/ws.go after the existing Broadcast() at line 49-65. Pattern is identical except for the frame type. Add:

   func (h *Hub) BroadcastBinary(data []byte) {
       h.mu.RLock()
       defer h.mu.RUnlock()
       for _, client := range h.clients {
           select {
           case client.send <- data:
           default:
           }
       }
   }

   IMPORTANT: the existing `client.send` channel feeds a writer loop that calls `WriteMessage(websocket.TextMessage, msg)` at line ~195 of ws.go. We need binary frames for SimEvent traffic. Two approaches:
     (a) Add a second per-client channel `sendBinary chan []byte` and a parallel select branch in the writer loop that uses `websocket.BinaryMessage`.
     (b) Tag every message in the existing channel as text or binary (e.g. `type outboundMessage struct { data []byte; binary bool }`).

   Choose (a) — minimal blast radius, mirrors the existing pattern, no struct rewrite.

   Find the writer loop (search for `WriteMessage(websocket.TextMessage`). Add a `sendBinary chan []byte` field to Client (line ~21). Initialize it where `send` is initialized (search for `make(chan []byte` in the Hub/connection-setup path). In the writer loop, add a parallel `case data := <-client.sendBinary:` that calls `client.conn.WriteMessage(websocket.BinaryMessage, data)`. Point `BroadcastBinary` at `client.sendBinary` instead of `client.send`.

6. Replace broadcastProto in internal/sim/handler.go:185-201. Current implementation re-marshals proto → BridgeMessage envelope → JSON. New implementation marshals the outer SimEvent → binary bytes → BroadcastBinary.

   New signature:
       func (h *Handler) broadcastSimEvent(ev *storyv1.SimEvent, label string) {
           data, err := proto.Marshal(ev)
           if err != nil {
               log.Warnf("[Sim] %s: proto marshal failed: %v", label, err)
               return
           }
           if !h.state.IsActive() {
               log.Warnf("[Sim] broadcastSimEvent: dropping %s — sim not active", label)
               return
           }
           h.sender.BroadcastBinary(data)
       }

   (Mirror whatever active-check broadcastToFrontends does today — read lines ~134-145 to confirm.)

7. Update the three call sites in handler.go:160-182 to pass the OUTER SimEvent, not the inner variant. The Handle switch already has the outer `e *storyv1.SimEvent` in scope (it's the parameter being switched on). So:

   case *storyv1.SimEvent_GoTo:
       h.broadcastSimEvent(e, "go_to")
   case *storyv1.SimEvent_CombatAttackResolved:
       h.broadcastSimEvent(e, "combat.attack_resolved")
   case *storyv1.SimEvent_NpcItemTransfer:
       h.broadcastSimEvent(e, "npc.item_transfer")

   Wait — `e` in those cases is the typed inner pointer (e.g. *SimEvent_GoTo). You need the outer. Check the Handle signature: if it's `Handle(sim *storyv1.SimEvent)`, the outer is `sim` and the switch is on `sim.GetEvent()` — pass `sim` to broadcastSimEvent. Verify by reading the actual switch statement.

   Also: handleNpcState (line 161), handleEntitySpawned (line 163), and handleEntityDied (line 169) take typed INNER messages. Two of these (NpcState, EntitySpawned) ALSO need to be broadcast to plugin clients (per the spec's list of plugin-visible variants). Check the current behavior — does handleNpcState OR handleEntitySpawned internally call broadcastProto for these? If yes, swap those internal calls to broadcastSimEvent(sim, ...). If no, the previous JSON path may have been dropping these to plugins entirely (unlikely given the spec's list), so re-read the handler to confirm and add broadcastSimEvent calls where appropriate.

8. Build:
   go build ./... 2>&1 | tail -20
   Expected: clean.

9. Test:
   go test ./... 2>&1 | tail -50
   Expected: TestDamageEventForwarding may fail (pre-existing flaky). Everything else passes. The deleted sim_event_decode_test.go was the only test covering the deleted shim.

10. Sanity-check: no remaining JSON SimEvent decode/encode in inbound/outbound sim path:
    grep -rn "decodeSimEvent\|sim_event_decode" internal/
    Expected: empty.

11. Commit:
    jj describe -m "feat(wire): decode sim NATS as binary proto; broadcast sim events as binary WS

    - Delete decodeSimEvent shim and its tests
    - internal/bridge/nats.go: proto.Unmarshal directly on msg.Data
    - internal/bridge/sender.go: add BroadcastBinary([]byte) to Sender interface
    - internal/bridge/ws.go: add Hub.BroadcastBinary using a per-client
      sendBinary channel and websocket.BinaryMessage frame
    - internal/sim/handler.go: replace broadcastProto with broadcastSimEvent
      that marshals the outer SimEvent and pushes via BroadcastBinary
    - Bump proto submodule to $PROTO_SHA for NpcState.world and
      EntitySpawned.world/mob_template fields"
    jj st  # confirm clean
    jj log -r @- --no-graph

Report back with:
- The commit hash
- Pinned $PROTO_SHA
- Test results: pass/fail count, names of new failures (separate from pre-existing flaky)
- Whether handleNpcState / handleEntitySpawned needed broadcastSimEvent calls added (was the JSON path previously broadcasting these?)
- Files modified
```

### Task 1.C: StoryMC — decode binary SimEvent from WebSocket

Dispatch as: `Agent(subagent_type=general-purpose, description="StoryMC binary wire", prompt=<below>)`

```
You are migrating the Story Minecraft plugin (Kotlin/Paper) to consume SimEvent traffic as binary protobuf frames on WebSocket. Legacy text-frame BridgeMessage traffic stays unchanged.

Repo: /Users/canefe/Projects/personal/Story
Spec: /Users/canefe/Projects/personal/Story/docs/superpowers/specs/2026-05-28-binary-proto-wire-design.md
Plan: /Users/canefe/Projects/personal/Story/docs/superpowers/plans/2026-05-28-binary-proto-wire.md (only execute Task 1.C)

Prerequisite: story-proto has commit $PROTO_SHA (passed in) on origin/main.

Constraints:
- ONE jj commit, message starts with "feat(wire):". Do NOT push.
- No Co-Authored-By.
- Do not try to fix the 42 pre-existing NoClassDefFoundError full-boot tests (per project_test_classpath_noclassdef memory).
- Use Edit tool, never replace_all for renames.
- Build before commit: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`.

Steps:

1. Bump proto submodule:
   cd /Users/canefe/Projects/personal/Story/src/main/proto
   git fetch origin && git checkout $PROTO_SHA
   cd ../../..
   export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
   ./gradlew generateProto 2>&1 | tail -20
   This regenerates com.canefe.storyproto.v1.SimEvent / NpcState / EntitySpawned with the new fields.

2. Modify WebSocketTransport.kt:

   2a. Delete `tryParseSimEvent` entirely (lines 221-240). This was the proto-JSON parser used by the text-frame inbound path; binary frames bypass it.

   2b. Modify `handleInboundMessage(payload: String)` (lines 186-219) — DELETE the `tryParseSimEvent(payload)?.let { ... }` block at lines 187-201. The function now ONLY handles legacy BridgeMessage envelopes. Trim the comments accordingly.

   2c. In the StoryWebSocketListener inner class (lines 330-371), add a parallel `ByteArrayOutputStream` accumulator for binary frames and an `onBinary` override:

   ```kotlin
   private val binaryBuffer = java.io.ByteArrayOutputStream()

   override fun onBinary(
       webSocket: WebSocket,
       data: java.nio.ByteBuffer,
       last: Boolean,
   ): CompletionStage<*> {
       val chunk = ByteArray(data.remaining())
       data.get(chunk)
       binaryBuffer.write(chunk)
       if (last) {
           val bytes = binaryBuffer.toByteArray()
           binaryBuffer.reset()
           handleInboundBinary(bytes)
       }
       webSocket.request(1)
       return CompletableFuture.completedFuture(null)
   }
   ```

   2d. Add a new `handleInboundBinary(bytes: ByteArray)` private method on the outer class:

   ```kotlin
   private fun handleInboundBinary(bytes: ByteArray) {
       val simEvent = try {
           com.canefe.storyproto.v1.SimEvent.parseFrom(bytes)
       } catch (e: Exception) {
           logger.warning("Failed to parse binary SimEvent: ${e.message}")
           return
       }
       if (simEvent.eventCase == com.canefe.storyproto.v1.SimEvent.EventCase.EVENT_NOT_SET) {
           logger.warning("Binary SimEvent had no event variant set")
           return
       }
       val event = adaptSimEvent(simEvent) ?: return
       Bukkit.getScheduler().runTask(plugin, Runnable { inboundHandler?.invoke(event) })
   }
   ```

   2e. The `sendProto` outbound path (lines 134-146) and the `PROTO_JSON_PRINTER` companion stay unchanged — they handle plugin→go outbound proto traffic which is out of scope. Leave them alone.

3. Modify SimEventAdapter.kt:

   3a. Line 59: change `world = "",` to `world = s.world,`. (NpcStateIntent now reads the proto field directly.) Update the surrounding comment that explained the default — the field exists now.

   3b. Lines 73-74: change
       ```kotlin
       // proto EntitySpawned has no mob_template — default per Kotlin class.
       mobTemplate = "Character",
       ```
   to
       ```kotlin
       mobTemplate = e.mobTemplate.ifEmpty { "Character" },
       ```

   3c. Line 78: change `world = "",` to `world = e.world,`. Update the comment block at lines 25-29 to reflect that the proto↔Kotlin gaps are now closed (delete those two bullet points).

4. Build:
   export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
   ./gradlew compileKotlin 2>&1 | tail -20
   Expected: clean compile.

5. Test:
   ./gradlew test 2>&1 | tail -50
   Expected: same baseline as before — ~42 boot tests fail with NoClassDefFoundError (pre-existing env issue per project_test_classpath_noclassdef memory). The two wire-relevant tests added by the prior session (CombatRoundTripWireTest, SimAuthoritativeDamageListenerTest) should pass. NpcSpawnIntentRefusalTest should pass.

   IMPORTANT: CombatRoundTripWireTest likely uses proto-JSON inputs (it predates this PR). If it fails post-migration, that's NEW failure — investigate. The test asserts the round-trip path; switching the wire shape may invalidate its fixture format. Check: does the test send a JSON string into WebSocketTransport.handleInboundMessage, or does it construct a SimEvent and feed bytes? If the former, EITHER update the test to feed binary bytes via the new onBinary path, OR leave the test alone if it covers a unit boundary upstream of the WebSocket frame split. Use judgment; report what you did.

6. Sanity:
   grep -rn "tryParseSimEvent\|JsonFormat.parser" src/main/kotlin/com/canefe/story/bridge/
   Expected: zero matches for tryParseSimEvent. JsonFormat.parser may still appear in sendProto callers — that's fine, it's outbound.

7. Commit:
   jj describe -m "feat(wire): decode SimEvent from binary WebSocket frames

   - WebSocketTransport: add onBinary listener with ByteBuffer accumulator
     and SimEvent.parseFrom dispatch through adaptSimEvent
   - Delete tryParseSimEvent and its proto-JSON parsing branch in
     handleInboundMessage; legacy BridgeMessage path on onText unchanged
   - SimEventAdapter: wire new proto fields NpcState.world,
     EntitySpawned.world, EntitySpawned.mob_template — drop the defaults
   - Bump proto submodule to $PROTO_SHA"
   jj st
   jj log -r @- --no-graph

Report back with:
- The commit hash
- Pinned $PROTO_SHA
- Test results: list of failing tests, separating pre-existing NoClassDefFoundError boot tests from any new failures
- Whether CombatRoundTripWireTest needed adjustment, and what you did
- Files modified
```

### Task 1.D: Verify all three agents pinned the same SHA

After all three agents return:

- [ ] **Step 1: Compare pinned SHAs**

Each agent's report includes the `$PROTO_SHA` it pinned. Confirm they match the SHA from Phase 0 Task 0.1 step 3.

- [ ] **Step 2: Spot-check each commit**

```bash
cd /Users/canefe/Projects/personal/story-sim && jj log -r @-..@-- --no-graph --limit 1
cd /Users/canefe/Projects/personal/story-go && jj log -r @-..@-- --no-graph --limit 1
cd /Users/canefe/Projects/personal/Story && jj log -r @-..@-- --no-graph --limit 1
```

Expected: each repo has its `feat(wire):` commit as the latest non-empty change. No Co-Authored-By.

- [ ] **Step 3: Aggregate test failures**

Collect the failing test names from each agent's report. Compare against the known-flaky list:
- story-sim: `plugins::behavior::planner_systems::system_tests` (9 cases)
- story-go: `TestDamageEventForwarding` (in pkg integration tests)
- StoryMC: ~42 NoClassDefFoundError full-boot tests

Any failure NOT in that list is a regression caused by this PR. Stop and triage before moving to Phase 3.

---

## Phase 3: End-to-end in-game verification (main thread)

### Task 3.1: Restart the stack and run a death roundtrip

- [ ] **Step 1: Restart story-sim**

In a separate terminal, the user already runs story-sim. Ask the user to restart it so it picks up the binary-emit changes. They typically use:

```bash
cd /Users/canefe/Projects/personal/story-sim
cargo run --bin story_sim > /tmp/story-sim.log 2>&1 &
```

- [ ] **Step 2: Restart story-go**

```bash
cd /Users/canefe/Projects/personal/story-go
# air should pick up the build automatically; if not, restart manually.
# Confirm via:
tail -20 logs/story-go.log
```

Expected log line: `[Sim] connecting to NATS` followed by subscribe confirmations. No errors mentioning JSON parse failures.

- [ ] **Step 3: Restart the MC server (plugin reload)**

The user controls the Minecraft server. Ask them to restart or `/reload confirm`. Confirm in `run/logs/latest.log`:

```
WebSocket transport connected to ...
```

No errors about failed-to-parse messages.

- [ ] **Step 4: Trigger a combat round-trip**

Ask the user to swing at any NPC in-game until it dies.

- [ ] **Step 5: Inspect logs**

```bash
grep -i "combat.attack_resolved\|entity_died\|MarkDead\|killed" /tmp/story-sim.log | tail -20
grep -i "combat\|entity_died\|broadcast" /Users/canefe/Projects/personal/story-go/logs/story-go.log | tail -20
grep -i "combat\|entity_died\|kill" /Users/canefe/Projects/personal/Story/run/logs/latest.log | tail -20
```

Expected:
- story-sim: emits `combat.attack_resolved` and `entity_died` SimEvents.
- story-go: logs the inbound NATS event (without "decode" warnings) and re-broadcasts.
- StoryMC: logs the inbound combat.attack_resolved adapter call; the NPC death animation plays in-game.

- [ ] **Step 6: Confirm Mongo state**

The user can run:

```bash
mongosh story-go --quiet --eval 'db.characters.findOne({name: "<NPC_NAME>"}, {status: 1, _id: 0})'
```

Expected: `{ status: "dead" }`.

- [ ] **Step 7: Confirm no respawn on follow-up spawn query**

Without restarting anything, if the player triggers a spawn query (e.g. by reconnecting or via `/storynpc spawn-near`), the dead NPC must NOT respawn. Confirm in the MC server log that the spawn handler skipped it.

---

## Out of scope (deferred to future PRs)

- Golden-file binary fixtures across the three repos (`combat_attack_resolved.golden.bin`, `entity_died.golden.bin`).
- Migrating plugin→go events (PluginEvent, perception intents, decision responses) to binary or proto.
- Fixing pre-existing flaky tests.
- Wiring real multi-world routing (the proto fields exist but consumers don't yet use them for routing decisions).

---

## Self-review

- **Spec coverage:** all four phases match the spec sections (wire shape, repo-level changes, proto additions, test strategy, verification). Out-of-scope list matches.
- **Placeholders:** none. Every step has explicit commands and code blocks.
- **Type consistency:** `Vec<u8>` payload on the Rust side; `[]byte` on the Go side; `ByteArray` (and `ByteBuffer` at the frame edge) on the Kotlin side. `SimEvent` is the outer proto on all hops. `broadcastSimEvent(ev *storyv1.SimEvent, label string)` is the only new Go function name. `BroadcastBinary(data []byte)` is the only new Sender/Hub method name.
- **Pre-existing failures:** explicitly listed per repo so agents don't chase them.
