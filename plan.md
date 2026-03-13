# Conversation Theme System - Implementation Plan

## Architecture

### Core abstractions (new package: `conversation/theme/`)

1. **`ConversationTheme`** - Abstract base class that defines a theme
   - `name: String` - unique identifier (e.g., "chat", "violence")
   - `displayName: String` - human-readable name
   - `compatibleWith: Set<String>` - theme names this can stack with
   - `onActivate(conversation: Conversation)` - callback when theme becomes active
   - `onDeactivate(conversation: Conversation)` - callback when theme is removed
   - `onMessage(conversation: Conversation, message: ConversationMessage)` - called on each message

2. **`ConversationThemeFactory`** - Interface for creating theme instances
   - `fun create(): ConversationTheme`

3. **`ConversationThemeRegistry`** - Singleton registry
   - `register(name: String, factory: ConversationThemeFactory)` - register a theme type
   - `unregister(name: String)` - remove a theme type
   - `create(name: String): ConversationTheme` - instantiate from factory
   - `getRegisteredThemes(): Set<String>` - list available themes

4. **`ConversationThemeManager`** - Manages active themes per conversation (separate manager, observes conversations)
   - `activateTheme(conversation: Conversation, themeName: String): Boolean` - activate a theme (checks compatibility with existing active themes)
   - `deactivateTheme(conversation: Conversation, themeName: String): Boolean`
   - `getActiveThemes(conversation: Conversation): List<ConversationTheme>`
   - `hasTheme(conversation: Conversation, themeName: String): Boolean`
   - `onConversationEnd(conversation: Conversation)` - cleanup
   - `onMessage(conversation: Conversation, message: ConversationMessage)` - propagate to active themes

5. **`ConversationThemeData`** - Data class held within `Conversation`
   - `activeThemeNames: List<String>` (read-only view of active theme names)
   - Kept minimal - just state, no logic

### Example Themes

6. **`ChatTheme`** - Default theme, compatible with everything
7. **`ViolenceTheme`** - Activates on combat events, not compatible with "trade"

### Integration Points

- Add `themeData: ConversationThemeData` field to `Conversation`
- `ConversationThemeManager` initialized in `Story.onEnable()` and stored on plugin
- Theme manager listens to `ConversationStartEvent` to apply default "chat" theme
- Theme manager listens to conversation messages via `ConversationManager`

### File list
```
src/main/kotlin/com/canefe/story/conversation/theme/
├── ConversationTheme.kt          # Abstract base
├── ConversationThemeFactory.kt   # Factory interface
├── ConversationThemeRegistry.kt  # Singleton registry
├── ConversationThemeManager.kt   # Per-conversation manager
├── ConversationThemeData.kt      # Data class for Conversation
├── ChatTheme.kt                  # Example: default chat
└── ViolenceTheme.kt              # Example: violence/combat

Modified files:
- Conversation.kt                 # Add themeData field
- Story.kt                        # Initialize theme manager + register defaults
```

### Tests
```
src/test/kotlin/com/canefe/story/conversation/theme/
├── ConversationThemeRegistryTest.kt
├── ConversationThemeManagerTest.kt
```
