package com.canefe.story;

import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ConversationManagerTest {

    private MockedStatic<Bukkit> bukkitMock;
    private MockedStatic<LoreBookManager> loreBookManagerMock;
    private MockedStatic<EssentialsUtils> essentialsUtilsMock;
    private MockedStatic<PluginUtils> pluginUtilsMock;

    @Mock
    private Story plugin;

    @Mock
    private Player player;

    @Mock
    private Server server;

    @Mock
    private PluginManager pluginManager;

    @Mock
    private BukkitScheduler scheduler;

    @Mock
    private LoreBookManager loreBookManager;

    @Mock
    private LocationManager locationManager;


    @Mock
    private NPC npc;

    private UUID playerUuid = UUID.randomUUID();
    private UUID npcUuid = UUID.randomUUID();

    @Mock
    private NPCUtils npcUtils;

    private MockedStatic<SeasonsAPI> seasonsMock;

    @BeforeEach
    public void setUp() {
        // Setup static mocks first
        bukkitMock = mockStatic(Bukkit.class);
        loreBookManagerMock = mockStatic(LoreBookManager.class);
        essentialsUtilsMock = mockStatic(EssentialsUtils.class);
        pluginUtilsMock = mockStatic(PluginUtils.class);


        // Setup Bukkit static methods
        Logger logger = Logger.getLogger("BukkitLogger");
        bukkitMock.when(Bukkit::getServer).thenReturn(server);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkitMock.when(Bukkit::getLogger).thenReturn(logger);


        // Mock RealisticSeasons API
        //MockedStatic<PluginUtils> pluginUtilsMock = mockStatic(PluginUtils.class);
        seasonsMock = mockStatic(SeasonsAPI.class);
        SeasonsAPI seasonsAPI = mock(SeasonsAPI.class);
        seasonsMock.when(SeasonsAPI::getInstance).thenReturn(seasonsAPI);

        // Mock necessary methods on SeasonsAPI
        when(seasonsAPI.getSeason(any())).thenReturn(Season.FALL);
        when(seasonsAPI.getHours(any())).thenReturn(12);
        when(seasonsAPI.getMinutes(any())).thenReturn(30);
        when(seasonsAPI.getDate(any())).thenReturn(mock(Date.class));
        when(seasonsAPI.getDate(any()).toString(anyBoolean())).thenReturn("Spring 1");


        // If your code gets the API via static method

        // Setup Bukkit static methods
        bukkitMock.when(Bukkit::getServer).thenReturn(server);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);

        // Setup LoreBookManager
        loreBookManagerMock.when(() -> LoreBookManager.getInstance(any())).thenReturn(loreBookManager);

        // Setup EssentialsUtils
        essentialsUtilsMock.when(() -> EssentialsUtils.getNickname(any())).thenReturn("TestPlayer");

        // Setup PluginUtils - indicate DHAPI is not available
        pluginUtilsMock.when(() -> PluginUtils.isPluginEnabled("DecentHolograms")).thenReturn(false);

        // Setup plugin
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        locationManager = mock(LocationManager.class);
        plugin.locationManager = locationManager;

        // Mock any required methods
        when(locationManager.getLocation(anyString())).thenReturn(null); // or a mock location


        // Setup mock data folder
        File dataFolder = new File("target/test-data");
        dataFolder.mkdirs();
        when(plugin.getDataFolder()).thenReturn(dataFolder);

        // Set static instance of the plugin
        Story.instance = plugin;

        // Setup mock player
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("TestPlayer");

        // Setup mock NPC
        when(npc.getUniqueId()).thenReturn(npcUuid);
        when(npc.getName()).thenReturn("TestNPC");
        when(npc.isSpawned()).thenReturn(true);
        //doAnswer(invocation -> true).when(plugin).isPlayerInRangeOfNPC(any(Player.class), any(NPC.class));
        npcUtils = mock(NPCUtils.class);
        plugin.npcUtils = npcUtils;
        NPCUtils.NPCContext mockContext = mock(NPCUtils.NPCContext.class);
        // Mock the necessary fields or methods on the context
        mockContext.npcName = "TestNPC";
        mockContext.npcRole = "TestRole";
        when(npcUtils.getOrCreateContextForNPC(anyString())).thenReturn(mockContext);

        // Now it's safe to create the conversation manager
        ConversationManager conversationManager = new ConversationManager(plugin);
        plugin.conversationManager = conversationManager;
        // Mock the NPCContextGenerator directly
        NPCContextGenerator contextGenerator = mock(NPCContextGenerator.class);
        // Use reflection to set the final field
        try {
            Field field = ConversationManager.class.getDeclaredField("npcContextGenerator");
            field.setAccessible(true);

            // Create the mock
            NPCContextGenerator mockContextGenerator = mock(NPCContextGenerator.class);

            // Mock the methods
            when(mockContextGenerator.updateContext(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn("Updated context");
            when(mockContextGenerator.generateDefaultContext(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn("Default context");

            // Set the field using reflection
            field.set(conversationManager, mockContextGenerator);
        } catch (Exception e) {
            fail("Failed to set up test: " + e.getMessage());
        }

        // Mock methods
        when(contextGenerator.updateContext(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn("Updated context");
        when(contextGenerator.generateDefaultContext(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn("Default context");
    }

    @org.junit.jupiter.api.AfterEach
    public void tearDown() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
        if (loreBookManagerMock != null) {
            loreBookManagerMock.close();
        }
        if (essentialsUtilsMock != null) {
            essentialsUtilsMock.close();
        }
        if (pluginUtilsMock != null) {
            pluginUtilsMock.close();
        }

        if (seasonsMock != null) {
            seasonsMock.close();
        }

        //seasonsMock.close();

        Story.instance = null;
    }



    @Test
    public void testStartConversation() {
        // Arrange
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        when(plugin.getNPCData(anyString())).thenReturn(mockConfig);
        List<NPC> npcs = List.of(npc);

        ConversationManager conversationManager = plugin.conversationManager;

        // Act
        GroupConversation conversation = conversationManager.startGroupConversation(player, npcs);

        // log the conversation
        System.out.println("Conversation started with NPCs: " + npcs);
        System.out.println("Player: " + player.getName());
        System.out.println("Conversation: " + conversation);

        assertNotNull(conversation);
        assertTrue(conversation.isActive());


        // Assert
        assertTrue(conversationManager.hasActiveConversation(player));
    }

    @Test
    public void testEndConversation() {
        // Arrange
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        when(plugin.getNPCData(anyString())).thenReturn(mockConfig);
        List<NPC> npcs = List.of(npc);


        ConversationManager conversationManager = plugin.conversationManager;

        // Start conversation first
        conversationManager.startGroupConversation(player, npcs);
        assertTrue(conversationManager.hasActiveConversation(player));

        // Act
        conversationManager.endConversation(player);

        // Assert
        assertFalse(conversationManager.hasActiveConversation(player));
    }

    @Test
    public void testPlayerJoinsExistingThenStartsNewConversation() {
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
        // Add these lines to fix the NullPointerException
        UUID npc1Uuid = UUID.randomUUID();
        UUID npc2Uuid = UUID.randomUUID();
        when(npc1.getUniqueId()).thenReturn(npc1Uuid);
        when(npc2.getUniqueId()).thenReturn(npc2Uuid);

        when(npc1.getName()).thenReturn("NPC1");
        when(npc2.getName()).thenReturn("NPC2");
        when(npc1.isSpawned()).thenReturn(true);
        when(npc2.isSpawned()).thenReturn(true);

        FileConfiguration mockConfig = mock(FileConfiguration.class);
        when(plugin.getNPCData(anyString())).thenReturn(mockConfig);
        //doReturn(true).when(plugin).isPlayerInRangeOfNPC(any(Player.class), any(NPC.class));

        ConversationManager conversationManager = plugin.conversationManager;

        // Act - Player 1 starts conversation with NPC 1
        GroupConversation conversation1 = conversationManager.startGroupConversation(player1, List.of(npc1));

        // Assert that Player 1's conversation started
        assertNotNull(conversation1);
        assertTrue(conversation1.isActive());
        assertTrue(conversationManager.hasActiveConversation(player1));
        assertEquals(1, conversation1.getPlayers().size());
        assertTrue(conversation1.getPlayers().contains(player1Uuid));
        assertEquals(1, conversation1.getNPCs().size());
        assertEquals("NPC1", conversation1.getNpcNames().get(0));

        // Act - Player 2 joins the conversation
        boolean player2Joined = conversationManager.addPlayerToConversation(player2, "NPC1");

        // Assert Player 2 successfully joined
        assertTrue(player2Joined);
        assertEquals(2, conversation1.getPlayers().size());
        assertTrue(conversation1.getPlayers().contains(player2Uuid));

        // Act - Player 2 starts a new conversation with NPC 2
        GroupConversation conversation2 = conversationManager.startGroupConversation(player2, List.of(npc2));

        // Assert Player 2 has a new conversation and is no longer in the first one
        assertNotNull(conversation2);
        assertTrue(conversation2.isActive());
        assertTrue(conversationManager.hasActiveConversation(player2));
        assertEquals(1, conversation2.getPlayers().size());
        assertTrue(conversation2.getPlayers().contains(player2Uuid));
        assertEquals(1, conversation2.getNPCs().size());
        assertEquals("NPC2", conversation2.getNpcNames().get(0));

        // Assert Player 1's conversation is still active with only Player 1
        assertTrue(conversation1.isActive());
        assertEquals(1, conversation1.getPlayers().size());
        assertTrue(conversation1.getPlayers().contains(player1Uuid));
        assertTrue(conversationManager.hasActiveConversation(player1));

        // Check that we now have two separate active conversations
        assertEquals(2, conversationManager.getActiveConversations().size());
    }
}