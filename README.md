<div align="center" style="margin-bottom: 1rem;">
  <h1 style="font-size: 2rem; margin: 0;"> StoryMC </h1>

  <img src="https://img.shields.io/badge/status-archived-red.svg?style=flat-square" alt="Archived" />
  <img src="https://img.shields.io/github/v/release/canefe/StoryMC?include_prereleases&style=flat-square" alt="Latest Version" />
  <img src="https://img.shields.io/badge/minecraft-1.21.1-brightgreen.svg?style=flat-square" alt="Minecraft Version" />
  <img src="https://img.shields.io/badge/paper-1.21.1-blue.svg?style=flat-square" alt="Paper API" />
</div>

> [!IMPORTANT]
> **📦 This repository is archived.**
>
> StoryMC has been superseded by **story-forged**, a NeoForge mod that replaces this Paper plugin as the Minecraft frontend of the Story platform. Development moved there because the platform outgrew what a server-side plugin can do: story-forged ships both a server mod and a client mod, giving us custom entities and renderers, client-side HUDs (health, needs, skill checks), camera control for cinematics, and a first-class inventory/item pipeline.
> 
>
> This repo remains available read-only as a reference for the original monolith design. No further releases, fixes, or support.

A Kotlin-based Paper plugin that transformed Minecraft into a living, narrative-driven world through AI-integrated NPCs and voice synthesis.

Fully stable as a Human-in-the-loop (HITL) orchestration tool. It provides a robust narration engine and NPC framework managed by a human Game Master.

## Where did the project go?

StoryMC started as a standalone monolith: one Paper plugin doing dialogue, memory, voice, scheduling, and world logic. That architecture served its purpose and is preserved here, but the platform has since been decomposed into dedicated services, with Minecraft demoted to just one possible frontend:

```
┌───────────────────────┐      ┌───────────────────────┐
│  story-forged         │      │  Other frontends      │
│  (NeoForge mod,       │      │  (web viewer, ...)    │
│   client + server)    │      │                       │
└──────────┬────────────┘      └──────────┬────────────┘
           │  gRPC / protobuf             │
           ▼                              ▼
┌─────────────────────────────────────────────────────┐
│  story-go — orchestrator & director                 │
│  (intents, events, LLM brains, narrative direction) │
└──────────────────────────┬──────────────────────────┘
                           │  NATS
                           ▼
┌─────────────────────────────────────────────────────┐
│  story-sim — autonomous simulation engine (Rust)    │
│  (HTN planning, needs, behaviors, Lua authoring)    │
└─────────────────────────────────────────────────────┘
```

What this plugin did on its own is now split across that stack, and the roadmap items listed below (external simulation, autonomous agents, semantic memory) became the successor's foundation rather than experiments.

---

*Everything below is the original README, kept for historical reference.*

---

![Story](https://i.imgur.com/ZqSs2tx.png)

## What is Story?

Story is a production-ready Minecraft plugin that enables Dungeon Masters to create immersive narrative experiences with AI-powered NPCs. It allows facilitating D&D-style campaigns with persistent character memory, dynamic dialogue generation, and realistic voice synthesis.

- **LLM-Powered Dialogues**. NPCs generate contextually-aware responses using language models
- **Voice Synthesis**. Character-specific voices via ElevenLabs integration
- **Persistent Memory**. NPCs remember conversations, relationships, and story events
- **Rich Character System**. Complex personalities, backstories, relationships, and lore
- **Location Awareness**. NPCs understand their environment and react accordingly
- **Multi-Player Support**. Handle concurrent conversations across multiple players
- **Character Schedules**. NPCs follow daily routines and autonomous behaviors
- **Plugin Ecosystem**. Integrates with Citizens, MythicMobs, and other popular plugins

## Installation

> [!WARNING]
> Provided as-is for historical reference. No support is offered for new installations — the actively developed successor is story-forged.

### Requirements

- Minecraft 1.21.1
- Paper 1.21.1
- Java 21
- OpenRouter API key (for LLM integration)
- ElevenLabs API key (for voice synthesis) (optional)

### Dependencies

- [Citizens](https://github.com/CitizensDev/Citizens2) - NPC management
- [MythicMobs](https://www.spigotmc.org/resources/mythicmobs.5702/) (optional) - Advanced mob behaviors
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) (optional)
- [DecentHolograms](https://www.spigotmc.org/resources/decentholograms.96927/) (optional)
- [ItemsAdder](https://www.spigotmc.org/resources/itemsadder.9388/) (optional)
- [RealisticSeasons](https://www.spigotmc.org/resources/realisticseasons.83416/) (optional)
- [ReviveMe](https://www.spigotmc.org/resources/reviveme.93888/) (optional)
- [TheNewEconomy](https://www.spigotmc.org/resources/theneweconomy.93889/) (optional)
- [SuperVanish](https://www.spigotmc.org/resources/supervanish.93890/) (optional)
- [BetterHealthBar](https://www.spigotmc.org/resources/betterhealthbar.93892/) (optional)

### Usage
Click [here](https://story-2.gitbook.io/story/basics/interactive-blocks/creating-storylocations) to get started.

## Architecture (as archived)

```
┌─────────────────────────────────────┐
│     Minecraft Server (Paper)        │
│  ┌───────────────────────────────┐  │
│  │      Story Plugin (Kotlin)    │  │
│  │  ┌─────────────────────────┐  │  │
│  │  │   Character System      │  │  │
│  │  │   - Personalities       │  │  │
│  │  │   - Relationships       │  │  │
│  │  │   - Memories            │  │  │
│  │  └─────────────────────────┘  │  │
│  │  ┌─────────────────────────┐  │  │
│  │  │   Lores & Locations     │  │  │
│  │  │   - Keywords & Context  │  │  │
│  │  └─────────────────────────┘  │  │
│  │  ┌─────────────────────────┐  │  │
│  │  │   LLM Integration       │  │  │
│  │  │   (OpenRouter API)      │  │  │
│  │  └─────────────────────────┘  │  │
│  │  ┌─────────────────────────┐  │  │
│  │  │   Voice Synthesis       │  │  │
│  │  │   (ElevenLabs)          │  │  │
│  │  └─────────────────────────┘  │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

### How It Works

1. **Player Interaction**: Player speaks in proximity to an NPC
2. **Context Building**: System gathers character personality, recent memories, location data
3. **LLM Generation**: OpenRouter API generates contextually-appropriate response
4. **Voice Synthesis**: ElevenLabs converts text to character-specific voice
5. **Response Delivery**: Audio and text delivered to players
6. **Memory Update**: Conversation stored for future context

## Use Cases

### Narrative Campaigns
Run D&D-style adventures with persistent NPC characters who remember player actions and develop relationships over time.

### Living Worlds
Create settlements with NPCs that have daily schedules, relationships, and goals - making the world feel alive even when not directly interacting.

### Interactive Storytelling
Let NPCs drive emergent narratives based on player choices and actions, rather than following predetermined scripts.

### Educational/Roleplay Servers
Facilitate creative roleplay with AI-powered characters that stay in character and respond contextually.

## Support

This repository is archived and unsupported. For the actively developed successor:

- **Discord**: [Discord Server](https://discord.gg/vbSszBZc)

## License

This project is licensed under the [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/) License.
