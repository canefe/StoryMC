# In-Game Sim Authoring Commands — Design

**Date:** 2026-05-22
**Status:** Approved, ready for implementation planning

## Goal

Let a builder author the village-scenario setup that
`story-sim/packs/BaseGame/lua/autorun/spawn_village_demo.lua` hard-codes —
locations (with tags/radius), vendor offers, item grants, traits, need/stat
overrides, location knowledge, home assignment — **dynamically, in-game, via
commands**, instead of editing a Lua file.

The Lua demo proves the goal-chaining stack works (hungry villager → market →
alms at temple). This feature makes that same authoring author-driven and
persistent.

## Background: current state

### What already reaches the sim from a command
Only **character spawn**. `/story char spawn <Template> [count]`
(`CharCommand.kt`) → chargen generates data → plugin writes `characters` Mongo
doc → emits `NpcSpawnIntent` → story-go `ForwardSpawnToSim` upserts
`character_positions` and publishes `spawn_entity` on NATS → sim's
`spawn_entity` stream handler queues a `SpawnHumanoidRequest` →
`process_spawn_queue` builds the `NpcBundle` with `ExternalId` (= `characters._id`).

### What the sim can do but is NOT command-reachable
`spawn_village_demo.lua` uses sim-side handles: `World.SpawnLocation`,
`:set_offers`, `:give_item`, `:give_trait`, `:modify_need`, `:modify_stat`,
`:know_location`, `:assign_home_location`. These all exist in
`story-sim/src/plugins/lua_world_api.rs` as **Lua command queues**
(`spawn_locations`, `set_offers`, `know_locations`, `assign_home_locations`,
etc.), drained by Bevy systems each frame. But the **stream handlers** (the
message-driven path story-go can reach,
`story-sim/src/plugins/stream_handlers/`) expose only `spawn_entity` and
`spawn_affordance`. Everything else is Lua-only.

### Existing location subsystem (the migration concern)
`/story location` already exists with a full command tree (`create`, `update`,
`move`, `teleport`, `find`, `call`) backed by `LocationManager`,
`StoryLocation`, and three storage backends (SQLite/YAML/Mongo →
`locations` collection). But:
- It is Minecraft/narrative-centric: `name` (slash-path hierarchy),
  `description`, `parentLocationName`, `allowedNPCs`, `randomPathingAction`.
- It **lacks the two fields the sim's goal-chaining gates on**: `radius` and
  `tags` (e.g. `commerce`, `alms_eligible`).
- It **never reaches the sim** — there is no `locations` → sim bridge today.

## Key decisions

1. **Persistence: Mongo, re-seeded on `sim.init`.** Authored content is durable
   and comes back after a sim world wipe, exactly like characters/positions do.
2. **Locations: extend the existing `locations` collection.** Add `radius` +
   `tags` to `StoryLocation`/`LocationDocument`. `/story location` stays the one
   command surface for locations. No parallel location concept.
3. **NPC sim-attributes: new `character_data` collection**, keyed by
   `characterId` (= `characters._id`). `characters` stays identity-only;
   `character_positions` stays positions; `character_data` holds sim setup. This
   mirrors the existing separate-keyed-collection pattern.
4. **Apply timing: live push + persist.** Every authoring command writes Mongo
   AND immediately emits the bridge message so the running sim updates without a
   restart (same model as `/story char` spawn). On `sim.init`, story-go
   re-applies everything.
5. **NPC targeting uses `char_id`, not name.** Names are display strings;
   `characterId` is the stable key used by `characters._id`,
   `character_positions._id`, and the sim's `ExternalId`. The id flows straight
   through plugin → story-go → sim with no name→id lookup. Tab-completion
   resolves ids from the character registry, hinting the name (e.g.
   `npc_erik_001 (Erik)`). **Locations** still target by `name` (the `locations`
   collection keys on `name`), so `know`/`home` take a `locationName`.

## Architecture

```
Plugin command (Kotlin)
   ├─ writes/updates Mongo (source of truth: locations | character_data)
   └─ emits bridge intent ──► story-go forwarder ──► NATS ──► story-sim stream handler ──► live apply
                                   │
                     (also re-applies all of it on sim.init re-seed)
```

Three layers, each mirroring the proven `/story char spawn → spawn_entity` path.

### Layer 1 — Plugin (Kotlin, repo: Story)

Command surface:

**Locations — extend `/story location`:**
```
/story location create <name> [context]        # EXISTING, unchanged (captures x/y/z/world)
/story location tag    <name> add|remove <tag> # NEW
/story location radius <name> <radius>         # NEW
```

**NPC sim-attributes — new `/story npc ...` (sibling to `/story char`):**
```
/story npc offer <char_id> add <id> wants <spec...> gives <spec...> [while <situation>]
/story npc offer <char_id> remove <id>
/story npc give  <char_id> <item> <qty>
/story npc trait <char_id> <Trait>
/story npc need  <char_id> <need> <delta>      # e.g. hunger -70
/story npc stat  <char_id> <stat>  <delta>     # e.g. sociability 0.2
/story npc know  <char_id> <locationName>
/story npc home  <char_id> <locationName>
```

Offer `wants`/`gives` mini-syntax mirrors the Lua schema:
`tag:currency:3` → `{tag="currency", qty=3}`, `item:wheat:1` → `{item="wheat", qty=1}`.

Each command: validate args → upsert the matching field in Mongo
(`locations` for location cmds, `character_data` for npc cmds) → emit the bridge
intent for live apply. `char_id` args use registry-backed autocomplete with
name hints.

### Layer 2 — story-go forwarder (Go)

- New forwarders that receive the plugin intents and re-publish them as NATS
  messages to the sim (`story-sim.inbound`), parallel to `ForwardSpawnToSim`.
- Extend `handleSimInit` re-seed loop: after spawning entities, read `locations`
  (→ `spawn_location` per location) and `character_data` (→ `set_offers`,
  `give_item`, `give_trait`, `set_need`, `set_stat`, `know_location`,
  `assign_home` per NPC). Re-seed order: locations first (offers/home/knowledge
  reference them), then per-NPC attributes.

### Layer 3 — story-sim stream handlers (Rust)

New thin stream handlers in `story-sim/src/plugins/stream_handlers/`, each
adapting an existing Lua command queue in `lua_world_api.rs`:

| Message type     | Maps to Lua queue / system        |
|------------------|-----------------------------------|
| `spawn_location` | `spawn_locations`                 |
| `set_offers`     | `set_offers`                      |
| `give_item`      | (item grant queue)                |
| `give_trait`     | (trait queue)                     |
| `set_need`       | (need modify) — resolve by ExternalId |
| `set_stat`       | (stat modify) — resolve by ExternalId |
| `know_location`  | `know_locations`                  |
| `assign_home`    | `assign_home_locations`           |

NPC-targeted handlers resolve the entity by `ExternalId == character_id`, the
same lookup `position_sync` already uses. Handlers are adapters only — no new
sim behavior logic; they reuse the queues the Lua handles already feed.

## Data model

### `locations` (extend existing `LocationDocument` / `StoryLocation`)
Add:
- `radius: Double` (default e.g. `8.0`)
- `tags: List<String>` (default empty)

Sim `id` is derived from the location `name` (the bridge already carries a
string id; the existing `name` serves as it). Migration backfills `radius`
default and empty `tags` for existing docs — handled the same way
`MongoLocationStorage.documentToLocationDocument` already tolerates missing
fields (defaulting on read).

### `character_data` (new, keyed by `_id = characterId`)
```
{
  _id: "<characterId>",
  offers: [ { id, wants: [{tag|item, qty}], gives: [{item, qty}], whileSituation? } ],
  startingInventory: [ { item, qty } ],
  traits: [ "Generous", ... ],
  needOverrides: { hunger: -70.0, ... },
  statOverrides: { sociability: 0.2, pride: -0.3, ... },
  knownLocations: [ "market_square", "temple_grounds" ],
  homeLocation: "market_square"
}
```

## Acceptance: reproduce the demo in-game

The whole `spawn_village_demo.lua` is reproducible via commands:
1. `/story location create market_square` → `tag market_square add commerce`
2. `/story location create temple_grounds` → `tag temple_grounds add alms_eligible`
3. `/story location create tavern_common_room`
4. Spawn Baker via `/story char spawn` (existing); then
   `npc give <tobin_id> bread 12`, `npc offer <tobin_id> add tobin_bread_for_coin wants tag:currency:3 gives item:bread:1 while manning_stall`,
   `npc offer <tobin_id> add tobin_bread_for_wheat wants item:wheat:1 gives item:bread:2 while manning_stall`,
   `npc home <tobin_id> market_square`
5. Spawn Caelan; `npc give <caelan_id> bread 6`, `npc trait <caelan_id> Generous`, `npc home <caelan_id> temple_grounds`
6. Spawn Maja; `npc stat <maja_id> sociability 0.2`, `npc stat <maja_id> pride -0.3`,
   `npc need <maja_id> hunger -70`, `npc know <maja_id> market_square`, `npc know <maja_id> temple_grounds`

After this, the goal chain plays out identically to the Lua demo, and survives a
sim restart via the `sim.init` re-seed.

## Out of scope (YAGNI)

- Editing/removing locations through the sim path beyond tag/radius (use existing
  `/story location update|move`).
- Authoring affordances (already has `spawn_affordance`); not part of the demo
  recipe.
- A GUI/visual authoring tool — commands only.
- Bulk import/export of authored worlds.

## Risks / notes

- **`locationName` mismatch:** chargen/`CharCommand` never set `characters.locationName`,
  and `resolver.go` decodes it but the spawn gate is really `character_positions`.
  This feature doesn't change that; `know`/`home` reference `locations.name`,
  independent of `characters.locationName`.
- **Race values:** `tags`/offers don't touch race, so no `RaceRegistry` impact.
- **Transport is NATS** (`story-sim.inbound` / `story-sim.events`), not Redis —
  older notes referencing Redis are stale.
