# Decision System Design

**Date:** 2026-05-04  
**Status:** Approved for implementation planning  
**Scope:** CK3-style emergent player decision UI with cinematic presentation, multi-player voting, and NPC voices

---

## Overview

Replace implicit Minecraft simulation (combat mechanics, positioning, etc.) with an explicit text-based decision system where the Go orchestrator detects decisive moments, generates options and NPC opinions via LLM, and presents them to players through a custom Fabric client UI. Decisions are emergent and dynamic — not scripted — driven by the world state and perception pipeline.

---

## Pipeline

```
Perception events / world state
        ↓
Go detects decisive moment
        ↓
Go LLM generates DecisionEvent (options + NPC voices)
        ↓
Go → WebSocket → Story.kt
        ↓
Story.kt relays via raw packets to targeted Fabric clients
        ↓
Fabric renders cinematic UI + countdown
        ↓
Player(s) respond via packet → Story.kt → WebSocket → Go
        ↓
Go resolves consequences → fires PerceptionEvents back into pipeline
```

Story.kt is a pure relay. No decision logic lives in the plugin.

---

## Decision Event (Go → Story.kt → Fabric)

```json
{
  "type": "decision.prompt",
  "decisionId": "uuid",
  "mode": "leader" | "vote",
  "leaderId": "characterId",
  "playerTargets": ["characterId1", "characterId2"],
  "title": "The battle line is breaking",
  "context": "Your left flank is overwhelmed. Commander Valen is wounded.",
  "urgency": "critical" | "ambient",
  "npcVoices": [
    {
      "characterId": "...",
      "name": "Valen",
      "opinion": "Fall back to the ridge — we'll regroup.",
      "stance": "cautious"
    },
    {
      "characterId": "...",
      "name": "Mira",
      "opinion": "Push through! They're bluffing.",
      "stance": "aggressive"
    }
  ],
  "options": [
    { "id": "a", "label": "Retreat to the ridge", "consequenceHint": "Safer, but cedes ground" },
    { "id": "b", "label": "Press the attack", "consequenceHint": "High risk, high reward" },
    { "id": "c", "label": "Hold position and send for reinforcements", "consequenceHint": "Buys time" }
  ],
  "allowFreeform": true,
  "timeoutSeconds": 60
}
```

### Observer Event (non-leader players in leader mode)

```json
{
  "type": "decision.observe",
  "decisionId": "uuid",
  "leaderName": "...",
  "options": [...]
}
```

### Player Response (Fabric → Story.kt → Go)

```json
{
  "type": "decision.response",
  "decisionId": "uuid",
  "choiceId": "a",
  "freeformText": null
}
```

`choiceId` is null when the player uses freeform. `freeformText` is null when they pick a preset option.

---

## Decision Modes

### Leader Mode
- Only the designated `leaderId` player receives `decision.prompt` and gets the full UI
- All other `playerTargets` receive `decision.observe` — a slim HUD strip showing who is deciding and what the options are
- Go acts on the leader's response immediately

### Vote Mode
- All `playerTargets` receive `decision.prompt` and the full UI
- Go waits for all votes (or timeout), tallies majority, resolves
- Leader (if set) breaks ties
- Each player's UI shows live vote counts updating as others respond

---

## Urgency Modes

### Critical
- Full-screen takeover with dark vignette overlay
- Cinematic camera (see below)
- Countdown timer in top corner, color shifts red as time runs low
- Game world still renders behind the overlay

### Ambient
- HUD panel slides in from the right or bottom
- Game remains fully playable
- No forced timeout, player can defer
- No cinematic camera

---

## Cinematic Camera (Fabric, critical urgency only)

Automatic sequencer runs while the decision is pending:

1. For each NPC in `npcVoices`: smoothly interpolate camera to their world position at head height, tight FOV, hold ~3-4 seconds. The corresponding NPC voice card pulses/highlights in the UI panel.
2. Pull to top-down overview of all decision participants (~5 seconds).
3. Loop until player responds or timeout fires.
4. On resolution: camera eases back to player control.

---

## Fabric UI Layout (critical)

```
┌─────────────────────────────────────────────────────┐
│  [Game world renders behind vignette overlay]        │
│                                          [00:42] ←countdown
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │ TITLE: "The battle line is breaking"         │   │
│  │ CONTEXT: "Your left flank is overwhelmed..." │   │
│  ├─────────────────────────────────────────────┤   │
│  │ NPC VOICES (portrait cards, side by side)    │   │
│  │ [Valen] cautious  |  [Mira] aggressive       │   │
│  │ "Fall back..."    |  "Push through..."        │   │
│  ├─────────────────────────────────────────────┤   │
│  │ OPTIONS                                       │   │
│  │  [A] Retreat to the ridge                    │   │
│  │  [B] Press the attack                        │   │
│  │  [C] Hold position and send reinforcements   │   │
│  │  [✏] Say something else...                   │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

---

## Story.kt Changes

Three new `BridgeDTO` message types:
- `decision.prompt` — received from Go, dispatched as packet to targeted players
- `decision.observe` — received from Go, dispatched as packet to observer players
- `decision.response` — received as packet from Fabric client, forwarded to Go over WebSocket

A new `DecisionPacketHandler` in the `bridge/` package:
- Listens for `decision.prompt` / `decision.observe` from Go via `StoryEventBus`
- Sends the appropriate raw packet to the targeted online players
- Receives response packets from clients and forwards to Go

---

## Future: Event Ledger Integration

Each `DecisionEvent` carries a stable `decisionId`. When the event ledger is built, decisions will be first-class ledger entries with:
- Causal chain: which perception events triggered this decision
- Resolution record: which option was chosen, who voted what
- Downstream effects: which PerceptionEvents / memory writes were caused by this decision

For now, `decisionId` is generated but only used for response correlation. The shape is forward-compatible.

---

## Out of Scope (this spec)

- Event ledger / rollback system (separate spec)
- DM override UI
- Decision history / replay UI
- NPC autonomous decisions (no player involved)
