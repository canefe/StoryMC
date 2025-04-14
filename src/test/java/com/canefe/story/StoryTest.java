package com.canefe.story;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class StoryTest {

    @Mock
    private Story plugin;

    @Mock
    private Player player;

    @Mock
    private NPC npc;

    @Mock
    private ConversationManager conversationManager;

    @Mock
    private GroupConversation conversation;

    private Method handleConversationMethod;
    private List<String> disabledPlayers;
    private Set<UUID> disabledNPCs;
    private Map<UUID, UUID> playerCurrentNPC;

    @BeforeEach
    public void setup() throws Exception {
        // Initialize mocks
        MockitoAnnotations.openMocks(this);

        // Use reflection to access private fields
        disabledPlayers = new ArrayList<>();
        disabledNPCs = new HashSet<>();
        playerCurrentNPC = new HashMap<>();

        // Set the private fields via reflection
        setPrivateField(plugin, "conversationManager", conversationManager);
        setPrivateField(plugin, "playerCurrentNPC", playerCurrentNPC);
        setPrivateField(plugin, "disabledPlayers", disabledPlayers);
        setPrivateField(plugin, "disabledNPCs", disabledNPCs);

        // Get the private method using reflection
        handleConversationMethod = Story.class.getDeclaredMethod("handleConversation",
                Player.class, NPC.class, boolean.class);
        handleConversationMethod.setAccessible(true);

        // Common mock setup
        UUID playerUUID = UUID.randomUUID();
        UUID npcUUID = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerUUID);
        when(npc.getUniqueId()).thenReturn(npcUUID);
        when(npc.getName()).thenReturn("TestNPC");
        when(npc.isSpawned()).thenReturn(true);

        // Mock the player range check to always return true for testing
        doReturn(true).when(plugin).isPlayerInRangeOfNPC(any(Player.class), any(NPC.class));
    }

    private void setPrivateField(Object object, String fieldName, Object value) throws Exception {
        Field field = Story.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Test
    public void testHandleConversation_PlayerDisabled() throws Exception {
        // Setup - player has disabled interactions
        disabledPlayers.add("TestPlayer");
        when(player.getName()).thenReturn("TestPlayer");

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertTrue(result);
        verify(conversationManager, never()).startGroupConversation(any(), any());

        // Use reflection to check playerCurrentNPC
        Field field = Story.class.getDeclaredField("playerCurrentNPC");
        field.setAccessible(true);
        Map<UUID, UUID> map = (Map<UUID, UUID>)field.get(plugin);
        assertEquals(npc.getUniqueId(), map.get(player.getUniqueId()));
    }

    @Test
    public void testHandleConversation_NPCDisabled() throws Exception {
        // Setup - NPC is disabled
        disabledNPCs.add(npc.getUniqueId());

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertFalse(result);
        verify(conversationManager, never()).startGroupConversation(any(), any());
    }

    @Test
    public void testHandleConversation_PlayerAlreadyInConversation() throws Exception {
        // Setup - player is already in a conversation
        when(conversationManager.hasActiveConversation(player)).thenReturn(true);
        when(conversationManager.getActiveConversation(player)).thenReturn(conversation);

        // Create a List of UUIDs (not a Set)
        List<UUID> players = new ArrayList<>();
        players.add(player.getUniqueId());

        // Use doReturn/when for generic return types
        doReturn(players).when(conversation).getPlayers();

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertTrue(result);
        verify(conversationManager).addNPCToConversation(eq(player), eq(npc));
    }

    @Test
    public void testHandleConversation_NPCAlreadyInConversation() throws Exception {
        // Setup - NPC is already in a conversation
        when(conversationManager.hasActiveConversation(player)).thenReturn(false);
        when(conversationManager.isNPCInConversation(npc.getName())).thenReturn(true);
        when(conversationManager.addPlayerToConversation(player, npc.getName())).thenReturn(true);

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertTrue(result);
        verify(conversationManager).addPlayerToConversation(player, npc.getName());
    }

    @Test
    public void testHandleConversation_StartNewConversation() throws Exception {
        // Setup - new conversation
        when(conversationManager.hasActiveConversation(player)).thenReturn(false);
        when(conversationManager.isNPCInConversation(npc.getName())).thenReturn(false);

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertTrue(result);
        verify(conversationManager).startGroupConversation(player, Collections.singletonList(npc));
    }

    @Test
    public void testHandleConversation_PlayerOutOfRange() throws Exception {
        // Setup - player is out of range
        doReturn(false).when(plugin).isPlayerInRangeOfNPC(player, npc);

        // Execute
        boolean result = (boolean) handleConversationMethod.invoke(plugin, player, npc, true);

        // Verify
        assertFalse(result);
        verify(conversationManager, never()).startGroupConversation(any(), any());
    }

    @Test
    public void testPlayerJoinsExistingThenStartsNewConversation() throws Exception {
        // Arrange
        Player player1 = mock(Player.class);
        Player player2 = mock(Player.class);
        UUID player1Uuid = UUID.randomUUID();
        UUID player2Uuid = UUID.randomUUID();

        when(player1.getUniqueId()).thenReturn(player1Uuid);
        when(player1.getName()).thenReturn("Player1");
        when(player2.getUniqueId()).thenReturn(player2Uuid);
        when(player2.getName()).thenReturn("Player2");

        NPC npc1 = mock(NPC.class);
        NPC npc2 = mock(NPC.class);
        UUID npc1Uuid = UUID.randomUUID();
        UUID npc2Uuid = UUID.randomUUID();
        when(npc1.getUniqueId()).thenReturn(npc1Uuid);
        when(npc2.getUniqueId()).thenReturn(npc2Uuid);
        when(npc1.getName()).thenReturn("NPC1");
        when(npc2.getName()).thenReturn("NPC2");
        when(npc1.isSpawned()).thenReturn(true);
        when(npc2.isSpawned()).thenReturn(true);

        // Scenario 1: Player1 starts conversation with NPC1
        doReturn(true).when(plugin).isPlayerInRangeOfNPC(any(Player.class), any(NPC.class));
        when(conversationManager.hasActiveConversation(player1)).thenReturn(false);
        when(conversationManager.isNPCInConversation(npc1.getName())).thenReturn(false);

        // Act - Player1 starts conversation with NPC1
        boolean result1 = (boolean) handleConversationMethod.invoke(plugin, player1, npc1, true);

        // Assert
        assertTrue(result1);
        verify(conversationManager).startGroupConversation(player1, Collections.singletonList(npc1));

        // Reset for next scenario
        reset(conversationManager);

        // Scenario 2: Player2 joins Player1's conversation
        when(conversationManager.hasActiveConversation(player2)).thenReturn(false);
        when(conversationManager.isNPCInConversation(npc1.getName())).thenReturn(true);
        when(conversationManager.addPlayerToConversation(player2, npc1.getName())).thenReturn(true);

        // Act - Player2 joins the conversation
        boolean result2 = (boolean) handleConversationMethod.invoke(plugin, player2, npc1, true);

        // Assert
        assertTrue(result2);
        verify(conversationManager).addPlayerToConversation(player2, npc1.getName());

        // Reset for next scenario
        reset(conversationManager);

        // Scenario 3: Player2 starts a new conversation with NPC2
        GroupConversation conv1 = mock(GroupConversation.class);
        when(conversationManager.hasActiveConversation(player2)).thenReturn(true);
        when(conversationManager.getActiveConversation(player2)).thenReturn(conv1);
        when(conversationManager.isNPCInConversation(npc2.getName())).thenReturn(false);

        // Mock the players list for conv1
        List<UUID> conv1Players = new ArrayList<>();
        conv1Players.add(player2.getUniqueId());
        doReturn(conv1Players).when(conv1).getPlayers();

        // Act - Player2 starts new conversation with NPC2
        boolean result3 = (boolean) handleConversationMethod.invoke(plugin, player2, npc2, true);

        // Assert
        assertTrue(result3);
        // The actual behavior is to add the NPC to the existing conversation, not start a new one
        verify(conversationManager).addNPCToConversation(eq(player2), eq(npc2));
    }
}