# Emote Icons Pipeline — Design

**Date:** 2026-05-27
**Status:** Approved (design); implementation plan pending
**Scope:** v1 vertical slice — sim is the only producer; 5 stock emotes (Cry, Anger, Pain, Laugh, Shock); icon renders above NPC head in StoryClient.

## Goal

Give NPCs a way to express short non-verbal reactions as floating emoji-style icons above their heads, visible from across the room. Players should instantly read "that NPC just laughed / cried / is in pain" without reading chat.

The full pipeline (sim → story-go → StoryMC → StoryClient) is proven end-to-end on a single new event type. Future versions can plug in additional producers (story-go LLM, StoryMC-local reactions) without changing the wire contract.

## Producer Authority (resolved)

Any of the three layers (story-sim, story-go, StoryMC) is permitted to emit an emote — the wire shape does not restrict the source. **However, v1 ships only the story-sim producer.** Story-go LLM hooks and StoryMC-local reactions (damage → PAIN, death → SHOCK, etc.) are explicit v2 work and out of scope here.

## Architecture

```
story-sim (Rust + Lua)              story-go              StoryMC (Paper)              StoryClient (Fabric)
─────────────────────              ─────────             ─────────────────             ────────────────────
behavior calls                     receives              IntentExecutor                EmoteRenderer:
  char:emote("laugh")    ─NATS─►   EmoteIntent  ─WS─►    .executeEmoteIcon    ─pkt─►   load Identifier
emits EmoteIntent                  forwards as-is        broadcasts                    ("storyclient",
{char_id, emote_id}                to MC client          NPCEmoteIconPacket            "textures/emote/
                                                         to nearby players              <id>.png")
                                                                                       float-and-fade
                                                                                       above NPC head
```

One canonical event type: `NPCEmoteIconIntent { character_id, emote_id }`. Free-form string `emote_id` on the wire; StoryClient holds the allowlist mapping `emote_id → PNG asset`. Unknown IDs are silently dropped at the client.

This **coexists with** the existing `NPCEmoteIntent` (which broadcasts `*action*` as a chat-bubble text message). The two intents do not interact — they are parallel channels for parallel purposes (visual icon vs. chat-log text).

## Components

### story-proto

`story/v1/intents.proto`:

```proto
message NPCEmoteIconIntent {
  string character_id = 1;
  string emote_id = 2;  // free-form; client allowlists {cry, anger, pain, laugh, shock}
}
```

Added as a new variant to the `OrchestratorIntent` oneof. Codegen runs in sim (prost), story-go (protoc), and StoryMC (gradle) per the existing proto-SoT pipeline.

### story-sim

New Lua primitive `char:emote(id)` exposed on all three `char` surfaces (planner-tier, behavior-hook, effects/scoring) — per the known dual-surface gotcha, every surface that any behavior author might reach must expose the method. Each call emits an `EmoteIntent { character_id, emote_id }` onto the existing intent NATS subject.

No internal state, no cooldown in v1 — behavior author owns rate-limiting. If a behavior calls `char:emote` twice in one tick, two intents are emitted and the client renders the second as a replacement of the first.

### story-go

Pass-through: new intent variant in the NATS → WebSocket bridge, forwarded to StoryMC unchanged. Routed through the existing DM-override gate (grabbed NPCs drop the intent, consistent with `go_to` / `speak`).

### StoryMC

1. New `NPCEmoteIconIntent` data class in `bridge/StoryEvent.kt`, implementing `SerializableStoryEvent` with `eventType = "npc.emote_icon"`.
2. Registered in `WebSocketTransport.serializeEvent` (outbound) **and** the inbound deserialize switch in `WebSocketTransport` — both registrations are required per the WS-serialize-registration gotcha. Missing either silently corrupts the payload.
3. `IntentExecutor.executeEmoteIconIntent(plugin, intent)`: resolve NPC via the existing `resolveNPC` helper; if null, log warning and return; otherwise broadcast a new `NPCEmoteIconPacket { entityId, emoteId }` to players within 32 blocks.
4. Event handler wired in `Story.kt` (`eventBus.on<NPCEmoteIconIntent> { IntentExecutor.executeEmoteIconIntent(this, it) }`), next to the existing emote handler registration.
5. Does **not** touch chat. The existing `*action*` text-emote path is left fully intact.

### StoryClient

**Assets:** Five PNGs at `client/resources/assets/storyclient/textures/emote/`:

- `cry.png`
- `anger.png`
- `pain.png`
- `laugh.png`
- `shock.png`

Recommended size 32×32 with transparency. Loaded via `Identifier("storyclient", "textures/emote/<id>.png")`.

**Packet handler:** New `NPCEmoteIconPacketHandler` registered in `StoryPacketManager.registerDefaultHandlers()`, alongside the existing handlers. Decodes `{ entityId: Int, emoteId: String }` and forwards to `EmoteRenderer`.

**Renderer:** New `EmoteRenderer` object (sibling to `PerceptionPopupRenderer` in `client/perception/`):

- Per-entity in-flight emote tracked in `ConcurrentHashMap<UUID, Emote>` (one emote at a time per NPC — new emote replaces running one).
- Lifecycle timing reuses the perception popup constants: `RISE_MS=350`, `HOLD_MS=900`, `EXIT_MS=400`. Total ~1.65s.
- Each frame: for every tracked entity still in the world, draw a world-billboarded textured quad anchored at entity head `+ 0.5` block initial offset, rising another `0.5` block over total lifetime, alpha fading on exit.
- **Always visible** — does not gate on crosshair target (unlike `PerceptionPopupRenderer`). The point of an emote is that players notice it without aiming.
- Unknown `emoteId` (not in the bundled allowlist) → silently dropped, logged at debug.
- Entity despawn detection: if `world.getEntityById(id)` returns null on a tick, the emote is removed.

The allowlist is a hardcoded `Map<String, Identifier>` in `EmoteRenderer` for v1. Adding a new emote means dropping a PNG and adding one line. No config.yml.

## Data Flow Example

```
sim NATS:     {"intent":"emote", "character_id":"npc_42", "emote_id":"laugh"}
story-go WS:  {"type":"npc.emote_icon", "characterId":"npc_42", "emoteId":"laugh"}
MC→client:    NPCEmoteIconPacket { entityId: 1234, emoteId: "laugh" }
client renders laugh.png above entity 1234 for ~1.65s, replacing any prior emote on that entity.
```

## Error Handling & Edge Cases

| Condition | Behavior |
|---|---|
| Unknown `emote_id` at client | Silently dropped, debug-logged. Forward-compatible: sim can ship new IDs before client knows them. |
| NPC not resolvable in StoryMC | Log warning, drop intent. Same pattern as today's `executeEmoteIntent`. |
| NPC currently DM-grabbed | story-go's existing override gate drops the intent. Consistent with `go_to` / `speak`. |
| Player >32 blocks from NPC | Packet not sent. |
| Rapid-fire emotes within RISE_MS | New replaces old. No queue. Author's responsibility to space them. |
| NPC despawns mid-emote | Renderer drops the in-flight emote on next frame. |
| `bridge.enabled = false` | Emotes don't reach MC. Same as every other bridge intent. |

## Testing

**story-sim:**
- Lua unit test per `char` surface (planner / behavior-hook / effects): `char:emote("laugh")` enqueues exactly one `EmoteIntent` on the outbound channel with `emote_id = "laugh"`.

**story-go:**
- Proto serialization round-trip: `NPCEmoteIconIntent` → JSON wire → back.
- DM-grab gate test: grabbed NPC's emote intent is dropped.

**StoryMC:**
- `IntentExecutorTest`: inbound WS message `{"type":"npc.emote_icon","characterId":"X","emoteId":"laugh"}` resolves the NPC and broadcasts a `NPCEmoteIconPacket` to a mock player within range.
- `WebSocketTransport` round-trip test confirms `serializeEvent` registration (both directions).

**StoryClient:**
- Packet handler test: decoded packet enqueues an `Emote` on `EmoteRenderer` (mock the registry / GL).
- Manual smoke test: run client, trigger a sim behavior that calls `char:emote("laugh")`, observe the icon float and fade above the NPC's head.

## Out of Scope (v2+)

- story-go LLM intelligence hook emitting emotes during dialogue
- StoryMC-local reactive emotes (damage → PAIN, death → SHOCK)
- Persistent mood-state emotes (NPC stays Angry until cleared)
- Per-emote sound effects
- Player-driven emotes (player triggers their own emote on themselves)
- Resource-pack-delivered icon set
- Per-emote color tints or animated sprite-sheet variants

## Files Touched (anticipated)

- `story-proto/story/v1/intents.proto` (+ regen across 3 repos)
- `story-sim/`: char surface bindings (3 files), intent emit
- `story-go/`: intent forwarding switch
- `Story/src/main/kotlin/com/canefe/story/bridge/StoryEvent.kt`
- `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`
- `Story/src/main/kotlin/com/canefe/story/bridge/WebSocketTransport.kt`
- `Story/src/main/kotlin/com/canefe/story/Story.kt` (handler wiring)
- New: `Story/src/main/kotlin/com/canefe/story/bridge/NPCEmoteIconPacket.kt` (server-side packet sender)
- `StoryClient/src/client/kotlin/com/canefe/storyclient/client/packets/StoryPacketManager.kt`
- New: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/packets/NPCEmoteIconPacket{Handler}.kt`
- New: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/EmoteRenderer.kt`
- New: `StoryClient/src/client/resources/assets/storyclient/textures/emote/{cry,anger,pain,laugh,shock}.png`
