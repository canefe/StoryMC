<div align="center" style="margin-bottom: 1rem;">
<h1 style="font-size: 2rem;margin: 0;"> StoryMC </h1>
<img src="https://img.shields.io/badge/minecraft-1.21.1-brightgreen.svg" alt="Minecraft Version" />
<img src="https://img.shields.io/badge/paper-1.21.1-blue.svg" alt="Paper API" />
<img src="https://img.shields.io/badge/kotlin-1.9+-purple.svg" alt="Kotlin" />
</div>


A Kotlin-based Paper plugin that transforms Minecraft into a living, narrative-driven world through AI-integrated NPCs and voice synthesis. 

Fully stable as a Human-in-the-loop (HITL) orchestration tool. It provides a robust narration engine and NPC framework managed by a human Game Master.

> [!NOTE]  
> This project is architected to scale into a fully autonomous simulation engine. The objective is to transition from human-managed logic to a self-governing agentic framework where AI entities dynamically manage their own schedules, behaviors, and environmental interactions.

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

**Roadmap:** 
Evolving into a comprehensive simulation engine. The goal is to transition from manual GM scheduling to a fully autonomous architecture where AI agents manage their own schedules, behaviors, and world-state interactions with minimal human intervention.

## Installation

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

## Architecture

### Current Production Stack

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

## Experimental Features

### Vector Database Integration (In Development)

Working on semantic search capabilities using Python + vector databases:

- 13,000+ conversation embeddings from gameplay data
- Semantic memory retrieval (vs. simple recent + flagged)
- Improved context relevance for long-term character development

### MCP Server

Model Context Protocol server for external LLM access to story data:
- Query character information programmatically
- Retrieve narrative context across sessions
- Enable advanced AI-assisted storytelling tools

### LangGraph Multi-Agent Architecture 

Exploring autonomous NPC behaviors with sophisticated cognitive architecture:
- Environmental perception systems
- Goal-driven decision making
- Multi-step reasoning for complex interactions
- Reduced manual DM orchestration

### External Simulation in Go 

Making minecraft solely a frontend layer, and the simulation layer is in a separate server. Using Redis Pub/Sub for communication.

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

- **Issues**: [GitHub Issues](https://github.com/canefe/story/issues)
- **Discord**: [Discord Server](https://discord.gg/vbSszBZc)

**Note**: Story is under active development. Production features are stable, but experimental features are subject to change.

## License

This project is licensed under the [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/) License.


