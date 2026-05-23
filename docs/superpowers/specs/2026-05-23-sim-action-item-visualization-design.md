# Sim Action & Item-Transfer Visualization — Design

**Date:** 2026-05-23
**Repos:** story-sim (Rust/Bevy), story-go (Go relay), Story/StoryMC (Kotlin Paper), StoryClient (Fabric/Kotlin)
**Supersedes the open decisions in:** `StoryClient/docs/handoff-sim-action-item-visualization.md`

## Goal

Make the simulation legible in-world. Two client-rendered visualizations:

1. **Action label above the head** — a fading text label over each sim-driven NPC
   showing its current behavior ("Buying", "Heading to market_square", "Begging",
   "Eating"). Rides the **existing** perception-popup path with a new
   `PopupType.ACTION`. No new channel, no new renderer.
2. **Item-transfer holograms** — when items change hands, a client-side floating
   `ItemStack` arcs from giver to receiver (spin + scale-in + fade, qty badge),
   over its own new `story:item_transfer` channel.

Both are **rendered entirely client-side**. StoryMC renders nothing itself — it
relays/encodes only. story-go is a pure relay.

## Decisions (all resolved)

- **Scope:** Both features, full end-to-end across all repos.
- **Sim item-transfer emit point:** the single chokepoint `process_transfer_item_queue`
  in `lua_world_api.rs` (where `TransferItemRequest`s are actually applied). This
  covers trade settle AND `give_item_to` in one place — no per-call-site backfill.
- **Item map home:** `items.yml` in StoryMC, loaded by `ConfigService`, exposed via
  a new `ItemMapService`. `material` + optional `customModelData` per sim item, plus
  a `default` fallback.
- **Holo style:** arc (parabola) giver→receiver over ~0.8s, spin, scale-in then
  fade-out; **qty badge** (`xN`) when qty>1; coin uses its mapped material, no
  special "coins fly" effect (one render path).
- **Action label copy (the proposed set):**
  - `buy_item` → `"Buying"`
  - `head_to_known_location` → **binding-aware** `"Heading to {instance_name}"`
  - `request_alms` → `"Begging"`
  - `give_alms` → `"Giving alms"`
  - `eat_bread` → `"Eating"`
  - `cooperative_eating` → `"Eating together"`
  - `approach_partner` → `"Approaching"`
  - `cautious_retreat` → `"Backing away"`
  - `mental_break_panic` → `"Panicking"`
  - Silent (no `BEHAVIOR.Label`): `idle_stand`, `idle_look_around`
- **Empty/idle handling:** explicit clear — when the resolved label goes empty or
  changes to a silent behavior, MC sends an ACTION popup with an **empty label**;
  the client treats empty as "clear this NPC's action popup" (fade immediately).
- **Label resolution precedence:** dynamic per-action label
  (`CurrentAction.lua_state["label"]`) wins over static `BehaviorDef.label`. This is
  what enables binding-aware movement labels.

---

## Feature A — Action Labels

### A1. story-sim: dynamic + static label model

**Static label on the behavior def.** A behavior opts in by setting
`BEHAVIOR.Label` in its pack Lua. No label = silent (free; no denylist).

- `BehaviorDef` (`src/resources/mod.rs`, struct at ~line 673): add
  `pub label: Option<String>,` after `description`.
- Loader (`src/plugins/definition_loader/behaviors.rs`, `parse_one`): add
  `label: extract_optional_string(&table, "Label"),` to the `BehaviorDef { ... }`
  literal (~line 195), mirroring how `description` is read.

**Dynamic label via per-action scratch.** A binding-aware behavior writes its
runtime label into action scratch:
```lua
char.action.set("label", "Heading to " .. (target.instance_name or "somewhere"))
```
This persists on `CurrentAction.lua_state` (`src/components/behavior.rs:106`), which
the broadcast system can read. `head_to_known_location.lua` sets this in `OnStart`
(and re-sets in `OnTick` if the target drifts under `filter="nearest"`); its static
`BEHAVIOR.Label` is a fallback `"Heading somewhere"` for the brief window before a
target is resolved.

**Authoring per behavior (pack Lua, `packs/BaseGame/lua/defs/behaviors/*.lua`):**
add `BEHAVIOR.Label = "..."` per the decided copy set above. `head_to_known_location`
gets both the static fallback AND the dynamic `char.action.set("label", ...)`.

### A2. story-sim: put actionId + actionLabel on npc.state

`broadcast_entity_states` (`src/plugins/entity_state_broadcast.rs`, system at
~line 28) currently queries name/transform/external-id/stats and emits the
`npc.state` JSON (~line 63). Add:

- **Query:** `Option<&components::behavior::ActiveActions>` and
  `Option<&components::blackboard::Blackboard>`.
- **Res:** `Res<crate::resources::BehaviorRegistry>` (read access for def lookup).
- **Resolution helper** (a free fn in this module):
  1. Pick the current `CurrentAction`: highest-priority occupied `ActiveActions`
     slot (Interaction > Movement > Passive ordering, or by `ActionPriority`).
     Fallback: `Blackboard.active_behavior`.
  2. `action_id` = that `CurrentAction.action_id` (or `""`).
  3. `behavior_id` = that `CurrentAction.behavior_id` (or `Blackboard.active_behavior`).
  4. `label`:
     - dynamic first: `current_action.lua_state.get("label")` if it's a
       `ActionStateValue::String`;
     - else static: `behavior_registry.get(behavior_id).and_then(|d| d.label.clone())`;
     - else `None`.
- **Payload:** add to the `"data"` object:
  - `"actionId": action_id` (string, may be `""`)
  - `"actionLabel": label` (string or JSON null when no label)

  Emit them on every tick (no sim-side throttle — MC change-diffs).

### A3. story-go: relay (no change)

`npc.state` is already handled by `internal/sim/handler.go::handleNpcState` and
re-broadcast verbatim. New optional fields ride along since the payload is
forwarded as the parsed `BridgeMessage.data` JSON. **No story-go change for
Feature A.**

### A4. StoryMC: extend NpcStateIntent + change-diff + send ACTION popup

- **DTO** `bridge/DomainEvents.kt` `NpcStateIntent` (~line 232): add
  `val actionId: String? = null` and `val actionLabel: String? = null`.
  kotlinx.serialization auto-maps by name; decode in `WebSocketTransport.kt`
  (`"npc.state" -> json.decodeFromString<NpcStateIntent>(data)`) needs no change.
- **PopupType** `perception/PerceptionBroadcaster.kt` (enum ~line 226): add
  `ACTION(5)`. Add `fun sendActionPopup(npcUuid: UUID, label: String)` that calls
  the existing `broadcastPerceptionPopup(npcUuid, label, PopupType.ACTION)`.
- **Change-diff** in `bridge/IntentExecutor.kt::executeNpcStateIntent` (~line 354):
  - Add a module-level `private val lastActionLabel = ConcurrentHashMap<String, String>()`
    keyed by `characterId`.
  - Normalize incoming: `val label = intent.actionLabel?.takeIf { it.isNotBlank() } ?: ""`.
  - Compare to `lastActionLabel[characterId]` (default `""`). If unchanged, skip.
  - If changed: store, resolve `npc.entity?.uniqueId`, call
    `plugin.perceptionBroadcaster.sendActionPopup(uuid, label)`. An empty `label`
    is the explicit clear (client interprets empty as "clear").

### A5. StoryClient: ACTION popup variant

- `perception/NpcPerceptionPayload.kt` `PopupType` enum (currently 0–4): add
  `ACTION(5)`. (Ordinal must match the MC byte = 5.)
- `perception/PerceptionPopupRenderer.kt`: add a `PopupStyle` for `PopupType.ACTION`
  (e.g. icon `"➤"` / a neutral action color like `0xC8E6C9`).
- **Empty-label clear:** in `onPerception`, if `type == ACTION && label.isBlank()`,
  remove any queued ACTION popup(s) for that `npcUuid` instead of enqueuing — so a
  clear fades the current label promptly rather than appending a blank.

No new channel, no new payload, no new receiver — `story:npc_perception` already
carries it.

---

## Feature B — Item-Transfer Holograms

### B1. story-sim: emit npc.item_transfer at the transfer chokepoint

`process_transfer_item_queue` (`src/plugins/lua_world_api.rs`, ~line 2935; success
log ~line 3023) is where every `TransferItemRequest` is applied (remove from giver,
add to receiver). After a **successful** transfer, emit:

```json
{
  "type": "npc.item_transfer",
  "data": {
    "fromCharacterId": "<giver ExternalId>",
    "toCharacterId":   "<receiver ExternalId>",
    "item":            "<item_id>",
    "qty":             <u32>,
    "reason":          "trade" | "gift" | "give"
  },
  "timestamp": 0,
  "source": "story-sim"
}
```

Details:
- The system must resolve each endpoint's **character id** (the wire identity the
  frontend keys on). `TransferItemRequest` carries optional `from_character_id` /
  `to_character_id` and the entity names. Resolve character id from the entity's
  `ExternalId` component (query by the resolved entity), preferring the request's
  explicit `*_character_id` when present, else looking up `ExternalId` for the
  resolved entity. If either side has **no** `ExternalId` (a non-externally-tracked
  entity), **skip emission** — the MC side can't render a hologram for an entity it
  doesn't know.
- `reason`: thread a `reason` onto `TransferItemRequest` (default `"give"`).
  `trade_settle_system` sets `reason = "trade"` on the requests it pushes;
  `give_item_to` / gift paths set `"gift"` or `"give"`. (Add the field; existing
  push sites default it.)
- Publish via the same `MessageWriter<BridgePubSubOut>` pattern used by
  `broadcast_entity_states` (channel `"story-sim.events"`). The transfer-queue
  processor must gain a `MessageWriter<BridgePubSubOut>` system param (or emit via
  whatever event-writer the surrounding system already has — verify the call site is
  a Bevy system that can take the param; if it's a helper fn, pass the writer down).

### B2. story-go: forward npc.item_transfer

`npc.item_transfer` is **not** in `IsSimEvent` (`internal/sim/events.go`), so it
bypasses the sim handler and falls through to `hub.Broadcast()` — but **only when
the sim is active** (`internal/bridge/nats.go` gate). That's the desired behavior.

**Decision during impl:** add `NpcItemTransfer = "npc.item_transfer"` to
`pkg/events/events.go` for naming hygiene, but do **not** add it to `IsSimEvent`
(adding it there would route it into `Handle()`, which has no case for it and would
drop it). Net: no functional change needed; the constant is documentation. Confirm
on the wire that a transfer reaches a frontend during a trade.

### B3. StoryMC: items.yml + ItemMapService

- **Config** `Story/run/plugins/Story/items.yml`:
  ```yaml
  items:
    bread:  { material: BREAD }
    coin:   { material: GOLD_NUGGET }
    wheat:  { material: WHEAT }
  default: { material: PAPER }
  ```
  Ship a default `items.yml` via `saveResource` (mirror config.yml/prompts.yml).
- **`config/ItemMapService.kt`** (new): loads `items.yml`, exposes
  `fun renderSpecFor(simId: String): ItemRenderSpec` where
  `data class ItemRenderSpec(val material: Material, val customModelData: Int?)`.
  Unknown id → the `default` spec. Loaded/reloaded from `ConfigService.reload()`.
  Unit-tested for lookup + fallback + bad-material-name resilience.

### B4. StoryMC: item-transfer handler → story:item_transfer packet

- **Intent** `bridge/DomainEvents.kt`: add
  ```kotlin
  @Serializable
  data class NpcItemTransferIntent(
      val fromCharacterId: String,
      val toCharacterId: String,
      val item: String,
      val qty: Int,
      val reason: String = "give",
  ) : SerializableStoryEvent {
      override val eventType: String get() = "npc.item_transfer"
  }
  ```
- **Decode** `bridge/WebSocketTransport.kt` `deserializeEvent`: add
  `"npc.item_transfer" -> json.decodeFromString<NpcItemTransferIntent>(data)`.
- **Dispatch** `Story.kt` `initializeEventBus()`: add
  `eventBus.on<NpcItemTransferIntent> { IntentExecutor.executeItemTransferIntent(this, it) }`.
- **Handler** `bridge/IntentExecutor.kt::executeItemTransferIntent`:
  - resolve both NPCs via `resolveNPC(plugin, fromCharacterId/toCharacterId)`;
    require both `.entity` (skip if either missing).
  - `val spec = plugin.itemMapService.renderSpecFor(intent.item)`.
  - audience = players within render distance of either endpoint.
  - encode + send `story:item_transfer` (use a new
    `combat/packet/CombatPacketBridge`-style sender, or a small dedicated
    `bridge/ItemTransferPacketBridge.kt`).
- **Packet** `story:item_transfer` binary layout (DataOutputStream):
  ```
  int    fromEntityId
  int    toEntityId
  UTF    materialNamespacedId   (e.g. "minecraft:bread")
  int    customModelData        (-1 = none)
  short  qty
  byte   reasonOrdinal          (0=trade,1=gift,2=give)
  ```
  Send via PacketEvents `WrapperPlayServerPluginMessage("story:item_transfer", bytes)`
  to the audience (mirror `CombatPacketBridge.sendToAll`).

### B5. StoryClient: ItemTransferPayload + ItemHologramRenderer

- **`client/sim/ItemTransferPayload.kt`** (new) — mirror `combat/HitOutcomePayload.kt`:
  `CustomPayload.Id<...>(Identifier.of("story", "item_transfer"))`, a `CODEC`
  decoding the layout above into
  `data class ItemTransferPayload(fromEntityId, toEntityId, materialId: String,
  customModelData: Int, qty: Int, reasonOrdinal: Int)`.
- **Register** in `client/NPCMessageParserClient.kt` (alongside the perception/combat
  registrations ~line 167): `PayloadTypeRegistry.playS2C().register(...)` +
  `ClientPlayNetworking.registerGlobalReceiver(...) { payload, _ ->
  ItemHologramRenderer.spawn(payload) }`.
- **`client/sim/ItemHologramRenderer.kt`** (new) — registered into the
  `WorldRenderEvents.AFTER_ENTITIES` block (~line 374) next to the other renderers.
  - Holds a list/queue of active holos (`ItemHolo(from/to entity ids, ItemStack,
    qty, startMs)`); build `ItemStack` from `Identifier.tryParse(materialId)` →
    `Registries.ITEM.get(...)`, apply `customModelData` if ≥0; fallback to PAPER.
  - **Animation** over `DURATION_MS ≈ 800`: position = parabolic lerp between the two
    entities' interpolated positions (`prevPos + (pos-prev)*tickDelta`), with an apex
    lift; `scale` eases in (first ~15%) then out (last ~25%); a constant `spin` on Y;
    `alpha` fades in the final ~25%. Re-resolve both entity positions each frame so a
    moving NPC's holo tracks (cache entity-by-id like `PerceptionPopupRenderer`).
  - **Render** the item as a billboard/world item: use `MinecraftClient.itemRenderer`
    / `ItemRenderer.renderItem(..., ModelTransformationMode.GROUND, ...)` into the
    `WorldRenderContext` matrix stack (translate to interpolated holo pos − camera,
    apply spin/scale). If a direct item render proves fiddly, fall back to a
    camera-facing textured quad of the item's sprite — but prefer the real item model.
  - **Qty badge:** when `qty > 1`, draw `"x$qty"` via `TextRenderer` near the item
    (small, billboarded), reusing the outlined-text helper style from existing
    renderers.
  - Drop holos past `DURATION_MS`; cap concurrent holos (e.g. 32) to bound cost.

---

## End-to-end flows

**Action label:**
```
behavior Lua: static BEHAVIOR.Label and/or char.action.set("label", ...)
 → broadcast_entity_states: ActiveActions→CurrentAction.lua_state["label"] (dyn)
     else BehaviorRegistry.get(behavior_id).label (static)
 → npc.state {..., actionId, actionLabel}
 → story-go handleNpcState (verbatim relay)
 → MC executeNpcStateIntent: normalize, diff vs lastActionLabel[characterId]
 → on change: PerceptionBroadcaster.sendActionPopup(uuid, label)  [story:npc_perception, ACTION]
 → client story:npc_perception receiver → PerceptionPopupRenderer.onPerception(uuid,label,ACTION)
     (blank label = clear) → fades above head
```

**Item transfer:**
```
sim process_transfer_item_queue (applies TransferItemRequest)
 → emit npc.item_transfer {fromCharacterId,toCharacterId,item,qty,reason}
 → story-go: not in IsSimEvent → falls through to hub.Broadcast (sim active)
 → MC executeItemTransferIntent: resolve both entities + ItemMapService.renderSpecFor
 → encode story:item_transfer (fromEntityId,toEntityId,materialId,cmd,qty,reason)
 → sendToAll(nearby players)
 → client ItemTransferPayload receiver → ItemHologramRenderer.spawn
 → arc giver→receiver ~0.8s, spin + scale-in + fade, qty badge
```

---

## Error handling & edge cases

- **MC NPC not resolvable** (registry not ready, no entity): skip silently (both
  features already guard on `isNpcRegistryReady` / `npc.entity`).
- **Unmapped sim item:** `ItemMapService` returns `default` (PAPER) — never throws.
- **Bad material name in items.yml:** log a warning, fall back to PAPER for that id.
- **Transfer with an untracked endpoint (no ExternalId):** sim skips emission.
- **Self-transfer / qty 0:** sim skips emission (no-op exchange).
- **Label thrash:** MC change-diff per characterId prevents per-tick re-trigger.
- **Holo overload:** client caps concurrent holos and drops oldest.

## Testing

- **story-sim:** unit/integration test asserting the `npc.item_transfer` JSON shape
  on a simulated transfer; assert `npc.state` carries `actionLabel` for a behavior
  with a `BEHAVIOR.Label` and null for a silent one. Resolution-precedence test
  (dynamic over static).
- **StoryMC:** `ItemMapService` lookup + fallback + bad-material unit test. Wire test
  that `NpcItemTransferIntent` and the extended `NpcStateIntent` round-trip through
  `WebSocketTransport` deserialize (mirror existing `AuthoringIntentWireTest`).
  Change-diff test: same label twice → one send; change → send; blank → send (clear).
- **StoryClient:** `ItemTransferPayload` codec round-trip test; `PopupType.ACTION`
  ordinal == 5 guard.
- **Manual end-to-end:** run sim + go + server + client; trigger the village trade
  chain; observe action labels and a bread/coin holo arc on a trade.

## Build order (each independently testable)

1. **StoryMC item map** — `items.yml` + `ItemMapService` + unit test. No wire.
2. **sim npc.item_transfer** from `process_transfer_item_queue` (+ `reason` field on
   `TransferItemRequest`); verify JSON shape via test + wire watch during a trade.
3. **StoryMC item-transfer handler → story:item_transfer** (log on receipt; no client
   render yet).
4. **StoryClient item hologram** receiver + `ItemHologramRenderer` (visible payoff).
5. **Action label** end-to-end: `BehaviorDef.label` + loader; author `BEHAVIOR.Label`
   set; dynamic label in `head_to_known_location`; `actionId`/`actionLabel` on
   `npc.state`; MC change-diff + `sendActionPopup`; client `PopupType.ACTION` + style
   + empty-clear.
6. **story-go** `NpcItemTransfer` constant (hygiene) + wire confirmation.

## Key file references

- sim broadcast: `story-sim/src/plugins/entity_state_broadcast.rs` (~28, payload ~63)
- sim transfer chokepoint: `story-sim/src/plugins/lua_world_api.rs::process_transfer_item_queue` (~2935, log ~3023)
- sim trade settle (sets reason): `story-sim/src/plugins/behavior/trade/lifecycle.rs::trade_settle_system` (~176)
- sim BehaviorDef + loader: `story-sim/src/resources/mod.rs` (struct ~673), `story-sim/src/plugins/definition_loader/behaviors.rs` (`parse_one` ~195, `extract_optional_string` in `mod.rs`)
- sim CurrentAction.lua_state: `story-sim/src/components/behavior.rs:106`; location target name: `execution.rs:1388` (`instance_name`)
- behavior defs: `story-sim/packs/BaseGame/lua/defs/behaviors/*.lua`
- MC action sender (reuse): `Story/src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt` (enum ~226, send ~230)
- MC packet pattern: `Story/src/main/kotlin/com/canefe/story/combat/packet/CombatPacketBridge.kt` (sendToAll ~168)
- MC npc.state intake: `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt::executeNpcStateIntent` (~354), DTO `bridge/DomainEvents.kt` (~232), decode `bridge/WebSocketTransport.kt` (~205), dispatch `Story.kt` (~724)
- MC config: `Story/src/main/kotlin/com/canefe/story/config/ConfigService.kt` (reload ~144)
- client perception popup (reuse): `StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt`, `perception/NpcPerceptionPayload.kt` (PopupType enum), registration `client/NPCMessageParserClient.kt` (~167)
- client combat payload pattern: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/combat/HitOutcomePayload.kt`
- client render hook: `client/NPCMessageParserClient.kt` `WorldRenderEvents.AFTER_ENTITIES` (~374)
- story-go relay: `story-go/internal/sim/handler.go` (`handleNpcState`), `internal/sim/events.go` (`IsSimEvent`), `pkg/events/events.go`
