# Testing Guide for Story Plugin

This document provides a comprehensive guide on how to test the Story Minecraft plugin.

## Overview

The Story plugin uses a modern testing stack:
- **JUnit 5** - Main testing framework
- **Mockito** - Mocking framework with Kotlin support
- **MockBukkit** - Bukkit/Paper API mocking
- **Kotlin Test** - Kotlin-specific testing utilities

## Test Structure

```
src/test/kotlin/
├── com/canefe/story/
│   ├── TestBase.kt              # Base test class with common setup
│   ├── TestUtils.kt             # Testing utilities
│   ├── StoryTest.kt             # Basic plugin tests
│   ├── config/
│   │   └── ConfigServiceTest.kt # Configuration tests
│   ├── conversation/
│   │   └── ConversationManagerTest.kt # Conversation logic tests
│   ├── npc/
│   │   └── service/
│   │       └── NPCResponseServiceTest.kt # NPC service tests
│   └── util/
│       └── EssentialsUtilsTest.kt # Utility function tests
└── resources/                   # Test resources (config files, etc.)
```

## Types of Tests

### 1. Unit Tests
Test individual components in isolation using mocks.

**Example:**
```kotlin
@Test
fun `getNickname should return player name when no nickname set`() {
    // Given
    val player = server.addPlayer("TestPlayer")

    // When
    val nickname = EssentialsUtils.getNickname(player.name)

    // Then
    assertEquals("TestPlayer", nickname)
}
```

### 2. Integration Tests
Test how components work together with real dependencies.

**Example:**
```kotlin
@Test
fun `createConversation should return new conversation`() {
    // Given
    val player1 = server.addPlayer("Player1")
    val player2 = server.addPlayer("Player2")

    // When
    val conversation = conversationManager.createConversation(listOf(player1, player2))

    // Then
    assertNotNull(conversation)
    assertTrue(conversation.participants.contains(player1))
}
```

### 3. Service Tests
Test business logic services with mocked external dependencies.

**Example:**
```kotlin
@Test
fun `generateNPCResponse should return CompletableFuture`() {
    // Given
    val responseContext = listOf("Hello", "How are you?")
    val player = server.addPlayer("TestPlayer")

    // When
    val result = npcResponseService.generateNPCResponse(
        npc = null,
        responseContext = responseContext,
        player = player
    )

    // Then
    assertNotNull(result)
    assertTrue(result is CompletableFuture<String>)
}
```

## Testing Best Practices

### 1. Use TestBase for Common Setup
Extend `TestBase` to get automatic MockBukkit setup:

```kotlin
class MyServiceTest : TestBase() {
    // Automatic server and plugin setup
}
```

### 2. Use TestUtils for Common Operations
```kotlin
val players = TestUtils.createMockPlayers(server, "Player1", "Player2")
val npc = TestUtils.createMockNPC("TestNPC")
```

### 3. Follow AAA Pattern
- **Arrange** - Set up test data and mocks
- **Act** - Execute the code under test
- **Assert** - Verify the results

### 4. Use Descriptive Test Names
```kotlin
@Test
fun `generateNPCResponse with player should use player name`() {
    // Test implementation
}
```

### 5. Mock External Dependencies
```kotlin
@Mock
private lateinit var mockNPCContextGenerator: NPCContextGenerator

@BeforeEach
fun setUp() {
    MockitoAnnotations.openMocks(this)
    whenever(plugin.npcContextGenerator).thenReturn(mockNPCContextGenerator)
}
```

## Running Tests

### Command Line
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.canefe.story.StoryTest"

# Run tests with verbose output
./gradlew test --info

# Run tests and generate reports
./gradlew test jacocoTestReport
```

### IDE Integration
- **IntelliJ IDEA**: Right-click on test class/method → Run
- **VS Code**: Use Kotlin Test Runner extension

## Test Configuration

### JVM Arguments
Tests run with optimized JVM settings:
- `-Xmx2G` - 2GB heap memory
- `-XX:+UseG1GC` - G1 garbage collector

### Parallel Execution
Tests run in parallel using half of available CPU cores for optimal performance.

### Logging
Test output includes:
- Passed/skipped/failed events
- Standard streams
- Exceptions and stack traces

## Mocking Strategies

### 1. MockBukkit for Bukkit API
```kotlin
val server = MockBukkit.mock()
val player = server.addPlayer("TestPlayer")
```

### 2. Mockito for Custom Objects
```kotlin
@Mock
private lateinit var mockNPC: NPC

whenever(mockNPC.name).thenReturn("TestNPC")
```

### 3. Mockito-Kotlin for Better Kotlin Support
```kotlin
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any

whenever(mockService.process(any())).thenReturn("result")
```

## Testing Async Code

For testing `CompletableFuture` and other async operations:

```kotlin
@Test
fun `async operation should complete successfully`() {
    // Given
    val future = service.asyncOperation()

    // When & Then
    val result = future.get(5, TimeUnit.SECONDS)
    assertNotNull(result)
}
```

## Testing Configuration

Test configuration files are located in `src/test/resources/` and mirror the main configuration structure.

## Coverage

To generate test coverage reports:

```bash
./gradlew jacocoTestReport
```

Reports will be generated in `build/reports/jacoco/test/html/index.html`

## Common Testing Patterns

### 1. Testing Event Handlers
```kotlin
@Test
fun `event handler should process event correctly`() {
    // Given
    val event = createMockEvent()

    // When
    eventManager.handleEvent(event)

    // Then
    verify(mockService).processEvent(event)
}
```

### 2. Testing Commands
```kotlin
@Test
fun `command should execute successfully`() {
    // Given
    val player = server.addPlayer("TestPlayer")
    val command = "/story test"

    // When
    player.performCommand(command)

    // Then
    player.assertSaid("Command executed successfully")
}
```

### 3. Testing Data Persistence
```kotlin
@Test
fun `data should be saved and loaded correctly`() {
    // Given
    val data = TestData("test", 42)

    // When
    dataManager.save(data)
    val loaded = dataManager.load("test")

    // Then
    assertEquals(data, loaded)
}
```

## Troubleshooting

### Common Issues

1. **MockBukkit not working**: Ensure you're using the correct version for your Minecraft version
2. **Mockito not working**: Check that `@Mock` annotations are properly initialized
3. **Async test failures**: Use proper timeouts and `CompletableFuture.get()`
4. **Resource loading issues**: Ensure test resources are in the correct directory

### Debug Tips

1. Use `@DisplayName` for better test reporting
2. Add logging to understand test flow
3. Use `assertAll` for multiple assertions
4. Use `assertThrows` for exception testing

## Continuous Integration

For CI/CD pipelines, ensure:
1. Tests run in headless mode
2. Proper JVM memory allocation
3. Test reports are generated and archived
4. Coverage thresholds are enforced

## Future Enhancements

Consider adding:
1. **Property-based testing** with Kotest
2. **Contract testing** for API interactions
3. **Performance testing** for critical paths
4. **End-to-end testing** with real server instances










