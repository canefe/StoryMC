# In-Game Sim Authoring Commands — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a builder author the village-scenario setup (locations with tags/radius, vendor offers, item grants, traits, need/stat snapshots, location knowledge, home assignment) in-game via commands, persisted to Mongo and applied live to the running sim — replacing the hard-coded `spawn_village_demo.lua`.

**Architecture:** Three layers mirroring the proven `/story char spawn → spawn_entity` path. Plugin (Kotlin) commands write Mongo (`locations` extended; new `character_data`) and emit `@Serializable` bridge intents. story-go (Go) forwards each intent to story-sim as a NATS message and re-applies everything on `sim.init`. story-sim (Rust/Bevy) adds thin stream handlers that feed existing Lua command queues — with three exceptions requiring new sim logic: absolute need/stat set, and character_id resolution for know/home.

**Tech Stack:** Kotlin (Paper plugin, CommandAPI, kotlinx.serialization, MongoDB driver), Go (mongo-driver/v2, NATS), Rust (Bevy ECS, mlua).

**Cross-repo paths:**
- Plugin: `/Users/canefe/Projects/personal/Story`
- story-go: `/Users/canefe/Projects/personal/story-go`
- story-sim: `/Users/canefe/Projects/personal/story-sim`

> **SCOPE CHANGE (2026-05-22, during execution):** `home` / `assign_home` is **DROPPED** from this feature. Reason: story-sim's `assign_home_location` apply is a log-only stub (no `HomeLocation` component exists; behaviors don't consume it). Do NOT implement any `home`/`assign_home`/`homeLocation` piece in any task — skip the `assign_home` stream handler, the `ForwardHome` forwarder, the `NpcHome`/`npc.home` constant+intent, the `homeLocation` field in `character_data`/`CharacterDataDocument`, the re-seed `assign_home` emit, and the `/story npc home` subcommand. Wherever a task lists "...know, home..." treat it as "...know..." only. Task 7 covers `know_location` only.

**Build/test per repo:**
- Plugin: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` / `./gradlew test`
- story-go: `cd /Users/canefe/Projects/personal/story-go && go build ./... && go test ./...`
- story-sim: `cd /Users/canefe/Projects/personal/story-sim && cargo test` (NO `--lib` target; use `cargo test` or `cargo test --bin story-sim`)

---

## File Structure

### Plugin (Story)
- Modify `src/main/kotlin/com/canefe/story/storage/LocationStorage.kt` — add `radius`, `tags` to `LocationDocument`.
- Modify `src/main/kotlin/com/canefe/story/location/data/StoryLocation.kt` — add `radius`, `tags`.
- Modify `src/main/kotlin/com/canefe/story/storage/mongo/MongoLocationStorage.kt` — read/write new fields.
- Create `src/main/kotlin/com/canefe/story/storage/CharacterDataDocument.kt` — `@Serializable` snapshot model.
- Create `src/main/kotlin/com/canefe/story/storage/mongo/MongoCharacterDataStorage.kt` — CRUD on `character_data`.
- Modify `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt` — new intents.
- Modify `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt` — encode arms for new intents.
- Create `src/main/kotlin/com/canefe/story/command/story/location/TagLocationCommand.kt`, `RadiusLocationCommand.kt`.
- Create `src/main/kotlin/com/canefe/story/command/story/npc/NpcAuthorCommand.kt` (+ subcommand files).
- Modify `src/main/kotlin/com/canefe/story/command/story/location/LocationCommand.kt` — register tag/radius subcommands.
- Wire `MongoCharacterDataStorage` where the plugin builds Mongo stores (alongside `MongoCharacterStorage`).

### story-go
- Modify `pkg/events/events.go` — new message-type constants.
- Modify `pkg/character/resolver.go` — `ListCharacterData()` reading `character_data`; reuse existing `LocationDocument`-style read for `locations`.
- Modify `internal/sim/handler.go` — forwarder methods + re-seed steps; add `locations` store dependency.
- Modify `internal/server/server.go` — `routeMessage` cases; wire location store into `sim.Handler`.

### story-sim
- Modify `src/plugins/stream_handlers/mod.rs` — add `LuaCommandQueues` + extra queue resources to `StreamHandlerContext`; register new handlers.
- Create `src/plugins/stream_handlers/spawn_location.rs`, `set_offers.rs`, `give_item.rs`, `give_trait.rs`, `set_need.rs`, `set_stat.rs`, `know_location.rs`, `assign_home.rs`.
- Modify `src/plugins/lua_world_api.rs` — add `character_id` to `KnowLocationRequest`/`AssignHomeLocationRequest`; teach their apply systems ExternalId-first matching; add absolute-set path for need/stat.

---

## Phase 1 — Sim foundation (story-sim)

Build the sim handlers first so the rest of the pipeline has a target. Each handler feeds an existing queue; gaps (context wiring, character_id resolution, absolute set) are explicit tasks.

### Task 1: Expose Lua command queues to stream handlers

**Files:**
- Modify: `src/plugins/stream_handlers/mod.rs` (StreamHandlerContext struct, ~lines 28-92)

- [ ] **Step 1: Add queue resources to StreamHandlerContext**

In `StreamHandlerContext`, add these fields (the resources already exist and are `init_resource`'d in `lua_world_api.rs`):

```rust
    pub lua_queues: Res<'w, crate::plugins::lua_world_api::LuaCommandQueues>,
    pub stat_modify_queue: Res<'w, crate::plugins::lua_world_api::StatModifyQueue>,
    pub item_modify_queue: Res<'w, crate::plugins::lua_world_api::ItemModifyQueue>,
```

(`LuaCommandQueues` holds `spawn_locations`, `set_offers`, `give_trait`, `know_locations`, `assign_home_locations`, `modify_needs`. `StatModifyQueue` and `ItemModifyQueue` are separate resources.)

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build --bin story-sim`
Expected: PASS (no use yet, just the new fields).

- [ ] **Step 3: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim
git add src/plugins/stream_handlers/mod.rs
git commit -m "feat(sim): expose lua command queues to stream handler context"
```

### Task 2: `spawn_location` stream handler

**Files:**
- Create: `src/plugins/stream_handlers/spawn_location.rs`
- Modify: `src/plugins/stream_handlers/mod.rs` (handlers() registry, ~line 101)

- [ ] **Step 1: Write the handler**

Create `src/plugins/stream_handlers/spawn_location.rs`:

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::SpawnLocationRequest;
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry {
        msg_types: &["spawn_location"],
        handler: handle,
    });
}

/// Spawns a location region into the sim from an external (story-go) command.
/// Fields: id, instance_name, x, y, z, radius, tags (comma-joined string).
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let id = msg.str_field("id");
    if id.is_empty() {
        return;
    }
    let instance_name = msg.str_field("instance_name");
    let tags_raw = msg.str_field("tags");
    let tags: Vec<String> = if tags_raw.is_empty() {
        Vec::new()
    } else {
        tags_raw.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
    };
    let req = SpawnLocationRequest {
        id: id.to_string(),
        instance_name: instance_name.to_string(),
        x: msg.f32_field("x"),
        y: msg.f32_field("y"),
        z: msg.f32_field("z"),
        radius: { let r = msg.f32_field("radius"); if r <= 0.0 { 8.0 } else { r } },
        tags,
    };
    info!("[spawn_location] queuing id={} name={}", req.id, req.instance_name);
    if let Ok(mut q) = ctx.lua_queues.spawn_locations.lock() {
        q.push(req);
    }
}
```

- [ ] **Step 2: Register the handler**

In `mod.rs` `handlers()`, add after `spawn_entity::register(&mut entries);`:

```rust
    spawn_location::register(&mut entries);
```

And add the module declaration at the top of `mod.rs` with the other `mod` lines:

```rust
mod spawn_location;
```

- [ ] **Step 3: Build**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build --bin story-sim`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/plugins/stream_handlers/spawn_location.rs src/plugins/stream_handlers/mod.rs
git commit -m "feat(sim): spawn_location stream handler feeding lua spawn_locations queue"
```

### Task 3: `set_offers` stream handler

**Files:**
- Create: `src/plugins/stream_handlers/set_offers.rs`
- Modify: `src/plugins/stream_handlers/mod.rs`

> NOTE: `SetOffersRequest` uses `npc_name` and its apply system matches by name only (`process_lua_command_queues`, ~line 2626). The plugin will send the NPC's display **name** alongside `character_id`; this handler uses the name field to stay compatible with the existing apply system. Resolving offers by `character_id` is out of scope (offers target a stall vendor by name in the demo); the handler reads a `name` field.

- [ ] **Step 1: Write the handler**

Create `src/plugins/stream_handlers/set_offers.rs`:

```rust
use bevy::log::info;
use serde_json::Value;

use crate::plugins::lua_world_api::SetOffersRequest;
use crate::components::offers::Offer;
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["set_offers"], handler: handle });
}

/// Sets a vendor's offer list. Fields: name (NPC display name),
/// offers (JSON array string: [{id, wants:[{tag|item,qty}], gives:[{item,qty}], while_situation?}]).
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let name = msg.str_field("name");
    if name.is_empty() {
        return;
    }
    let offers_json = msg.str_field("offers");
    let parsed: Vec<Value> = serde_json::from_str(&offers_json).unwrap_or_default();
    let offers: Vec<Offer> = parsed.iter().filter_map(Offer::from_authoring_json).collect();
    info!("[set_offers] {} offers for {}", offers.len(), name);
    if let Ok(mut q) = ctx.lua_queues.set_offers.lock() {
        q.push(SetOffersRequest { npc_name: name.to_string(), offers });
    }
}
```

- [ ] **Step 2: Add `Offer::from_authoring_json`**

In `src/components/offers.rs`, add a constructor that mirrors the Lua offer schema. First read the existing `Offer` struct and its `wants`/`gives` types, then implement:

```rust
impl Offer {
    /// Build an Offer from the authoring JSON shape:
    /// { id, wants: [{tag|item, qty}], gives: [{item, qty}], while_situation? }
    pub fn from_authoring_json(v: &serde_json::Value) -> Option<Offer> {
        // Implement using the actual Offer/want/give field names from this file.
        // Map "tag"/"item"+"qty" entries into the existing want/give representation,
        // and "while_situation" into the existing situation gate field.
        // Return None if `id` or `gives` is missing.
        todo!("map JSON to the real Offer fields defined above in this file")
    }
}
```

> The implementer MUST replace the `todo!` by reading the real `Offer` definition in `offers.rs` (the request struct references `crate::components::offers::Offer`). Field names there are authoritative.

- [ ] **Step 3: Register + module decl** in `mod.rs`:

```rust
mod set_offers;
// ... in handlers():
    set_offers::register(&mut entries);
```

- [ ] **Step 4: Build**

Run: `cargo build --bin story-sim`
Expected: PASS once `from_authoring_json` is implemented (no `todo!`).

- [ ] **Step 5: Commit**

```bash
git add src/plugins/stream_handlers/set_offers.rs src/plugins/stream_handlers/mod.rs src/components/offers.rs
git commit -m "feat(sim): set_offers stream handler + Offer::from_authoring_json"
```

### Task 4: `give_item` and `give_trait` stream handlers

**Files:**
- Create: `src/plugins/stream_handlers/give_item.rs`, `src/plugins/stream_handlers/give_trait.rs`
- Modify: `src/plugins/stream_handlers/mod.rs`

`ModifyItemRequest` and `GiveTraitRequest` both already carry `character_id: Option<String>` and their apply systems do ExternalId-first matching — no sim changes needed beyond feeding the queues.

> **Snapshot semantics:** `give_item` here SETS the inventory entry to the snapshot quantity, not add. The simplest correct approach for re-seed idempotency: the handler sends `delta` equal to the target qty, and on first spawn the inventory is empty so add == set. For the in-game live command, `give` is additive by design (builder stocks N). To keep it simple and consistent with the existing additive `ModifyItemRequest`, this handler uses additive delta; re-seed is safe because re-seed happens on a freshly-spawned (empty-inventory) entity. Document this in the handler.

- [ ] **Step 1: Write give_item handler**

Create `src/plugins/stream_handlers/give_item.rs`:

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::ModifyItemRequest;
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["give_item"], handler: handle });
}

/// Adds items to an NPC inventory. Fields: character_id, name, item, qty.
/// Additive: re-seed runs against a freshly-spawned (empty) inventory, so add == set there.
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let character_id = msg.str_field("character_id");
    let item = msg.str_field("item");
    let qty = msg.f32_field("qty") as i32;
    if item.is_empty() || qty == 0 {
        return;
    }
    info!("[give_item] {} x{} -> char_id={}", item, qty, character_id);
    if let Ok(mut q) = ctx.item_modify_queue.requests.lock() {
        q.push(ModifyItemRequest {
            character_id: if character_id.is_empty() { None } else { Some(character_id.to_string()) },
            entity_name: msg.str_field("name").to_string(),
            item_id: item.to_string(),
            delta: qty,
            source: Some("authoring".to_string()),
        });
    }
}
```

- [ ] **Step 2: Write give_trait handler**

Create `src/plugins/stream_handlers/give_trait.rs`:

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::GiveTraitRequest;
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["give_trait"], handler: handle });
}

/// Grants a trait. Fields: character_id, name, trait.
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let character_id = msg.str_field("character_id");
    let trait_id = msg.str_field("trait");
    if trait_id.is_empty() {
        return;
    }
    info!("[give_trait] {} -> char_id={}", trait_id, character_id);
    if let Ok(mut q) = ctx.lua_queues.give_trait.lock() {
        q.push(GiveTraitRequest {
            character_id: if character_id.is_empty() { None } else { Some(character_id.to_string()) },
            entity_name: msg.str_field("name").to_string(),
            trait_id: trait_id.to_string(),
        });
    }
}
```

- [ ] **Step 3: Register both + module decls** in `mod.rs`:

```rust
mod give_item;
mod give_trait;
// in handlers():
    give_item::register(&mut entries);
    give_trait::register(&mut entries);
```

- [ ] **Step 4: Build**

Run: `cargo build --bin story-sim`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/plugins/stream_handlers/give_item.rs src/plugins/stream_handlers/give_trait.rs src/plugins/stream_handlers/mod.rs
git commit -m "feat(sim): give_item and give_trait stream handlers"
```

### Task 5: Absolute set path for needs and stats

**Files:**
- Modify: `src/plugins/lua_world_api.rs` (add `mode` to `ModifyNeedRequest`/`ModifyStatRequest`; update apply systems)
- Test: `src/plugins/lua_world_api.rs` (inline `#[cfg(test)]` or a new test module)

The snapshot stores absolute need/stat values; the existing queues only `modify` (delta). Add a `set` mode.

- [ ] **Step 1: Write failing test for absolute need set**

Add to a test module in `src/plugins/lua_world_api.rs` (or the existing test file pattern in the crate). Spawn an entity with a `Needs` component starting at hunger=50, push a `ModifyNeedRequest` with `mode: NeedStatOp::Set` value 30, run `process_lua_command_queues`, assert hunger == 30 (not 80).

```rust
#[cfg(test)]
mod authoring_set_tests {
    use super::*;
    // Build a minimal App with the needed registries/resources, spawn one NPC,
    // queue a Set request, run the system once, assert the absolute value.
    #[test]
    fn set_need_assigns_absolute_value() {
        // Arrange a Bevy App with Needs component and LuaCommandQueues;
        // hunger starts at 50.0. Push ModifyNeedRequest { op: Set, need_id:"hunger", value:30.0 }.
        // Run process_lua_command_queues. Assert hunger == 30.0.
        todo!("construct minimal app per existing test patterns in this crate")
    }
}
```

> Implementer: model this on any existing system test in story-sim. If none exists for these systems, the assertion can be done by querying the `Needs` component after `app.update()`.

- [ ] **Step 2: Run it — expect FAIL (no Set op yet)**

Run: `cargo test --bin story-sim set_need_assigns_absolute_value`
Expected: FAIL (compile error: no `op`/`Set` field).

- [ ] **Step 3: Add the op enum and field**

In `lua_world_api.rs`, define:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NeedStatOp { Modify, Set }

impl Default for NeedStatOp {
    fn default() -> Self { NeedStatOp::Modify }
}
```

Add `pub op: NeedStatOp` to both `ModifyNeedRequest` and `ModifyStatRequest`. Update all existing constructors (the Lua handle paths around lines 872, 894, 1149, 1182) to set `op: NeedStatOp::Modify` so current behavior is unchanged.

- [ ] **Step 4: Implement Set in the apply systems**

In `process_lua_command_queues` (need branch, ~line 2576) and `process_stat_modify_queue` (~line 1949), when matched:

```rust
match request.op {
    NeedStatOp::Modify => { needs.modify(&request.need_id, request.delta); }
    NeedStatOp::Set => { needs.set(&request.need_id, request.delta); } // absolute
}
```

If `Needs`/`Stats` lack a `set`, add one (read the component to confirm; `Needs::modify` exists, so add `Needs::set(&mut self, id, value)` setting the absolute value, clamped like modify). Do the same for `Stats` (it has `add`; add `set`).

- [ ] **Step 5: Run the test — expect PASS**

Run: `cargo test --bin story-sim set_need_assigns_absolute_value`
Expected: PASS.

- [ ] **Step 6: Run full sim tests (no regression)**

Run: `cargo test --bin story-sim`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/plugins/lua_world_api.rs src/components/needs.rs src/components/stats.rs
git commit -m "feat(sim): absolute Set op for need/stat modify queues (snapshot semantics)"
```

### Task 6: `set_need` and `set_stat` stream handlers

**Files:**
- Create: `src/plugins/stream_handlers/set_need.rs`, `set_stat.rs`
- Modify: `src/plugins/stream_handlers/mod.rs`

- [ ] **Step 1: Write set_need handler**

Create `src/plugins/stream_handlers/set_need.rs`:

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::{ModifyNeedRequest, NeedStatOp};
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["set_need"], handler: handle });
}

/// Sets a need to an absolute value. Fields: character_id, name, need, value.
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let character_id = msg.str_field("character_id");
    let need_id = msg.str_field("need");
    if need_id.is_empty() {
        return;
    }
    info!("[set_need] {}={} char_id={}", need_id, msg.f32_field("value"), character_id);
    if let Ok(mut q) = ctx.lua_queues.modify_needs.lock() {
        q.push(ModifyNeedRequest {
            character_id: if character_id.is_empty() { None } else { Some(character_id.to_string()) },
            entity_name: msg.str_field("name").to_string(),
            need_id: need_id.to_string(),
            delta: msg.f32_field("value"),
            op: NeedStatOp::Set,
        });
    }
}
```

- [ ] **Step 2: Write set_stat handler**

Create `src/plugins/stream_handlers/set_stat.rs` (same shape, `StatModifyQueue`, fields `stat`/`value`):

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::{ModifyStatRequest, NeedStatOp};
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["set_stat"], handler: handle });
}

/// Sets a stat to an absolute value. Fields: character_id, name, stat, value.
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let character_id = msg.str_field("character_id");
    let stat_id = msg.str_field("stat");
    if stat_id.is_empty() {
        return;
    }
    info!("[set_stat] {}={} char_id={}", stat_id, msg.f32_field("value"), character_id);
    if let Ok(mut q) = ctx.stat_modify_queue.requests.lock() {
        q.push(ModifyStatRequest {
            character_id: if character_id.is_empty() { None } else { Some(character_id.to_string()) },
            entity_name: msg.str_field("name").to_string(),
            stat_id: stat_id.to_string(),
            delta: msg.f32_field("value"),
            op: NeedStatOp::Set,
        });
    }
}
```

- [ ] **Step 3: Register + module decls** in `mod.rs`:

```rust
mod set_need;
mod set_stat;
// handlers():
    set_need::register(&mut entries);
    set_stat::register(&mut entries);
```

- [ ] **Step 4: Build + test**

Run: `cargo test --bin story-sim`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/plugins/stream_handlers/set_need.rs src/plugins/stream_handlers/set_stat.rs src/plugins/stream_handlers/mod.rs
git commit -m "feat(sim): set_need and set_stat stream handlers (absolute)"
```

### Task 7: character_id resolution for know_location & assign_home

**Files:**
- Modify: `src/plugins/lua_world_api.rs` (`KnowLocationRequest`, `AssignHomeLocationRequest`, `apply_know_locations_system`, the assign-home apply system)
- Test: `src/plugins/lua_world_api.rs`

These two requests currently match by `npc_name` only. Add optional `character_id` and ExternalId-first matching (consistent with need/stat/item/trait).

- [ ] **Step 1: Write failing test**

Add a test: spawn an NPC with `ExternalId("npc_x")` and `Name("Maja")` plus a `Location{def_id:"market"}`. Push `KnowLocationRequest { character_id: Some("npc_x"), npc_name: "", location_def_id: "market" }`. Run `apply_know_locations_system`. Assert the NPC's `LocationMemory` now knows "market".

```rust
#[test]
fn know_location_resolves_by_character_id() {
    todo!("minimal app: NPC w/ ExternalId+Name+LocationMemory, a Location entity, queue by character_id, run system, assert known");
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cargo test --bin story-sim know_location_resolves_by_character_id`
Expected: FAIL (no `character_id` field).

- [ ] **Step 3: Add character_id and ExternalId matching**

Add `pub character_id: Option<String>` to both `KnowLocationRequest` and `AssignHomeLocationRequest`. Update Lua constructors (~lines 805, 783) to set `character_id: None`.

In `apply_know_locations_system` (~line 2382), change the NPC query to include `Option<&ExternalId>` and the match to:

```rust
let matches = if let Some(cid) = &req.character_id {
    eid.map_or(false, |e| e.id == *cid)
} else {
    name.as_str().eq_ignore_ascii_case(&req.npc_name)
};
```

Apply the identical change to the assign-home apply system (find it near the other `process_lua_command_queues` location branches; it consumes `assign_home_locations`).

- [ ] **Step 4: Run the test — expect PASS**

Run: `cargo test --bin story-sim know_location_resolves_by_character_id`
Expected: PASS.

- [ ] **Step 5: Full sim tests**

Run: `cargo test --bin story-sim`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/plugins/lua_world_api.rs
git commit -m "feat(sim): resolve know_location/assign_home by character_id (ExternalId-first)"
```

### Task 8: `know_location` and `assign_home` stream handlers

**Files:**
- Create: `src/plugins/stream_handlers/know_location.rs`, `assign_home.rs`
- Modify: `src/plugins/stream_handlers/mod.rs`

- [ ] **Step 1: Write know_location handler**

Create `src/plugins/stream_handlers/know_location.rs`:

```rust
use bevy::log::info;

use crate::plugins::lua_world_api::KnowLocationRequest;
use super::{HandlerEntry, StreamHandlerContext, StreamMessage};

pub fn register(entries: &mut Vec<HandlerEntry>) {
    entries.push(HandlerEntry { msg_types: &["know_location"], handler: handle });
}

/// Seeds an NPC's location knowledge. Fields: character_id, location_id.
fn handle(msg: &StreamMessage, ctx: &mut StreamHandlerContext) {
    let character_id = msg.str_field("character_id");
    let location_id = msg.str_field("location_id");
    if character_id.is_empty() || location_id.is_empty() {
        return;
    }
    info!("[know_location] char_id={} loc={}", character_id, location_id);
    if let Ok(mut q) = ctx.lua_queues.know_locations.lock() {
        q.push(KnowLocationRequest {
            character_id: Some(character_id.to_string()),
            npc_name: String::new(),
            location_def_id: location_id.to_string(),
        });
    }
}
```

- [ ] **Step 2: Write assign_home handler**

Create `src/plugins/stream_handlers/assign_home.rs` (same shape, `assign_home_locations` queue, `AssignHomeLocationRequest`).

- [ ] **Step 3: Register + module decls** in `mod.rs`:

```rust
mod know_location;
mod assign_home;
// handlers():
    know_location::register(&mut entries);
    assign_home::register(&mut entries);
```

- [ ] **Step 4: Build + test**

Run: `cargo test --bin story-sim`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/plugins/stream_handlers/know_location.rs src/plugins/stream_handlers/assign_home.rs src/plugins/stream_handlers/mod.rs
git commit -m "feat(sim): know_location and assign_home stream handlers"
```

---

## Phase 2 — story-go forwarders + re-seed

### Task 9: Message-type constants

**Files:**
- Modify: `pkg/events/events.go` (constants block ~lines 21-42)

- [ ] **Step 1: Add constants**

Add a new const block:

```go
// Authoring messages forwarded plugin → story-go → story-sim.
const (
	LocationSpawn = "location.spawn"
	NpcSetOffers  = "npc.set_offers"
	NpcGiveItem   = "npc.give_item"
	NpcGiveTrait  = "npc.give_trait"
	NpcSetNeed    = "npc.set_need"
	NpcSetStat    = "npc.set_stat"
	NpcKnow       = "npc.know"
	NpcHome       = "npc.home"
)
```

- [ ] **Step 2: Build**

Run: `cd /Users/canefe/Projects/personal/story-go && go build ./...`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add pkg/events/events.go
git commit -m "feat: authoring message-type constants"
```

### Task 10: character_data resolver + locations read

**Files:**
- Modify: `pkg/character/resolver.go`
- Test: `pkg/character/resolver_test.go` (create if absent)

- [ ] **Step 1: Add CharacterData type + ListCharacterData**

In `resolver.go`, model on `ListAffordances` (uses `r.collection.Database().Collection("character_data")`):

```go
type OfferSpec struct {
	ID            string          `bson:"id" json:"id"`
	Wants         []OfferItemSpec `bson:"wants" json:"wants"`
	Gives         []OfferItemSpec `bson:"gives" json:"gives"`
	WhileSituation string         `bson:"whileSituation,omitempty" json:"while_situation,omitempty"`
}
type OfferItemSpec struct {
	Tag  string `bson:"tag,omitempty" json:"tag,omitempty"`
	Item string `bson:"item,omitempty" json:"item,omitempty"`
	Qty  int    `bson:"qty" json:"qty"`
}
type ItemStack struct {
	Item string `bson:"item" json:"item"`
	Qty  int    `bson:"qty" json:"qty"`
}
type CharacterData struct {
	ID                string             `bson:"_id"`
	Offers            []OfferSpec        `bson:"offers"`
	StartingInventory []ItemStack        `bson:"startingInventory"`
	Traits            []string           `bson:"traits"`
	NeedValues        map[string]float64 `bson:"needValues"`
	StatValues        map[string]float64 `bson:"statValues"`
	KnownLocations    []string           `bson:"knownLocations"`
	HomeLocation      string             `bson:"homeLocation"`
}

func (r *Resolver) ListCharacterData() ([]CharacterData, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	col := r.collection.Database().Collection("character_data")
	cur, err := col.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []CharacterData
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}
```

- [ ] **Step 2: Add SimLocation type + ListSimLocations**

```go
type SimLocation struct {
	Name   string   `bson:"name"`
	World  string   `bson:"world"`
	X      float64  `bson:"x"`
	Y      float64  `bson:"y"`
	Z      float64  `bson:"z"`
	Radius float64  `bson:"radius"`
	Tags   []string `bson:"tags"`
}

func (r *Resolver) ListSimLocations() ([]SimLocation, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	col := r.collection.Database().Collection("locations")
	cur, err := col.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []SimLocation
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}
```

- [ ] **Step 3: Write a compile/smoke test**

In `resolver_test.go`, add a test that constructs `CharacterData`/`SimLocation` literals and asserts BSON tag names via `reflect` (no live Mongo needed):

```go
func TestCharacterDataBsonTags(t *testing.T) {
	f, _ := reflect.TypeOf(CharacterData{}).FieldByName("NeedValues")
	if got := f.Tag.Get("bson"); got != "needValues" {
		t.Fatalf("needValues bson tag = %q", got)
	}
}
```

- [ ] **Step 4: Run**

Run: `go test ./pkg/character/...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pkg/character/resolver.go pkg/character/resolver_test.go
git commit -m "feat: ListCharacterData and ListSimLocations resolvers"
```

### Task 11: Forwarder methods

**Files:**
- Modify: `internal/sim/handler.go` (new methods, mirror `ForwardSpawnToSim` ~lines 508-557)

- [ ] **Step 1: Add forwarders**

Add one method per authoring type. Each guards on `h.state.IsActive()`, reads `msg.Data`, and republishes to NATS via `h.pub.Publish`. Example for offers (others follow identically, copying the fields each carries):

```go
func (h *Handler) ForwardSetOffers(msg events.BridgeMessage) {
	if !h.state.IsActive() || h.pub == nil {
		return
	}
	name, _ := msg.Data["name"].(string)
	offers, _ := msg.Data["offers"].(string) // JSON array string
	if name == "" {
		return
	}
	_ = h.pub.Publish(events.BridgeMessage{
		Type: "set_offers", Timestamp: time.Now().UnixMilli(), Source: "story-go",
		Data: map[string]interface{}{"name": name, "offers": offers},
	})
}

func (h *Handler) ForwardLocationSpawn(msg events.BridgeMessage) {
	if !h.state.IsActive() || h.pub == nil {
		return
	}
	id, _ := msg.Data["id"].(string)
	if id == "" {
		return
	}
	_ = h.pub.Publish(events.BridgeMessage{
		Type: "spawn_location", Timestamp: time.Now().UnixMilli(), Source: "story-go",
		Data: map[string]interface{}{
			"id":            id,
			"instance_name": msg.Data["instance_name"],
			"x":             msg.Data["x"], "y": msg.Data["y"], "z": msg.Data["z"],
			"radius":        msg.Data["radius"], "tags": msg.Data["tags"],
		},
	})
}
```

Add `ForwardGiveItem`, `ForwardGiveTrait`, `ForwardSetNeed`, `ForwardSetStat`, `ForwardKnow`, `ForwardHome` the same way — each republishing to the matching sim message type (`give_item`, `give_trait`, `set_need`, `set_stat`, `know_location`, `assign_home`) with the fields the sim handlers read (Task 2–8). For `give_item`: `character_id, name, item, qty`. For `set_need`: `character_id, name, need, value`. For `set_stat`: `character_id, name, stat, value`. For `give_trait`: `character_id, name, trait`. For `know`: `character_id, location_id`. For `home`: `character_id, location_id`.

- [ ] **Step 2: Build**

Run: `go build ./...`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add internal/sim/handler.go
git commit -m "feat: authoring forwarder methods (offers/items/traits/needs/stats/know/home/location)"
```

### Task 12: routeMessage wiring

**Files:**
- Modify: `internal/server/server.go` (`routeMessage` ~lines 529-614)

- [ ] **Step 1: Add cases**

After the existing `events.NpcSpawn` case:

```go
	if s.simHandler != nil {
		switch msg.Type {
		case events.LocationSpawn:
			s.simHandler.ForwardLocationSpawn(msg)
			return
		case events.NpcSetOffers:
			s.simHandler.ForwardSetOffers(msg)
			return
		case events.NpcGiveItem:
			s.simHandler.ForwardGiveItem(msg)
			return
		case events.NpcGiveTrait:
			s.simHandler.ForwardGiveTrait(msg)
			return
		case events.NpcSetNeed:
			s.simHandler.ForwardSetNeed(msg)
			return
		case events.NpcSetStat:
			s.simHandler.ForwardSetStat(msg)
			return
		case events.NpcKnow:
			s.simHandler.ForwardKnow(msg)
			return
		case events.NpcHome:
			s.simHandler.ForwardHome(msg)
			return
		}
	}
```

- [ ] **Step 2: Build**

Run: `go build ./...`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add internal/server/server.go
git commit -m "feat: route authoring messages to sim forwarders"
```

### Task 13: Re-seed on sim.init

**Files:**
- Modify: `internal/sim/handler.go` (`handleSimInit` ~lines 352-387)

- [ ] **Step 1: Add re-seed helpers and call them**

After the existing per-NPC spawn loop in `handleSimInit`, add:

```go
	h.reseedLocations()
	h.reseedCharacterData()
```

Implement (locations first — offers/home/knowledge reference them):

```go
func (h *Handler) reseedLocations() {
	if h.resolver == nil || h.pub == nil {
		return
	}
	locs, err := h.resolver.ListSimLocations()
	if err != nil {
		log.Warnf("[Sim] reseed locations failed: %v", err)
		return
	}
	for _, l := range locs {
		radius := l.Radius
		if radius <= 0 {
			radius = 8.0
		}
		_ = h.pub.Publish(events.BridgeMessage{
			Type: "spawn_location", Timestamp: time.Now().UnixMilli(), Source: "story-go",
			Data: map[string]interface{}{
				"id": l.Name, "instance_name": l.Name,
				"x": l.X, "y": l.Y, "z": l.Z, "radius": radius,
				"tags": strings.Join(l.Tags, ","),
			},
		})
	}
	log.Infof("[Sim] reseeded %d locations", len(locs))
}

func (h *Handler) reseedCharacterData() {
	if h.resolver == nil || h.pub == nil {
		return
	}
	data, err := h.resolver.ListCharacterData()
	if err != nil {
		log.Warnf("[Sim] reseed character_data failed: %v", err)
		return
	}
	for _, d := range data {
		name := h.resolver.Resolve(d.ID) // characterId -> display name
		pub := func(t string, extra map[string]interface{}) {
			extra["character_id"] = d.ID
			extra["name"] = name
			_ = h.pub.Publish(events.BridgeMessage{Type: t, Timestamp: time.Now().UnixMilli(), Source: "story-go", Data: extra})
		}
		if len(d.Offers) > 0 {
			if b, err := json.Marshal(d.Offers); err == nil {
				pub("set_offers", map[string]interface{}{"offers": string(b)})
			}
		}
		for _, it := range d.StartingInventory {
			pub("give_item", map[string]interface{}{"item": it.Item, "qty": float64(it.Qty)})
		}
		for _, tr := range d.Traits {
			pub("give_trait", map[string]interface{}{"trait": tr})
		}
		for need, v := range d.NeedValues {
			pub("set_need", map[string]interface{}{"need": need, "value": v})
		}
		for stat, v := range d.StatValues {
			pub("set_stat", map[string]interface{}{"stat": stat, "value": v})
		}
		for _, loc := range d.KnownLocations {
			pub("know_location", map[string]interface{}{"location_id": loc})
		}
		if d.HomeLocation != "" {
			pub("assign_home", map[string]interface{}{"location_id": d.HomeLocation})
		}
	}
	log.Infof("[Sim] reseeded character_data for %d NPCs", len(data))
}
```

Add `encoding/json` and `strings` to imports if not present.

> NOTE: `set_offers` reseed sends a JSON array string under key `offers`, matching Task 3's handler which parses `msg.str_field("offers")`. The `OfferSpec` JSON tags (Task 10) must match what `Offer::from_authoring_json` (Task 3) expects — verify field names align: `wants`/`gives` arrays of `{tag|item, qty}`, optional `while_situation`.

- [ ] **Step 2: Build**

Run: `go build ./...`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add internal/sim/handler.go
git commit -m "feat: re-seed locations and character_data to sim on sim.init"
```

---

## Phase 3 — Plugin: persistence, intents, commands

### Task 14: Extend location model with radius + tags

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/storage/LocationStorage.kt`
- Modify: `src/main/kotlin/com/canefe/story/location/data/StoryLocation.kt`
- Modify: `src/main/kotlin/com/canefe/story/storage/mongo/MongoLocationStorage.kt`

- [ ] **Step 1: Add fields to LocationDocument**

In `LocationStorage.kt`, add to `LocationDocument`:

```kotlin
    val radius: Double = 8.0,
    val tags: List<String> = emptyList(),
```

- [ ] **Step 2: Add fields to StoryLocation**

In `StoryLocation.kt`, add:

```kotlin
    var radius: Double = 8.0,
    val tags: MutableList<String> = mutableListOf(),
```

(Place them as the last constructor params with defaults so the existing secondary constructors still compile.)

- [ ] **Step 3: Read/write fields in MongoLocationStorage**

In `documentToLocationDocument`, add:

```kotlin
            radius = (doc.get("radius") as? Number)?.toDouble() ?: 8.0,
            tags = doc.getList("tags", String::class.java) ?: emptyList(),
```

(Writes go through the typed `replaceOne(LocationDocument)`, so no extra write code needed once the data class has the fields.)

- [ ] **Step 4: Map fields wherever LocationDocument ↔ StoryLocation convert**

Search for conversions: `grep -rn "LocationDocument(" src/main/kotlin` and `grep -rn "StoryLocation(" src/main/kotlin`. Add `radius`/`tags` to each mapping in `LocationManager` (the manager that converts between the two). Show no field is dropped.

- [ ] **Step 5: Compile**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/canefe/story/storage/LocationStorage.kt src/main/kotlin/com/canefe/story/location/data/StoryLocation.kt src/main/kotlin/com/canefe/story/storage/mongo/MongoLocationStorage.kt src/main/kotlin/com/canefe/story/location/LocationManager.kt
git commit -m "feat: add radius and tags to location model"
```

### Task 15: character_data store

**Files:**
- Create: `src/main/kotlin/com/canefe/story/storage/CharacterDataDocument.kt`
- Create: `src/main/kotlin/com/canefe/story/storage/mongo/MongoCharacterDataStorage.kt`
- Test: `src/test/kotlin/com/canefe/story/storage/CharacterDataDocumentTest.kt`

- [ ] **Step 1: Write failing serialization test**

Create the test asserting round-trip JSON of the snapshot model (uses kotlinx.serialization, no Mongo):

```kotlin
package com.canefe.story.storage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterDataDocumentTest {
    @Test
    fun roundTripsSnapshot() {
        val doc = CharacterDataDocument(
            id = "npc_x",
            needValues = mapOf("hunger" to 30.0),
            traits = listOf("Generous"),
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val s = json.encodeToString(CharacterDataDocument.serializer(), doc)
        val back = json.decodeFromString(CharacterDataDocument.serializer(), s)
        assertEquals(doc, back)
    }
}
```

- [ ] **Step 2: Run — expect FAIL (class missing)**

Run: `./gradlew test --tests "*CharacterDataDocumentTest*"`
Expected: FAIL (unresolved reference).

- [ ] **Step 3: Create the model**

Create `CharacterDataDocument.kt`:

```kotlin
package com.canefe.story.storage

import kotlinx.serialization.Serializable

@Serializable
data class OfferItemSpec(
    val tag: String? = null,
    val item: String? = null,
    val qty: Int = 1,
)

@Serializable
data class OfferSpec(
    val id: String,
    val wants: List<OfferItemSpec> = emptyList(),
    val gives: List<OfferItemSpec> = emptyList(),
    val whileSituation: String? = null,
)

@Serializable
data class ItemStack(val item: String, val qty: Int = 1)

@Serializable
data class CharacterDataDocument(
    val id: String,
    val offers: List<OfferSpec> = emptyList(),
    val startingInventory: List<ItemStack> = emptyList(),
    val traits: List<String> = emptyList(),
    val needValues: Map<String, Double> = emptyMap(),
    val statValues: Map<String, Double> = emptyMap(),
    val knownLocations: List<String> = emptyList(),
    val homeLocation: String? = null,
)
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew test --tests "*CharacterDataDocumentTest*"`
Expected: PASS.

- [ ] **Step 5: Create MongoCharacterDataStorage** (mirror `MongoCharacterStorage`)

```kotlin
package com.canefe.story.storage.mongo

import com.canefe.story.storage.CharacterDataDocument
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import kotlinx.serialization.json.Json
import java.util.logging.Logger

class MongoCharacterDataStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) {
    private val collection get() = mongoClient.getCollection("character_data")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun findById(id: String): CharacterDataDocument? {
        val doc = collection.find(Filters.eq("_id", id)).first() ?: return null
        return deserialize(doc)
    }

    fun save(record: CharacterDataDocument) {
        val jsonStr = json.encodeToString(CharacterDataDocument.serializer(), record)
        val doc = Document.parse(jsonStr)
        doc["_id"] = record.id
        doc.remove("id")
        collection.replaceOne(Filters.eq("_id", record.id), doc, ReplaceOptions().upsert(true))
    }

    /** Read-modify-write helper so command handlers can update one field. */
    fun update(id: String, mutate: (CharacterDataDocument) -> CharacterDataDocument) {
        val current = findById(id) ?: CharacterDataDocument(id = id)
        save(mutate(current))
    }

    private fun deserialize(doc: Document): CharacterDataDocument? = try {
        val id = doc.getString("_id") ?: return null
        doc["id"] = id
        json.decodeFromString(CharacterDataDocument.serializer(), doc.toJson())
    } catch (e: Exception) {
        logger.warning("Failed to deserialize character_data: ${e.message}")
        null
    }
}
```

- [ ] **Step 6: Wire it into the plugin**

Find where `MongoCharacterStorage`/`CharacterRegistry` are constructed (`grep -rn "MongoCharacterStorage(" src/main/kotlin`). Construct `MongoCharacterDataStorage` alongside and expose it on `Story` (the plugin) as a property `characterDataStorage`, mirroring how other stores are exposed.

- [ ] **Step 7: Compile + test**

Run: `./gradlew compileKotlin && ./gradlew test --tests "*CharacterDataDocumentTest*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/canefe/story/storage/CharacterDataDocument.kt src/main/kotlin/com/canefe/story/storage/mongo/MongoCharacterDataStorage.kt src/test/kotlin/com/canefe/story/storage/CharacterDataDocumentTest.kt
git commit -m "feat: character_data snapshot model and Mongo store"
```

### Task 16: Authoring intents

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt`
- Modify: `src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt`

- [ ] **Step 1: Add intent data classes**

In `DomainEvents.kt`, mirroring `NpcSpawnIntent` (each implements `SerializableStoryEvent` and overrides `eventType`):

```kotlin
@Serializable
data class LocationSpawnIntent(
    val id: String,
    val instanceName: String,
    val x: Double, val y: Double, val z: Double,
    val radius: Double = 8.0,
    val tags: String = "", // comma-joined
) : SerializableStoryEvent { override val eventType: String get() = "location.spawn" }

@Serializable
data class NpcSetOffersIntent(
    val characterId: String,
    val name: String,
    val offers: String, // JSON array string
) : SerializableStoryEvent { override val eventType: String get() = "npc.set_offers" }

@Serializable
data class NpcGiveItemIntent(
    val characterId: String, val name: String, val item: String, val qty: Int,
) : SerializableStoryEvent { override val eventType: String get() = "npc.give_item" }

@Serializable
data class NpcGiveTraitIntent(
    val characterId: String, val name: String, val trait: String,
) : SerializableStoryEvent { override val eventType: String get() = "npc.give_trait" }

@Serializable
data class NpcSetNeedIntent(
    val characterId: String, val name: String, val need: String, val value: Double,
) : SerializableStoryEvent { override val eventType: String get() = "npc.set_need" }

@Serializable
data class NpcSetStatIntent(
    val characterId: String, val name: String, val stat: String, val value: Double,
) : SerializableStoryEvent { override val eventType: String get() = "npc.set_stat" }

@Serializable
data class NpcKnowIntent(
    val characterId: String, val locationId: String,
) : SerializableStoryEvent { override val eventType: String get() = "npc.know" }

@Serializable
data class NpcHomeIntent(
    val characterId: String, val locationId: String,
) : SerializableStoryEvent { override val eventType: String get() = "npc.home" }
```

> The wire `data` field names must match what story-go reads from `msg.Data` (Task 11) and re-publishes. story-go reads e.g. `msg.Data["item"]`, `msg.Data["need"]`, `msg.Data["value"]`, `msg.Data["location_id"]`. kotlinx.serialization uses the Kotlin property name by default — so `locationId` serializes as `"locationId"`, NOT `"location_id"`. **Add `@SerialName` to align:** annotate `locationId` with `@SerialName("location_id")`, `instanceName` with `@SerialName("instance_name")`, `characterId` with `@SerialName("character_id")`. Verify each field name against the story-go read sites in Task 11/13.

- [ ] **Step 2: Add `@SerialName` import and annotations**

Add `import kotlinx.serialization.SerialName` and annotate the snake_case wire fields as noted above.

- [ ] **Step 3: Add encode arms in WebSocketTransport.serializeEvent**

In the `when (event)` (~line 165), add:

```kotlin
        is LocationSpawnIntent -> json.encodeToJsonElement(event)
        is NpcSetOffersIntent -> json.encodeToJsonElement(event)
        is NpcGiveItemIntent -> json.encodeToJsonElement(event)
        is NpcGiveTraitIntent -> json.encodeToJsonElement(event)
        is NpcSetNeedIntent -> json.encodeToJsonElement(event)
        is NpcSetStatIntent -> json.encodeToJsonElement(event)
        is NpcKnowIntent -> json.encodeToJsonElement(event)
        is NpcHomeIntent -> json.encodeToJsonElement(event)
```

(No decode arms needed — these are plugin→go only.)

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt
git commit -m "feat: authoring bridge intents with snake_case wire names"
```

### Task 17: Location tag/radius commands

**Files:**
- Create: `src/main/kotlin/com/canefe/story/command/story/location/TagLocationCommand.kt`
- Create: `src/main/kotlin/com/canefe/story/command/story/location/RadiusLocationCommand.kt`
- Modify: `src/main/kotlin/com/canefe/story/command/story/location/LocationCommand.kt`

- [ ] **Step 1: Write TagLocationCommand** (model on `CreateLocationCommand` + `MoveLocationCommand`)

`/story location tag <name> add|remove <tag>`: load the `StoryLocation` via `commandUtils.locationManager`, mutate `tags`, `saveLocation`, then emit `LocationSpawnIntent` with the location's coords + joined tags so the live sim updates.

```kotlin
package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.bridge.LocationSpawnIntent
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.MultiLiteralArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class TagLocationCommand(
    private val plugin: Story,
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("tag")
            .withArguments(
                TextArgument("location_name"),
                MultiLiteralArgument("op", "add", "remove"),
                StringArgument("tag"),
            )
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val name = args["location_name"] as String
                val op = args["op"] as String
                val tag = args["tag"] as String
                val loc = commandUtils.locationManager.getLocation(name)
                    ?: return@PlayerCommandExecutor player.sendError("No location '$name'.")
                if (op == "add") { if (!loc.tags.contains(tag)) loc.tags.add(tag) }
                else loc.tags.remove(tag)
                commandUtils.locationManager.saveLocation(loc)
                emitLocation(plugin, loc)
                player.sendSuccess("Location <gold>'$name'</gold> tags: ${loc.tags.joinToString(", ")}")
            })
}
```

Add a shared helper in `LocationCommandUtils` (or a top-level fun in this package) `emitLocation(plugin, loc)` that emits `LocationSpawnIntent` from a `StoryLocation` (id = name, coords from `bukkitLocation`, radius, joined tags). Reuse it from RadiusLocationCommand and the existing create/move commands later if desired.

> Verify method names: `locationManager.getLocation(name)` and `saveLocation(loc)` — confirm exact names via `grep -n "fun getLocation\|fun saveLocation\|fun createLocation" src/main/kotlin/com/canefe/story/location/LocationManager.kt`.

- [ ] **Step 2: Write RadiusLocationCommand**

`/story location radius <name> <radius>`: set `loc.radius`, save, emit.

- [ ] **Step 3: Register subcommands in LocationCommand**

In `LocationCommand.kt`, add `.withSubcommand(TagLocationCommand(plugin, commandUtils).getCommand())` and the radius one. Confirm `LocationCommand` has access to `plugin` (the `Story` instance); if not, thread it in.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/canefe/story/command/story/location/
git commit -m "feat: /story location tag and radius subcommands with live sim push"
```

### Task 18: NPC authoring commands

**Files:**
- Create: `src/main/kotlin/com/canefe/story/command/story/npc/NpcAuthorCommand.kt`
- Modify: `src/main/kotlin/com/canefe/story/command/story/StoryCommand.kt` (register under `getNPCCommand()` or as a new `getCharCommand()` sibling)

> Targeting uses `char_id`. Use an `ArgumentSuggestions`-backed `StringArgument("char_id")` whose suggestions come from `plugin.characterRegistry` (id with name hint).

- [ ] **Step 1: Write the command tree**

Create `NpcAuthorCommand.kt` exposing subcommands `offer`, `give`, `trait`, `need`, `stat`, `know`, `home`. Each: resolve `char_id` → `plugin.characterRegistry.getById(charId)` (for the display name), persist via `plugin.characterDataStorage.update(charId) { ... }`, then `plugin.eventBus.emit(<Intent>)` for live push. Example `need` and `give`:

```kotlin
package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.NpcGiveItemIntent
import com.canefe.story.bridge.NpcSetNeedIntent
import com.canefe.story.storage.ItemStack
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class NpcAuthorCommand(private val plugin: Story) {
    private fun charIdArg() =
        StringArgument("char_id").replaceSuggestions(ArgumentSuggestions.strings { _ ->
            plugin.characterRegistry.allIds().toTypedArray() // add allIds() if absent; see step 2
        })

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("npc")
            .withPermission("story.npc")
            .withSubcommand(needCmd())
            .withSubcommand(giveCmd())
            // ... trait, stat, know, home, offer

    private fun needCmd() = CommandAPICommand("need")
        .withArguments(charIdArg(), StringArgument("need"), DoubleArgument("value"))
        .executesPlayer(PlayerCommandExecutor { player, args ->
            val id = args["char_id"] as String
            val rec = plugin.characterRegistry.getById(id)
                ?: return@PlayerCommandExecutor player.sendError("No character id '$id'.")
            val need = args["need"] as String
            val value = args["value"] as Double
            plugin.characterDataStorage.update(id) { it.copy(needValues = it.needValues + (need to value)) }
            plugin.eventBus.emit(NpcSetNeedIntent(characterId = id, name = rec.name, need = need, value = value))
            player.sendSuccess("Set ${rec.name} need <gold>$need=$value</gold>.")
        })

    private fun giveCmd() = CommandAPICommand("give")
        .withArguments(charIdArg(), StringArgument("item"), IntegerArgument("qty", 1))
        .executesPlayer(PlayerCommandExecutor { player, args ->
            val id = args["char_id"] as String
            val rec = plugin.characterRegistry.getById(id)
                ?: return@PlayerCommandExecutor player.sendError("No character id '$id'.")
            val item = args["item"] as String
            val qty = args["qty"] as Int
            plugin.characterDataStorage.update(id) {
                it.copy(startingInventory = it.startingInventory + ItemStack(item, qty))
            }
            plugin.eventBus.emit(NpcGiveItemIntent(characterId = id, name = rec.name, item = item, qty = qty))
            player.sendSuccess("Gave ${rec.name} <gold>$qty× $item</gold>.")
        })
    // trait/stat/know/home identical in shape; offer parses the wants/gives mini-syntax (Task 19).
}
```

- [ ] **Step 2: Add `allIds()` to CharacterRegistry if missing**

`grep -n "fun allIds\|val byId" src/main/kotlin/com/canefe/story/api/character/CharacterRegistry.kt`. If absent, add `fun allIds(): Set<String> = byId.keys`. Optionally format suggestions as `"$id ($name)"` and strip the suffix on parse — but to keep parsing trivial, suggest raw ids and show name in tooltip via `ArgumentSuggestions.stringsWithTooltips`.

- [ ] **Step 3: Implement trait/stat/know/home subcommands** (same shape, matching intents and `characterDataStorage.update` mutations: `traits + trait`, `statValues + (stat to value)`, `knownLocations + loc`, `homeLocation = loc`).

- [ ] **Step 4: Register the command**

In `StoryCommand.kt`, add `.withSubcommand(NpcAuthorCommand(plugin).getCommand())` to the `story` root (or merge into the existing NPC command). Avoid colliding with the existing `getNPCCommand()` literal `npc` — if it already uses `npc`, nest these under it or pick `npca`/extend the existing one. Confirm with `grep -n "CommandAPICommand(\"npc\")" src/main/kotlin`.

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/canefe/story/command/story/npc/ src/main/kotlin/com/canefe/story/command/story/StoryCommand.kt src/main/kotlin/com/canefe/story/api/character/CharacterRegistry.kt
git commit -m "feat: /story npc authoring commands (need/give/trait/stat/know/home)"
```

### Task 19: Offer command with wants/gives mini-syntax

**Files:**
- Modify: `src/main/kotlin/com/canefe/story/command/story/npc/NpcAuthorCommand.kt`
- Create: `src/test/kotlin/com/canefe/story/command/story/npc/OfferSpecParseTest.kt`

`/story npc offer <char_id> add <id> wants <spec...> gives <spec...> [while <situation>]` where a spec token is `tag:currency:3` or `item:wheat:1`.

- [ ] **Step 1: Write failing parse test**

```kotlin
package com.canefe.story.command.story.npc

import com.canefe.story.storage.OfferItemSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class OfferSpecParseTest {
    @Test
    fun parsesTagAndItemTokens() {
        assertEquals(OfferItemSpec(tag = "currency", qty = 3), parseOfferToken("tag:currency:3"))
        assertEquals(OfferItemSpec(item = "wheat", qty = 1), parseOfferToken("item:wheat:1"))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew test --tests "*OfferSpecParseTest*"`
Expected: FAIL (unresolved `parseOfferToken`).

- [ ] **Step 3: Implement parseOfferToken** (top-level fun in the npc package)

```kotlin
fun parseOfferToken(token: String): OfferItemSpec {
    val parts = token.split(":")
    require(parts.size == 3) { "Expected kind:id:qty, got '$token'" }
    val (kind, id, qtyStr) = parts
    val qty = qtyStr.toIntOrNull() ?: error("Bad qty in '$token'")
    return when (kind) {
        "tag" -> OfferItemSpec(tag = id, qty = qty)
        "item" -> OfferItemSpec(item = id, qty = qty)
        else -> error("Unknown spec kind '$kind' in '$token'")
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew test --tests "*OfferSpecParseTest*"`
Expected: PASS.

- [ ] **Step 5: Wire the offer subcommand**

Add `offerCmd()` to `NpcAuthorCommand`. Use `GreedyStringArgument("spec")` and split into `wants`/`gives`/`while` sections by keyword, mapping tokens through `parseOfferToken`. Build an `OfferSpec`, persist via `characterDataStorage.update(id) { it.copy(offers = it.offers.filter { o -> o.id != offerId } + newOffer) }`, then emit `NpcSetOffersIntent` with the **full** offers list JSON (so the sim's `set_offers` replaces the whole list — matching the sim apply which inserts a fresh `Offers { list }`). Serialize with the same `Json` config.

- [ ] **Step 6: Compile + test**

Run: `./gradlew compileKotlin && ./gradlew test --tests "*OfferSpecParseTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/canefe/story/command/story/npc/ src/test/kotlin/com/canefe/story/command/story/npc/OfferSpecParseTest.kt
git commit -m "feat: /story npc offer with wants/gives mini-syntax"
```

---

## Phase 4 — Verification

### Task 20: Cross-repo build + manual acceptance checklist

**Files:** none (verification only)

- [ ] **Step 1: Build all three repos**

```bash
cd /Users/canefe/Projects/personal/story-sim && cargo test --bin story-sim
cd /Users/canefe/Projects/personal/story-go && go build ./... && go test ./...
cd /Users/canefe/Projects/personal/Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew test
```
Expected: all PASS.

- [ ] **Step 2: Wire-name consistency audit**

Confirm each field name matches across the three layers (this is the most likely source of silent failures):
- `character_id`, `name`, `item`, `qty`, `trait`, `need`, `stat`, `value`, `location_id`, `id`, `instance_name`, `radius`, `tags`, `offers`.
Check: Kotlin `@SerialName` (Task 16) → story-go `msg.Data[...]` reads (Task 11) and re-publish keys → Rust `msg.str_field(...)`/`f32_field(...)` reads (Tasks 2-8). List each and verify by grep.

- [ ] **Step 3: Manual acceptance (running stack)**

With story-sim, story-go, and a Paper server running + connected, reproduce the village demo per the spec's "Acceptance" section:
1. `/story location create market_square`, `/story location tag market_square add commerce`
2. `/story location create temple_grounds`, `/story location tag temple_grounds add alms_eligible`
3. `/story char spawn` a baker; `/story npc give <id> bread 12`; two `/story npc offer` commands; `/story npc home <id> market_square`
4. Spawn Caelan; `give bread 6`, `trait <id> Generous`, `home temple_grounds`
5. Spawn Maja; `stat sociability 0.7`, `need hunger 30`, `know market_square`, `know temple_grounds`
Observe in story-go logs: `spawn_location`, `set_offers`, `give_item`, etc. published. Restart the sim; confirm `[Sim] reseeded N locations` and `[Sim] reseeded character_data for N NPCs`, and the chain plays out.

- [ ] **Step 4: Commit any fixes found during the audit, then done.**

---

## Self-review notes

- **Spec coverage:** locations+tags/radius (Tasks 14,17), offers (3,11,13,19), items (4,11,13,18), traits (4,11,13,18), needs/stats absolute (5,6,11,13,18), knowledge (7,8,11,13,18), home (7,8,11,13,18), persistence to Mongo (10,13,15), live push (17,18,19), re-seed on sim.init (13), char_id targeting (7,8,18), snapshot/absolute semantics (5). All covered.
- **Known follow-through for implementer (not placeholders, but require reading real code):** `Offer::from_authoring_json` (Task 3) and the Rust system test scaffolding (Tasks 5,7) must be completed against the actual `Offer`/`Needs`/`Stats` definitions and the crate's existing test patterns. These are flagged inline with explicit instructions.
- **Type consistency:** intent property names use `@SerialName` to emit snake_case matching go reads and rust fields; `set_offers` carries the FULL offers list as a JSON string at every layer; `give_item` is additive-on-empty (safe under re-seed).
