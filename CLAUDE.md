# Story (Kotlin/Paper Plugin)

## Build & Run

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
./gradlew test
```

Config: `run/plugins/Story/config.yml`
Prompts: `src/main/resources/prompts.yml`

## Architecture

Paper Minecraft plugin for NPC conversations, quest system, and voice. Connects to a Go orchestrator via WebSocket.

### Key Packages

- `bridge/` — WebSocket event bus to Go orchestrator. Classes: StoryEventBus, WebSocketTransport, PerceptionService, PerceptionListener, IntentExecutor, BridgeIntelligence
- `intelligence/` — StoryIntelligence interface. LocalIntelligence (direct LLM) and BridgeIntelligence (delegates to Go). All response generation goes through `plugin.intelligence`
- `conversation/` — ConversationManager handles conversations, auto mode, NPC reactions, speaker selection. `speakAsNPC()` is the single broadcast path
- `npc/service/` — NPCMessageService formats/broadcasts messages with voice sync. NPCResponseService handles LLM calls; `cleanNPCResponse` strips meta-commentary
- `config/` — ConfigService loads config.yml, PromptService loads prompts.yml
- `session/` — SessionManager gates memory writes (no active session = no memory mutations)
- `command/base/` — CommandManager registers commands

### Key Patterns

- `bridge/Perception.kt` — Sealed interface PerceptionDetails with typed variants (Combat, Death, Weather, Movement, Generic)
- Bukkit events implement StoryEvent directly for zero-duplication serialization (ConversationStartEvent, ConversationEndEvent, PerceptionEvent)
- `bridge.enabled` config controls WebSocket connection to Go
- All NPC name args in commands use quoted autocomplete (`"NPC Name"`) and strip quotes on receive
- Memory writes require active session (`sessionManager.hasActiveSession()`)
- Auto mode conversations debounce on player message, `/maketalk`, `/conv continue`
- `generatingResponses` ConcurrentHashMap prevents overlapping response generation

### Test Setup

```kotlin
plugin.configService.npcReactionsEnabled = false
plugin.configService.autoModeEnabledByDefault = false
```
