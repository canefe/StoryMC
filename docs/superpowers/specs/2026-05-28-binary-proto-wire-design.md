# Binary protobuf wire on both SimEvent hops

**Date:** 2026-05-28
**Status:** Approved, ready for implementation plan
**Scope:** sim→go (NATS) and go→plugin (WebSocket) SimEvent traffic. Plugin→go traffic and non-SimEvent text/JSON traffic are out of scope.

## Problem

A prior session migrated the sim→go→plugin event vocabulary onto a typed `SimEvent` oneof in `story-proto`. The transport remained JSON, which has produced three drift sources that will keep biting:

1. **serde wrapper artifact.** prost+serde emits `{"event":{"sim_status":{"running":true}}}` — the outer `event` key is a serde representation of the Rust enum field, not anything in the proto contract. Go's `protojson` does not accept it.
2. **Field case divergence.** Sim emits snake_case (serde default), story-go re-broadcasts in camelCase (protojson default), StoryMC accepts either. Three repos, three conventions, all "matching the proto" by different definitions of "match".
3. **A 50-line hand-written shim** (`story-go/internal/bridge/sim_event_decode.go`) parses the wrapper, and StoryMC has a dual try-proto-then-fallback inbound path. Both exist to paper over the above.

## Decision

Drop JSON entirely on SimEvent hops. Use binary protobuf bytes:

- **sim→go**: NATS payload = `prost::Message::encode_to_vec(&SimEvent)`. Go side: `proto.Unmarshal(msg.Data, &sim)`.
- **go→plugin**: WebSocket `BinaryMessage` frame. Bytes = `proto.Marshal(simEvent)` of the outer `SimEvent`. Plugin: `SimEvent.parseFrom(byteBuffer)` from `Listener.onBinary`.

Bytes-on-the-wire are identical across all three languages. No wrapper, no field-case debate, no shim, no fallback path.

### Why not stay on JSON

Considered. The minimum-change alternative is to make all three sides canonical proto-JSON (camelCase, no `event` wrapper). That requires either patching prost's serde derive to emit canonical proto-JSON, or hand-writing a Rust serializer. Both move the drift problem rather than removing it — the next field added still has to be wired through three independent JSON paths. Binary proto removes the class of bug.

### Why mixed frames are fine

Non-SimEvent traffic (intelligence.response, permission.ask, npc.damaged, frontend.intent, plugin→go perception, intents, decision responses) has no proto definition today and stays on text/JSON. WebSocket frame type is naturally the discriminator: `BinaryMessage` ⟹ SimEvent, `TextMessage` ⟹ legacy `BridgeMessage`. No in-band magic byte, no header, no listener-side guessing.

## Architecture

### sim side (`story-sim`, Rust)

`src/plugins/nats_bridge.rs` publish call-sites: payload changes from JSON `String` to `Vec<u8>` produced by `sim_event.encode_to_vec()`. `async-nats 0.39`'s `publish(subject, payload.into())` accepts `Bytes` directly from `Vec<u8>` — no API change. Producers (combat_system, health_system, etc.) drop `serde_json::to_string` calls. Existing sim-side wire round-trip tests assert via `SimEvent::decode` rather than `serde_json::from_str`.

### go side (`story-go`, Go)

Three changes:

1. **Inbound NATS** (`internal/bridge/nats.go`): subscribe callback decodes `proto.Unmarshal(msg.Data, &sim)` directly. `internal/bridge/sim_event_decode.go` is deleted in full.
2. **Outbound WebSocket** (`internal/bridge/ws.go`): add `BroadcastBinary([]byte)` to `Hub` that writes `websocket.BinaryMessage`. Mirrors existing `Broadcast(msg events.BridgeMessage)` (which writes `TextMessage`). Extend `bridge.Sender` interface with the new method.
3. **Re-broadcast logic** (`internal/sim/handler.go`): `broadcastProto(msgType, m, label)` → `broadcastSimEvent(simEvent *pb.SimEvent)`. Caller passes the outer `SimEvent` (already in hand inside `Handle`). Implementation: `bytes, _ := proto.Marshal(simEvent); h.sender.BroadcastBinary(bytes)`. The seven plugin-visible variants (go_to, combat_attack_resolved, npc_state, entity_spawned, entity_died, npc_item_transfer, sim_status, sim_affordance_registry) all go through this path. The five sim→go-only variants (sim_init, nearby_query_response, recognition_reinforce, location_template_get_response, plus any future internal-only events) decode and dispatch internally — no broadcast change.

### plugin side (`Story`, Kotlin)

`bridge/WebSocketTransport.kt`:

1. Add a per-connection `ByteBuffer` accumulator field (since `Listener.onBinary` is fragment-aware via the `last: Boolean` flag — gorilla won't fragment small SimEvents in practice, but the API contract requires handling it).
2. Add `override fun onBinary(ws, data, last)`: append `data` to accumulator, if `last` then `SimEvent.parseFrom(accumulated)`, hand to `SimEventAdapter.dispatch(simEvent)`, reset accumulator, `request(1)`.
3. Delete the JSON-attempt branch of `tryParseSimEvent` and the body of `SimEventAdapter` that parses proto-JSON. Keep `SimEventAdapter`'s variant→Bukkit-event dispatch logic — only the parsing entry point changes.
4. `onText` keeps handling legacy `BridgeMessage` envelopes as today. No discriminator code in the message body; the listener method itself is the discriminator.

## Proto additions (Phase 0)

`story-proto/story/v1/events.proto`:

- `NpcState.world` (string) — multi-world support
- `EntitySpawned.world` (string)
- `EntitySpawned.mob_template` (string) — flagged missing by the prior StoryMC migration

Push to `origin/main` as a precondition for Phase 1; all three Phase-1 agents vendor the new commit SHA.

## Test strategy

- **Sim**: existing wire round-trip tests in `story-sim` re-pointed at `SimEvent::decode`. Pre-existing flaky `plugins::behavior::planner_systems::system_tests` (9 cases) are not addressed.
- **Go**: `go test ./...` for compile + unit pass. Pre-existing flaky `TestDamageEventForwarding` not addressed.
- **Plugin**: `./gradlew compileKotlin && ./gradlew test`. The 42 known-flaky NoClassDefFoundError full-boot tests stay flaky (env-level, per `project_test_classpath_noclassdef` memory).
- **End-to-end (Phase 3)**: restart sim + story-go (via air) + MC plugin. Swing at an NPC in-game. Confirm: sim emits binary on `story-sim.events`, story-go decodes without shim, plugin `onBinary` fires, kill animation plays, Mongo doc transitions to `status: "dead"`, follow-up spawn query does not respawn.

Golden-file binary fixtures across the three repos are deferred to a follow-up PR.

## Risks

- **Submodule pointer drift.** Three Phase-1 agents must vendor the same Phase-0 SHA. Each agent reports the SHA it pinned; main thread verifies all three match before merging.
- **Mixed-frame raw debug clients.** Anything connecting to the WebSocket via a raw client that only handles text frames will silently drop SimEvent traffic. Acceptable — proto-JSON wasn't readable either.
- **Legacy paths.** No fallback retained. If decode fails on either side, the event is dropped with a log. Intentional — fallback paths are how drift survives.

## Out of scope

- Migrating plugin→go events (`PluginEvent`, perception, intents) to binary or proto.
- Golden-file binary fixtures (deferred to follow-up).
- Fixing pre-existing flaky tests in any repo.
- Multi-world routing logic (just the proto field; consumers can read it but no new behavior wired).

## Implementation phasing

- **Phase 0** (main thread, single commit): add three proto fields, push to `origin/main`.
- **Phase 1** (three parallel general-purpose agents): sim, go, plugin. Each vendors the Phase-0 SHA, executes its repo-local changes, single commit per repo with `feat(wire):` prefix, no push, no Co-Authored-By, reports SHA and test results back.
- **Phase 2** (deferred): golden-file harness.
- **Phase 3** (main thread): end-to-end in-game verification.
