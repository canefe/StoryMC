package com.canefe.story;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIBukkitConfig;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.*;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import kr.toxicity.healthbar.api.event.HealthBarCreateEvent;
import me.libraryaddict.disguise.DisguiseAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.MetadataValue;
import org.json.simple.JSONArray;
import org.mcmonkey.sentinel.SentinelTrait;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.*;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Story extends JavaPlugin implements Listener, CommandExecutor {

    static Story instance;

    // Toggle for NPC chat
    private boolean chatEnabled = true;

    // Map to store current NPC per player (UUID -> NPC Name)
    public Map<UUID, UUID> playerCurrentNPC = new HashMap<>();

    // List of players that disabled right-clicking start conversation
    private List<String> disabledPlayers = new ArrayList<>();

    private Set<UUID> disabledNPCs = new HashSet<>();

    private List<String> generalContexts = new ArrayList<>();

    // OpenAI API Key (Set this in your config.yml)
    private String openAIKey;

    private String defaultContext;

    private String aiModel;

    private String chatFormat;

    private String emoteFormat;

    private List<String> traitList;

    private List<String> quirkList;

    private List<String> motivationList;

    private List<String> flawList;

    private List<String> toneList;

    private int radiantRadius;

    private int chatRadius;

    private int responseDelay;


    // Gson instance for JSON parsing
    private Gson gson = new Gson();

    public NPCDataManager npcDataManager;

    public ConversationManager conversationManager;

    public LocationManager locationManager;

    public NPCUtils npcUtils;

    public NPCManager npcManager;

    private NPCScheduleManager scheduleManager;

    private boolean itemsAdderEnabled = false; // Check if ItemsAdder is enabled

    // PlaceholderAPI expansion String that supports color codes

    public String questTitle = "";
    public String questObj = "> No quests active.";
    private String tempQuestTitle;


    private final Map<NPC, Long> npcCooldowns = new HashMap<>();
    private static final long COOLDOWN_TIME = 30000; // 10 seconds in milliseconds


    public static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIBukkitConfig(this).verboseOutput(true)); // Load with verbose output
    }

    @Override
    public void onEnable() {

        instance = this;

        // Plugin startup logic
        getLogger().info("Story has been enabled!");

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Check if Sentinel and Citizens plugins are present
        if (Bukkit.getPluginManager().getPlugin("Sentinel") == null) {
            getLogger().warning("Sentinel plugin not found! NPC commands will not work.");
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().warning("Citizens plugin not found! NPC interactions will not work.");
        }

        // Check if ItemsAdder is available
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            itemsAdderEnabled = true;
            getLogger().info("ItemsAdder detected, avatar features enabled.");
        } else {
            getLogger().warning("ItemsAdder not found, avatar features will be disabled.");
        }

        CommandAPI.onEnable();
        // Register commands with the new CommandHandler
        CommandHandler commandHandler = new CommandHandler(this);
        getCommand("togglegpt").setExecutor(commandHandler);
        getCommand("aireload").setExecutor(commandHandler);
        getCommand("feednpc").setExecutor(commandHandler);
        getCommand("g").setExecutor(commandHandler);
        getCommand("setcurq").setExecutor(commandHandler);
        getCommand("endconv").setExecutor(commandHandler);

        // Command to hide the health bar "vanish"
        //BetterHealthBar.inst().playerManager().player("").uninject();


        new CommandAPICommand("maketalk")
                .withPermission("storymaker.chat.toggle")
                .withArguments(new GreedyStringArgument("npc"))
                .executesPlayer((player, args) -> {
                    String npcName = (String) args.get("npc");
                    player.sendMessage(ChatColor.GRAY + "You made NPC '" + npcName + "' talk using AI.");

                    // Fetch NPC conversation
                    GroupConversation convo = conversationManager.getActiveNPCConversation(npcName);
                    if (convo == null) {
                        player.sendMessage(ChatColor.RED + "No active conversation found for NPC '" + npcName + "'.");
                        return;
                    }
                    // get a random player name from the convo
                    //Player randomPlayer = Bukkit.getPlayer(convo.getPlayers().iterator().next());
                    conversationManager.generateGroupNPCResponses(convo, null);
                })
                .register();

        new CommandAPICommand("spawnnpc")
                .withPermission("storymaker.npc.spawn")
                .withArguments(new StringArgument("npc"))
                .withArguments(new GreedyStringArgument("message"))
                .executesPlayer((player, args) -> {
                    String npcName = (String) args.get("npc");
                    String message = (String) args.get("message");
                    Location location = player.getLocation();
                    NPCSpawner npcSpawner = new NPCSpawner(this);
                    // Get a location that is 10 block away from the player
                    location.add(location.getDirection().multiply(10));

                    npcSpawner.spawnNPC(npcName, location, player, message);
                })
                .register();

        new CommandAPICommand("startconv")
                .withPermission("storymaker.conversation.start")
                .withArguments(new PlayerArgument("player"))
                .executesPlayer((player, args) -> {
                    Player target = (Player) args.get("player");
                    NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(
                            playerCurrentNPC.get(player.getUniqueId()));
                    if (target == null) {
                        player.sendMessage(ChatColor.RED + "Player not found.");
                        return;
                    }
                    String npcName = npc.getName();
                    if (conversationManager.isNPCInConversation(npcName)) {
                        if (conversationManager.addPlayerToConversation(target, npcName)) {
                            player.sendMessage(ChatColor.GRAY + "Added " + target + " to the conversation with " + npcName);
                        } else {
                            player.sendMessage(ChatColor.RED + "" + target + " is already in the conversation.");
                        }
                    } else {
                        // Start a new conversation
                        List<NPC> npcs = new ArrayList<>();
                        npcs.add(npc);
                        conversationManager.startGroupConversation(target, npcs);
                    }
                })
                .register();

        // /fendconv <player>
        new CommandAPICommand("fendconv")
                .withPermission("storymaker.conversation.end")
                .withArguments(new PlayerArgument("player"))
                .executesPlayer((player, args) -> {
                    Player target = (Player) args.get("player");
                    if (conversationManager.hasActiveConversation(target)) {
                        conversationManager.endConversation(target);
                        player.sendMessage(ChatColor.GRAY + "Ended conversation with " + target.getName());
                    } else {
                        player.sendMessage(ChatColor.RED + target.getName() + " is not in an active conversation.");
                    }
                })
                .register();

        new CommandAPICommand("togglechat")
                .withPermission("storymaker.chat.toggle")
                .withOptionalArguments(new PlayerArgument("target"))
                .executesPlayer((player, args) -> {
                    Player target = (Player) args.get("target");
                    if (target != null) {
                        if (disabledPlayers.contains(target.getName())) {
                            disabledPlayers.remove(target.getName());
                            player.sendMessage(ChatColor.GRAY + "Enabled chat for " + target.getName());
                        } else {
                            disabledPlayers.add(target.getName());
                            player.sendMessage(ChatColor.GRAY + "Disabled chat for " + target.getName());
                        }
                    } else {
                        if (disabledPlayers.contains(player.getName())) {
                            disabledPlayers.remove(player.getName());
                            player.sendMessage(ChatColor.GRAY + "Enabled chat for yourself.");
                        } else {
                            disabledPlayers.add(player.getName());
                            player.sendMessage(ChatColor.GRAY + "Disabled chat for yourself.");
                        }
                        saveDataFiles();
                    }

                })
                .register();

        new CommandAPICommand("setcurnpc")
                .withPermission("storymaker.npc.set")
                .executesPlayer((player, args) -> {
                    //Get the NPC that the player is looking at
                    NPC npc = CitizensAPI.getNPCRegistry().getNPC(player.getTargetEntity(5));
                    if(npc == null) {
                        player.sendMessage(ChatColor.RED + "You are not looking at an NPC!");
                        return;
                    }

                    //Set the player's current NPC to the NPC they are looking at
                    playerCurrentNPC.put(player.getUniqueId(), npc.getUniqueId());
                })
                .register();


        new CommandAPICommand("storyqtitle")
                .withPermission("storymaker.quest.set")
                .withArguments(new GreedyStringArgument("title"))
                .executesPlayer((player, args) -> {
                    String title = (String) args.args()[0];
                    setQuestTitle(title);
                    player.sendMessage(ChatColor.GRAY + "Quest title set to: " + title);
                })
                .register();

        new CommandAPICommand("storyqobj")
                .withPermission("storymaker.quest.set")
                .withArguments(new GreedyStringArgument("objective"))
                .executesPlayer((player, args) -> {
                    String objective = (String) args.args()[0];
                    setQuestObj(objective);
                    player.sendMessage(ChatColor.GRAY + "Quest objective set to: " + objective);
                })
                .register();

        // /convadd <player> <npc> // add player to existing conv on with npc
        new CommandAPICommand("convadd")
                .withPermission("storymaker.conversation.add")
                .withArguments(new PlayerArgument("player"))
                .withArguments(new GreedyStringArgument("npc"))
                .executesPlayer((player, args) -> {
                    Player target = (Player) args.get("player");
                    String npcName = (String) args.get("npc");

                    if (conversationManager.isNPCInConversation(npcName)) {
                        if (conversationManager.addPlayerToConversation(target, npcName)) {
                            player.sendMessage(ChatColor.GRAY + "Added " + target + " to the conversation with " + npcName);
                        } else {
                            player.sendMessage(ChatColor.RED + "" + target + " is already in the conversation.");
                        }
                    } else {
                        player.sendMessage(ChatColor.RED + npcName + " is not in an active conversation.");
                    }
                })
                .register();


        // New command to reset quest title and objective
        new CommandAPICommand("resetquest")
                .withPermission("storymaker.quest.reset")
                .executesPlayer((player, args) -> {
                    setQuestTitle("");
                    setQuestObj("> No quests active.");
                    player.sendMessage(ChatColor.GRAY + "Quest title and objective reset.");
                })
                .register();

        // command /disablenpc <npc> // disable npc from talking
        new CommandAPICommand("togglenpc")
                .withPermission("storymaker.npc.disable")
                .withArguments(new GreedyStringArgument("npc"))
                .executesPlayer((player, args) -> {
                    String npcName = (String) args.get("npc");
                    if (npcDataManager.loadNPCData(npcName) != null) {
                        if (disabledNPCs.contains(getNPCUUID(npcName))) {
                            player.sendMessage(ChatColor.RED + "NPC " + npcName + " is already disabled. Enabling...");
                            enableNPCTalking(npcName);
                            return;
                        }
                        disableNPCTalking(npcName);
                        player.sendMessage(ChatColor.GRAY + "Disabled " + npcName + " from talking.");
                    } else {
                        player.sendMessage(ChatColor.RED + "NPC " + npcName + " not found.");
                    }
                })
                .register();


        // command /toggleradiant // disable radiant conversations
        new CommandAPICommand("toggleradiant")
                .withPermission("storymaker.conversation.toggle")
                .executesPlayer((player, args) -> {
                    if (conversationManager.isRadiantEnabled()) {
                        conversationManager.setRadiantEnabled(false);
                        player.sendMessage(ChatColor.GRAY + "Radiant conversations disabled.");
                    } else {
                        conversationManager.setRadiantEnabled(true);
                        player.sendMessage(ChatColor.GRAY + "Radiant conversations enabled.");
                    }
                })
                .register();

        // commant /npctalk <npc_id:int> <npc_id_target:int>
        new CommandAPICommand("npctalk")
                .withPermission("storymaker.npc.talk")
                .withArguments(new IntegerArgument("npc_id"))
                .withArguments(new IntegerArgument("npc_id_target"))
                .withArguments(new GreedyStringArgument("message"))
                .executesPlayer((player, args) -> {
                    int npcId = (int) args.get("npc_id");
                    int npcIdTarget = (int) args.get("npc_id_target");
                    String message = (String) args.get("message");
                    NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                    NPC target = CitizensAPI.getNPCRegistry().getById(npcIdTarget);
                    if (npc == null || target == null) {
                        player.sendMessage(ChatColor.RED + "NPC not found.");
                        return;
                    }
                    walkAndInitiateConversation(npc, target, message);
                })
                .register();
        // command /npcply <npc_id:int> <player_name> <message>
        new CommandAPICommand("npcply")
                .withPermission("storymaker.npc.talk")
                .withArguments(new IntegerArgument("npc_id"))
                .withArguments(new PlayerArgument("player"))
                .withArguments(new GreedyStringArgument("message"))
                .executesPlayer((player, args) -> {
                    int npcId = (int) args.get("npc_id");
                    Player target = (Player) args.get("player");
                    String message = (String) args.get("message");
                    NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                    if (npc == null || target == null) {
                        player.sendMessage(ChatColor.RED + "NPC not found.");
                        return;
                    }
                    npcManager.eventGoToPlayerAndSay(npc, target.getName(), message);
                })
                .register();

        // command /stopallconv
        new CommandAPICommand("stopallconv")
                .withPermission("storymaker.conversation.endall")
                .executesPlayer((player, args) -> {
                    conversationManager.StopAllConversations();
                    player.sendMessage(ChatColor.GRAY + "All conversations ended.");
                })
                .register();


        new CommandAPICommand("npcinit")
                .withPermission("storymaker.npc.init")
                .withArguments(new StringArgument("location"))
                .withArguments(new GreedyStringArgument("npc"))
                .executesPlayer((player, args) -> {
                    String npcName = (String) args.get("npc");
                    String location = (String) args.get("location");
                    NPCUtils.NPCContext NPCContext = NPCUtils.getInstance(this).getOrCreateContextForNPC(npcName);
                    String role = NPCContext.npcRole;
                    String context = NPCContext.context;
                    saveNPCData(npcName, role, context, NPCContext.conversationHistory, location);
                    player.sendMessage(ChatColor.GRAY + "NPC data saved for " + npcName);
                })
                .register();


        // Command to make player's current NPC walk to them
        new CommandAPICommand("comenfp")
                .withPermission("storymaker.npc.come")
                .withOptionalArguments(new FloatArgument("speed", 0.5f, 3.0f))
                .executesPlayer((player, args) -> {
                    // Get player's UUID
                    UUID playerUUID = player.getUniqueId();

                    // Check if player has a current NPC
                    if (!playerCurrentNPC.containsKey(playerUUID)) {
                        player.sendMessage(colorize("&cYou don't have a current NPC assigned. Use &f/setcurnpc&c first."));
                        return;
                    }

                    // Get the NPC UUID and find the NPC
                    UUID npcUUID = playerCurrentNPC.get(playerUUID);
                    NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUUID);

                    if (npc == null || !npc.isSpawned()) {
                        player.sendMessage(colorize("&cYour NPC is not available or not spawned."));
                        return;
                    }

                    // Get optional speed argument
                    float speed = (float) args.getOrDefault("speed", 1.0f);

                    // Get player's current location
                    Location playerLocation = player.getLocation();

                    // Make the NPC walk to the player
                    int taskId = npcManager.walkToLocation(
                            npc,
                            playerLocation,
                            2.0,  // Stop 2 blocks away
                            speed,
                            30,    // 30 second timeout
                            () -> player.sendMessage(colorize("&a" + npc.getName() + " has arrived.")),
                            () -> player.sendMessage(colorize("&c" + npc.getName() + " couldn't reach you."))
                    );

                    if (taskId != -1) {
                        player.sendMessage(colorize("&a" + npc.getName() + " is coming to you."));
                    }
                })
                .register();
        // command /conv <subcommand> <args>
        new CommandAPICommand("conv")
                .withPermission("storymaker.conversation")
                .withSubcommand(new CommandAPICommand("list")
                        .executesPlayer((player, args) -> {
                            player.sendMessage(ChatColor.GRAY + "Active conversations:");
                            for (Map.Entry<Integer, GroupConversation> entry : conversationManager.getActiveConversations().entrySet()) {
                                Integer id = entry.getKey();
                                GroupConversation convo = entry.getValue();
                                MiniMessage mm = MiniMessage.miniMessage();
                                String npcNames = String.join(", ", convo.getNpcNames());
                                String playerNames = convo.getPlayers().stream()
                                        .map(uuid -> {
                                            Player ply = Bukkit.getPlayer(uuid);
                                            return ply != null ? ply.getName() : "Unknown";
                                        })
                                        .collect(Collectors.joining(", "));

                                // Build the feed button
                                Component feedButton = mm.deserialize(
                                        "<click:suggest_command:'/conv feed " + id + " Make '>" +
                                                "<hover:show_text:'Add system message to conversation'>" +
                                                "<gray>[<green>Feed</green>]</gray></hover></click>"
                                );

                                // Build Talk button
                                // get a random npc name from the list
                                String randomNPCName = convo.getNpcNames().get(new Random().nextInt(convo.getNpcNames().size()));
                                Component talkButton = mm.deserialize(
                                        "<click:run_command:'/maketalk " + randomNPCName + "'>" +
                                                "<hover:show_text:'Make Conversation Continue'>" +
                                                "<gray>[<green>Talk</green>]</gray></hover></click>"
                                );

                                // Build End button
                                // First feed the conversation to indicate the end and make all NPCs say goodbye
                                Component endButton = mm.deserialize(
                                        "<click:run_command:'/conv end " + id + "'>" +
                                                "<hover:show_text:'End conversation'>" +
                                                "<gray>[<red>End</red>]</gray></hover></click>"
                                );

                                // Force End (no goodbye)
                                Component forceEndButton = mm.deserialize(
                                        "<click:run_command:'/conv fend " + id + "'>" +
                                                "<hover:show_text:'Force End conversation'>" +
                                                "<gray>[<red>F-End</red>]</gray></hover></click>"
                                );

                                // Add Button
                                Component addButton = mm.deserialize(
                                        "<click:suggest_command:'/conv add " + id + " \"'>" +
                                                "<hover:show_text:'Add NPC to conversation'>" +
                                                "<gray>[<green>Add</green>]</gray></hover></click>"
                                );

                                // Make NPC names clickable
                                Component clickableNPCNames = Component.empty();
                                boolean first = true;

                                for (String npcName : convo.getNpcNames()) {
                                    Component npcComponent = mm.deserialize(
                                            "<click:run_command:'/conv npc " + id + " " + npcName + "'>" +
                                                    "<hover:show_text:'Control " + npcName + "'>" +
                                                    "<aqua>" + npcName + "</aqua></hover></click>"
                                    );

                                    if (!first) {
                                        clickableNPCNames = clickableNPCNames.append(mm.deserialize("<gray>, </gray>"));
                                    } else {
                                        first = false;
                                    }
                                    clickableNPCNames = clickableNPCNames.append(npcComponent);
                                }

// Build the prefix with conversation ID
                                Component prefix = mm.deserialize(
                                        String.format("<gray>[<green>%d</green>] </gray>", id)
                                );

// Now append the clickable NPC names component
                                Component fullPrefix = prefix.append(clickableNPCNames);

// And finally append the player names
                                fullPrefix = fullPrefix.append(mm.deserialize(String.format("<gray>, <yellow>%s</yellow> </gray>", playerNames)));

// Modify the existing code to use fullPrefix instead of prefix when sending the message

                                // Final message = prefix + feedButton
                                Component commands = feedButton;
                                commands = commands.append(mm.deserialize("<gray> | </gray>"));
                                commands = commands.append(talkButton);
                                commands = commands.append(mm.deserialize("<gray> | </gray>"));
                                commands = commands.append(addButton);
                                commands = commands.append(mm.deserialize("<gray> | </gray>"));
                                commands = commands.append(forceEndButton);
                                commands = commands.append(mm.deserialize("<gray> | </gray>"));
                                commands = commands.append(endButton);

                                // Send to player
                                player.sendMessage(fullPrefix);
                                player.sendMessage(commands);

                            }
                        })
                )

                .withSubcommand(new CommandAPICommand("npc")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .withArguments(new GreedyStringArgument("npc_name")
                                .replaceSuggestions(ArgumentSuggestions.strings(info -> {
                                    // Get all NPCs from Citizens and convert to array
                                    List<String> npcNames = new ArrayList<>();
                                    for (NPC citizenNPC : CitizensAPI.getNPCRegistry()) {
                                        npcNames.add(citizenNPC.getName());
                                    }
                                    return npcNames.toArray(new String[0]);
                                })))
                        .executesPlayer((player, args) -> {
                            int id = (int) args.get("conversation_id");
                            String npcName = (String) args.get("npc_name");

                            // Verify conversation and NPC exist
                            GroupConversation convo = conversationManager.getConversationById(id);
                            if (convo == null || !convo.getNpcNames().contains(npcName)) {
                                player.sendMessage(ChatColor.RED + "Invalid conversation or NPC not in this conversation.");
                                return;
                            }

                            MiniMessage mm = MiniMessage.miniMessage();

                            player.sendMessage("  ");
                            // Build menu title
                            Component title = mm.deserialize("<gold>==== NPC Controls: <yellow>" + npcName + "</yellow> ====</gold>");
                            player.sendMessage(title);

                            // Menu options
                            player.sendMessage(mm.deserialize("<click:run_command:'/conv remove " + id + " " + npcName + "'><hover:show_text:'Remove this NPC from the conversation'><gray>[<red>Remove</red>]</gray></hover></click>")
                                    .append(mm.deserialize("<gray> | </gray>"))
                                    .append(mm.deserialize("<click:run_command:'/conv mute \" + id + \" \" + npcName + \"'><hover:show_text:'Toggle whether this NPC speaks'><gray>[<yellow>Mute</yellow>]</gray></hover></click>"))
                            );

                            // Back button
                            player.sendMessage(mm.deserialize("<click:run_command:'/conv list'><hover:show_text:'Back to conversation list'><gray>« Back to Conversations</gray></hover></click>"));
                        })
                )

                .withSubcommand(new CommandAPICommand("mute")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .withArguments(new StringArgument("npc_name"))
                        .executesPlayer((player, args) -> {
                            int id = (int) args.get("conversation_id");
                            String npcName = (String) args.get("npc_name");

                            // Verify conversation and NPC exist
                            GroupConversation convo = conversationManager.getConversationById(id);
                            if (convo == null || !convo.getNpcNames().contains(npcName)) {
                                player.sendMessage(ChatColor.RED + "Invalid conversation or NPC not in this conversation.");
                                return;
                            }

                            // Toggle mute status for the NPC in this conversation
                            if (isNPCDisabled(npcName)) {
                                enableNPCTalking(npcName);
                                player.sendMessage(ChatColor.GREEN + npcName + " will now speak in the conversation.");
                            } else {
                                disableNPCTalking(npcName);
                                player.sendMessage(ChatColor.GRAY + npcName + " has been muted in the conversation.");
                            }
                        })
                )

                // Remove NPC subcommand
// Remove NPC subcommand
                .withSubcommand(new CommandAPICommand("remove")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .withArguments(new GreedyStringArgument("npc_name"))
                        .executesPlayer((player, args) -> {
                            int id = (int) args.get("conversation_id");
                            String npcName = (String) args.get("npc_name");

                            // Verify conversation and NPC exist
                            GroupConversation convo = conversationManager.getConversationById(id);
                            if (convo == null || !convo.getNpcNames().contains(npcName)) {
                                player.sendMessage(ChatColor.RED + "Invalid conversation or NPC not in this conversation.");
                                return;
                            }

                            // Get the NPC entity
                            NPC npc = convo.getNPCByName(npcName);
                            if (npc == null || !npc.isSpawned()) {
                                player.sendMessage(ChatColor.RED + "NPC not found or not spawned.");
                                return;
                            }

                            // Remove NPC from conversation first
                            convo.removeNPC(npc);

                            // End conversation if no NPCs left
                            if (convo.getNPCs().isEmpty()) {
                                conversationManager.endConversation(convo);
                                player.sendMessage(ChatColor.GRAY + "Conversation ended as there are no NPCs left.");
                            } else {
                                player.sendMessage(ChatColor.GRAY + "Removed " + npcName + " from the conversation.");
                            }

                            // Make the NPC walk away using the NPCManager
                            if (npc.isSpawned()) {
                                npcManager.makeNPCWalkAway(npc, convo);
                                player.sendMessage(ChatColor.GREEN + npcName + " has been removed from the conversation and is walking away.");
                            }
                        })
                )


                .withSubcommand(new CommandAPICommand("endall")
                        .executesPlayer((player, args) -> {
                            conversationManager.StopAllConversations();
                            player.sendMessage(ChatColor.GRAY + "All conversations ended.");
                        })
                )
                // feed subcommand
                .withSubcommand(new CommandAPICommand("feed")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .withArguments(new GreedyStringArgument("message"))
                        .executesPlayer((player, args) -> {
                            int conversationId = (int) args.get("conversation_id");
                            String message = (String) args.get("message");
                            conversationManager.addSystemMessage(conversationManager.getConversationById(conversationId), message);
                            player.sendMessage(ChatColor.GRAY + "Added system message: " + message);
                        })
                )

                // Add this to your existing conv command chain
                .withSubcommand(new CommandAPICommand("add")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .withArguments(new TextArgument("npc_name")
                                .replaceSuggestions(ArgumentSuggestions.strings(info -> {
                                    // Get all NPCs from Citizens and convert to array with each name wrapped in quotes
                                    List<String> npcNames = new ArrayList<>();
                                    for (NPC citizenNPC : CitizensAPI.getNPCRegistry()) {
                                        // Add quotes around each individual NPC name
                                        npcNames.add("\"" + citizenNPC.getName() + "\"");
                                    }
                                    return npcNames.toArray(new String[0]);
                                })))
                        .withOptionalArguments(new GreedyStringArgument("greeting_message"))
                        .executesPlayer((player, args) -> {
                            int conversationId = (int) args.get("conversation_id");
                            String npcName = (String) args.get("npc_name");
                            String greetingMessage = args.get("greeting_message") != null ?
                                    (String) args.get("greeting_message") : null;

                            // Find the NPC by name instead of ID
                            NPC npc = null;
                            for (NPC citizenNPC : CitizensAPI.getNPCRegistry()) {
                                if (citizenNPC.getName().equalsIgnoreCase(npcName)) {
                                    npc = citizenNPC;
                                    break;
                                }
                            }

                            if (npc == null) {
                                player.sendMessage(ChatColor.RED + "NPC '" + npcName + "' not found.");
                                return;
                            }

                            // Rest of your existing code
                            GroupConversation conversation = conversationManager.getConversationById(conversationId);
                            if (conversation == null) {
                                player.sendMessage(ChatColor.RED + "Conversation with ID " + conversationId + " not found.");
                                return;
                            }

                            boolean success = conversationManager.addNPCToConversationWalk(npc, conversation, greetingMessage);
                            if (success) {
                                player.sendMessage(ChatColor.GRAY + "Added NPC " + npc.getName() + " to conversation #" + conversationId);
                            } else {
                                player.sendMessage(ChatColor.RED + "Failed to add NPC to conversation.");
                            }
                        }))


                // command /conv fend <id>
                .withSubcommand(new CommandAPICommand("fend")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .executesPlayer((player, args) -> {
                            int conversationId = (int) args.get("conversation_id");
                            GroupConversation conversation = conversationManager.getConversationById(conversationId);

                            if (conversation == null) {
                                player.sendMessage(ChatColor.RED + "Conversation with ID " + conversationId + " not found.");
                                return;
                            }

                            // End the conversation immediately without feeding
                            conversationManager.endConversation(conversation);
                            player.sendMessage(ChatColor.GRAY + "Force ended conversation with ID: " + conversationId);
                        })
                )
                // command /conv end <id>
                .withSubcommand(new CommandAPICommand("end")
                        .withArguments(new IntegerArgument("conversation_id"))
                        .executesPlayer((player, args) -> {
                            int conversationId = (int) args.get("conversation_id");
                            // find the conversation by id
                            GroupConversation conversation = conversationManager.getConversationById(conversationId);

                            // feed the conversation first, make them talk, only then end the conversation
                            if (conversation == null) {
                                player.sendMessage(ChatColor.RED + "Conversation with ID " + conversationId + " not found.");
                                return;
                            }

                            conversationManager.addSystemMessage(conversation,
                                    "Each NPC should now deliver a final line or action that reflects their current feelings and intentions. Let them exit the scene naturally — avoid stating that the conversation is ending."
                            );

                            conversationManager.generateGroupNPCResponses(conversation, null)
                                    .thenRun(() -> {
                                        conversationManager.endConversation(conversation);
                                        player.sendMessage(ChatColor.GRAY + "Ended conversation with ID: " + conversationId);
                                    });

                            player.sendMessage(ChatColor.YELLOW + "Ending... conversation with ID: " + conversationId);
                        })
                ).register();









        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) { //
            new StoryPlaceholderExpansion(this).register(); //
        }

        conversationManager = ConversationManager.getInstance(this);


        npcDataManager = NPCDataManager.getInstance(this);

        locationManager = LocationManager.getInstance(this);

        npcUtils = NPCUtils.getInstance(this);

        npcManager = NPCManager.getInstance(this);

        scheduleManager = NPCScheduleManager.getInstance(this);

        startProximityTask(this);

        loadGeneralContexts();
        loadDataFiles();

        // Save default config and load OpenAI API key
        this.saveDefaultConfig();
        openAIKey = this.getConfig().getString("openai.apikey", "");
        defaultContext = this.getConfig().getString("ai.defaultContext", "Default context");
        aiModel = this.getConfig().getString("openai.aiModel", "gryphe/mythomax-l2-13b:extended");
        chatFormat = this.getConfig().getString("ai.chatFormat", "<gray>%npc_name% <italic>:</italic></gray> <white>%message%</white>");
        emoteFormat = this.getConfig().getString("ai.emoteFormat", "<yellow><italic>$1</italic></yellow>");

        // Context Generation
        traitList = this.getConfig().getStringList("ai.traits");
        quirkList = this.getConfig().getStringList("ai.quirks");
        motivationList = this.getConfig().getStringList("ai.motivations");
        flawList = this.getConfig().getStringList("ai.flaws");
        toneList = this.getConfig().getStringList("ai.tones");

        radiantRadius = this.getConfig().getInt("ai.radiantRadius", 5);
        chatRadius = this.getConfig().getInt("ai.chatRadius", 5);
        responseDelay = this.getConfig().getInt("ai.responseDelay", 2);


        if (openAIKey.isEmpty()) {
            getLogger().warning("OpenAI API Key is not set in config.yml!");
        }
    }

    public void setChatEnabled(boolean enabled) {
        this.chatEnabled = enabled;
    }

    public boolean isChatEnabled() {
        return chatEnabled;
    }

    public int getResponseDelay() {
        return responseDelay;
    }

    public void disableNPCTalking(String npcName) {
        disabledNPCs.add(getNPCUUID(npcName));
        // also add to the disabled NPCs file
        saveDataFiles();


    }

    // getNpcDataManager()
    public NPCDataManager getNPCDataManager() {
        return npcDataManager;
    }

    public LocationManager getLocationManager() {
        return locationManager;
    }

    public NPCScheduleManager getNPCScheduleManager() {
        return scheduleManager;
    }

    public Location calculateConversationCenter(GroupConversation conversation) {
        List<Location> locations = new ArrayList<>();

        // Add NPC locations
        for (NPC npc : conversation.getNPCs()) {
            if (npc.isSpawned() && npc.getEntity() != null) {
                locations.add(npc.getEntity().getLocation());
            }
        }

        // Add player locations
        for (UUID playerUUID : conversation.getPlayers()) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                locations.add(player.getLocation());
            }
        }

        if (locations.isEmpty()) {
            return null;
        }

        // Calculate average position
        double x = 0, y = 0, z = 0;
        for (Location loc : locations) {
            x += loc.getX();
            y += loc.getY();
            z += loc.getZ();
        }

        return new Location(
                locations.get(0).getWorld(),
                x / locations.size(),
                y / locations.size(),
                z / locations.size()
        );
    }

    public void saveDataFiles() {
        // save disabled NPCs to disabled-npcs.yml
        FileConfiguration disabledNPCsConfig = new YamlConfiguration();
        List<String> disabledNPCList = new ArrayList<>();
        for (UUID uuid : disabledNPCs) {
            disabledNPCList.add(uuid.toString());
        }
        disabledNPCsConfig.set("disabled-npcs", disabledNPCList);

        try {
            disabledNPCsConfig.save(new File(getDataFolder(), "disabled-npcs.yml"));
        } catch (Exception e) {
            getLogger().severe("Could not save disabled NPCs to file: " + e.getMessage());
        }

        // save disabled players to disabled-players.yml
        FileConfiguration disabledPlayersConfig = new YamlConfiguration();
        disabledPlayersConfig.set("disabled-players", disabledPlayers);
        try {
            disabledPlayersConfig.save(new File(getDataFolder(), "disabled-players.yml"));
        } catch (Exception e) {
            getLogger().severe("Could not save disabled players to file: " + e.getMessage());
        }

    }

    public void enableNPCTalking(String npcName) {
        disabledNPCs.remove(getNPCUUID(npcName));

        saveDataFiles();

    }

    public boolean isPlayerDisabled(Player player) {
        return disabledPlayers.contains(player.getName());
    }

    public void saveNPCData(String npcName, String roleDescription, String context, List<ConversationMessage> conversationHistory, String location) {
        FileConfiguration config = npcDataManager.loadNPCData(npcName);

        // Save role and context
        config.set("role", roleDescription);
        config.set("context", context);

        // Save conversation history
        List<Map<String, String>> historyList = new ArrayList<>();
        for (ConversationMessage message : conversationHistory) {
            Map<String, String> map = new HashMap<>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            historyList.add(map);
        }
        config.set("conversationHistory", historyList);

        // Save location
        if (location != null) {
            config.set("location", location);
        }

        npcDataManager.saveNPCFile(npcName, config);
    }






    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("AIStorymaker has been disableds.");
        CommandAPI.onDisable();
        if (scheduleManager != null) {
            scheduleManager.shutdown();
        }
    }

    public void reloadPluginConfig(Player player) {
        try {
            // Reload the config file
            this.reloadConfig();
            openAIKey = this.getConfig().getString("openai.apikey", "");

            // Any additional config values to reload can be added here
            player.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully.");

            if (openAIKey.isEmpty()) {
                getLogger().warning("OpenAI API Key is not set in the config.yml!");
                player.sendMessage(ChatColor.YELLOW + "Warning: OpenAI API Key is missing.");
            }

            defaultContext = this.getConfig().getString("ai.defaultContext", "Default context");
            aiModel = this.getConfig().getString("openai.aiModel", "gryphe/mythomax-l2-13b:extended");
            chatFormat = this.getConfig().getString("ai.chatFormat", "<gray>%npc_name% <italic>:</italic></gray> <white>%message%</white>");
            emoteFormat = this.getConfig().getString("ai.emoteFormat", "<yellow><italic>$1</italic></yellow>");
            traitList = this.getConfig().getStringList("ai.traits");
            quirkList = this.getConfig().getStringList("ai.quirks");
            motivationList = this.getConfig().getStringList("ai.motivations");
            flawList = this.getConfig().getStringList("ai.flaws");
            toneList = this.getConfig().getStringList("ai.tones");

            radiantRadius = this.getConfig().getInt("ai.radiantRadius", 5);
            chatRadius = this.getConfig().getInt("ai.chatRadius", 5);
            responseDelay = this.getConfig().getInt("ai.responseDelay", 2);

            loadGeneralContexts();
            loadDataFiles();
            conversationManager.reloadConfig();
            scheduleManager.reloadSchedules();
            locationManager.loadAllLocations();


        } catch (Exception e) {
            getLogger().severe("Failed to reload configuration: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to reload configuration. Check console for details.");
            e.printStackTrace();
        }
    }

    public void sendNpcMessage(String npcName, String message) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "c " + npcName + " " + message);
    }

    private boolean isVanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }

    public void startProximityTask(Story plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!conversationManager.isRadiantEnabled())
                return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                handleProximityCheck(player);
            }
        }, 20L * 5, 20L * 10); // Run every second (20 ticks)
    }


    private void handleProximityCheck(Player player) {
        // Disable if player is disabled
        if (disabledPlayers.contains(player.getName())) {
            return;
        }

        // Get nearby NPCs using your improved method
        List<NPC> nearbyNPCs = getNearbyNPCs(player, radiantRadius);

        // Only trigger single radiant
        boolean found = false;
        // Filter out NPCs that are disabled or on cooldown
        for (NPC npc : nearbyNPCs) {
            // Check if disabled
            if (disabledNPCs.contains(npc.getUniqueId())) {
                continue;
            }

            // Check cooldown
            if (isOnCooldown(npc)) {
                continue;
            }

            if (found) {
                break; // Only trigger one radiant conversation
            }

            found = true;

            // Trigger conversation with this NPC
            triggerRadiantConversation(npc, player);
            updateCooldown(npc);
        }
    }

    private boolean isOnCooldown(NPC npc) {
        if (!npcCooldowns.containsKey(npc)) return false;
        long currentTime = System.currentTimeMillis();
        long remainingTime = (currentTime - npcCooldowns.get(npc));
        boolean isOnCooldown = remainingTime < COOLDOWN_TIME;
        return npcCooldowns.containsKey(npc) && isOnCooldown;
    }

    private void updateCooldown(NPC npc) {
        npcCooldowns.put(npc, System.currentTimeMillis());
    }

    public void walkAndInitiateConversation(NPC actor, NPC target, String firstMessage) {
        // Walk to the target NPC
        npcManager.walkToNPC(actor, target, firstMessage);
    }

    private void triggerRadiantConversation(NPC initiator, Player player) {
        // Get initiator NPC's name
        String initiatorName = initiator.getName();

        // Check if initiator is already in a conversation
        if (conversationManager.isNPCInConversation(initiatorName)) {
            return;
        }
        // If player already in a conversation don't trigger
        if (conversationManager.isPlayerInConversation(player)) {
            return;
        }

        // NPC has a chance to start a conversation - decide between Player or NPC target
        Random random = new Random();
        boolean choosePlayer = random.nextBoolean(); // 50% chance to choose player

        if (choosePlayer && player != null && !isVanished(player) && !disabledPlayers.contains(player.getName())) {
            // Target is player - make NPC walk to player
            initiateRadiantPlayerConversation(initiator, player);
        } else {
            // Target is another NPC - find nearby NPC and walk to them
            initiateRadiantNPCConversation(initiator);
        }
    }

    private void initiateRadiantPlayerConversation(NPC initiator, Player player) {
        String initiatorName = initiator.getName();
        NPCUtils.NPCContext npcContext = NPCUtils.getInstance(this).getOrCreateContextForNPC(initiatorName);

        // Generate greeting before walking
        CompletableFuture.runAsync(() -> {
            try {
                List<ConversationMessage> prompts = new ArrayList<>();

                String playerName = player.getName();
                if (EssentialsUtils.getNickname(playerName) != null) {
                    playerName = EssentialsUtils.getNickname(playerName);
                }

                // Add system context
                prompts.add(new ConversationMessage("system",
                        "You are " + initiatorName + ". You've just noticed " + playerName +
                                " and decided to initiate a conversation. Generate a brief, natural greeting " +
                                "to start the conversation based on your character, relations, and context."));

                // Add general and location contexts
                getGeneralContexts().forEach(context ->
                        prompts.add(new ConversationMessage("system", context)));

                if (npcContext.location != null) {
                    npcContext.location.getContext().forEach(context ->
                            prompts.add(new ConversationMessage("system", context)));
                }

                // Add Relations
                prompts.add(new ConversationMessage("system", "Relations: " + npcContext.relations.toString()));

                // Get AI-generated greeting
                String greeting = getAIResponse(prompts);

                // Use NPCManager to make initiator walk to player with the greeting
                Bukkit.getScheduler().runTask(this, () -> {
                    // Make NPC walk to player and start conversation with greeting
                    npcManager.eventGoToPlayerAndTalk(initiator, player, greeting, null);

                    // Mark NPC as on cooldown
                    updateCooldown(initiator);
                });
            } catch (Exception e) {
                getLogger().warning("Error generating greeting for NPC " + initiatorName);
                e.printStackTrace();
            }
        });
    }

    private void initiateRadiantNPCConversation(NPC initiator) {
        // Find nearby NPCs that aren't in a conversation
        List<NPC> nearbyNPCs = getNearbyNPCs(initiator, radiantRadius);

        // filter the ones that already in conversation and in cooldown or disabled

        nearbyNPCs.removeIf(npc -> conversationManager.isNPCInConversation(npc.getName()) ||
                isOnCooldown(npc) || disabledNPCs.contains(npc.getUniqueId()));

        if (nearbyNPCs.isEmpty()) return;

        // Choose a random NPC to talk to
        Random random = new Random();
        NPC targetNPC = nearbyNPCs.get(random.nextInt(nearbyNPCs.size()));

        // Generate AI greeting
        String initiatorName = initiator.getName();
        NPCUtils.NPCContext npcContext = npcUtils.getOrCreateContextForNPC(initiatorName);

        CompletableFuture.runAsync(() -> {
            try {
                List<ConversationMessage> prompts = new ArrayList<>();

                // Add target NPC context
                prompts.add(new ConversationMessage("system", npcContext.context));

                // Add general and location contexts
                getGeneralContexts().forEach(context ->
                        prompts.add(new ConversationMessage("system", context)));

                if (npcContext.location != null) {
                    npcContext.location.getContext().forEach(context ->
                            prompts.add(new ConversationMessage("system", context)));
                }

                prompts.add(new ConversationMessage("system", "Relations: " + npcContext.relations.toString()));

                // include last 5 messages from the conversation history
                List<ConversationMessage> history = npcContext.conversationHistory;

                int start = Math.max(0, history.size() - 10);
                for (int i = start; i < history.size(); i++) {
                    ConversationMessage message = history.get(i);
                    prompts.add(new ConversationMessage(message.getRole(), message.getContent()));
                }

                // Add system context
                prompts.add(new ConversationMessage("system",
                        "You are " + initiatorName + ". You've noticed " + targetNPC.getName() +
                                " nearby and decided to initiate a conversation. Generate a brief, natural greeting " +
                                "to start the conversation based on your character."));




                // Get AI-generated greeting
                String greeting = getAIResponse(prompts);

                // Use NPCManager to make initiator walk to target with AI greeting
                Bukkit.getScheduler().runTask(this, () -> {
                    npcManager.walkToNPC(initiator, targetNPC, greeting);

                    // Mark both NPCs as on cooldown
                    updateCooldown(initiator);
                    updateCooldown(targetNPC);
                });
            } catch (Exception e) {
                getLogger().warning("Error generating greeting for NPC " + initiatorName);
                e.printStackTrace();
            }
        });
    }

    public void scheduleProximityCheck(Player player, NPC npc, GroupConversation conversation) {
        final int PROXIMITY_CHECK_DELAY = 10; // Check every 10 seconds
        final double MAX_DISTANCE = chatRadius; // Use the same chat radius

        int taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Check if conversation is still active
            if (!conversation.isActive()) {
                return;
            }

            // Check if player is still nearby
            if (player == null || !player.isOnline() || npc == null || !npc.isSpawned()) {
                conversationManager.endConversation(conversation);
                return;
            }

            Location playerLoc = player.getLocation();
            Location npcLoc = npc.getEntity().getLocation();

            if (playerLoc.getWorld() != npcLoc.getWorld() ||
                    playerLoc.distance(npcLoc) > MAX_DISTANCE) {
                // Player moved away, end conversation
                player.sendMessage(ChatColor.GRAY + "The conversation with " + npc.getName() +
                        " has ended as you moved away.");
                conversationManager.endConversation(conversation);
            }
        }, PROXIMITY_CHECK_DELAY * 20L, PROXIMITY_CHECK_DELAY * 20L).getTaskId();

        // Store the task for cleanup
        conversationManager.getScheduledTasks().put(conversation, taskId);
    }

    private void startRadiantConversation(NPC npc1, NPC npc2, Player player) {
        // Start a conversation between two NPCs
        List<NPC> npcs = new ArrayList<>();
        npcs.add(npc1);
        npcs.add(npc2);
        conversationManager.startRadiantConversation(npcs);
    }

    public boolean isNPCDisabled(String npcName) {
        return disabledNPCs.contains(getNPCUUID(npcName));
    }


    // Check if two chunks are adjacent or the same
    private boolean isNearbyChunk(Chunk chunk1, Chunk chunk2) {
        int dx = Math.abs(chunk1.getX() - chunk2.getX());
        int dz = Math.abs(chunk1.getZ() - chunk2.getZ());
        return dx <= 1 && dz <= 1;
    }


    @EventHandler
    public void onHealthbarCreated(HealthBarCreateEvent event) {
        // if event entity is a player and vanished cancel
        if (event.getEntity().entity() instanceof Player && isVanished((Player) event.getEntity().entity())) {
            event.setCancelled(true);
        }
        // if event entity is a player and lib disguised then return disguise name
        
        if (DisguiseAPI.isDisguised(event.getEntity().entity())) {
            // there is no setCustomName method in HealthBarCreateEvent what do I do?
            if (event.getEntity().entity() instanceof Player) {
               event.setCancelled(true);
            }
        }

    }

    @EventHandler
    // Player drop item
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        // Check if in conversation
        if (conversationManager.hasActiveConversation(player)) {
            // add as a system message what he dropped
           GroupConversation convo = conversationManager.getActiveConversation(player);
            convo.addMessage(new ConversationMessage("system", EssentialsUtils.getNickname(player.getName()) + " dropped " + event.getItemDrop().getItemStack().getType().name() + " amount " + event.getItemDrop().getItemStack().getAmount()));
        }
    }

    @EventHandler
    // Player Pickup item
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        // Check if in conversation
        if (conversationManager.hasActiveConversation(player)) {
            // add as a system message what he picked up
            GroupConversation convo = conversationManager.getActiveConversation(player);
            convo.addMessage(new ConversationMessage("system", EssentialsUtils.getNickname(player.getName()) + " picked up " + event.getItem().getItemStack().getType().name() + " amount " + event.getItem().getItemStack().getAmount()));
        }
    }

    /**
     * Handles the conversation logic between a player and an NPC.
     * This method determines whether to join an existing conversation,
     * create a new one, or end a conversation based on the current state.
     *
     * @param player The player interacting with the NPC
     * @param npc The NPC being interacted with
     * @return true if the interaction was handled, false otherwise
     */
    /**
     * Handles the conversation logic between a player and an NPC.
     * This method determines whether to join an existing conversation,
     * create a new one, or end a conversation based on the current state.
     *
     * @param player The player interacting with the NPC
     * @param npc The NPC being interacted with
     * @param isDirectInteraction Whether this is a direct interaction (right-click) or chat
     * @return true if the interaction was handled, false otherwise
     */
    private boolean handleConversation(Player player, NPC npc, boolean isDirectInteraction) {
        // Safety checks
        if (player == null || npc == null) {
            return false;
        }

        // Check if player has disabled interactions
        if (disabledPlayers.contains(player.getName())) {
            playerCurrentNPC.put(player.getUniqueId(), npc.getUniqueId());
            return true;
        }

        String npcName = npc.getName();
        UUID playerUUID = player.getUniqueId();

        // Check if player is already in a conversation
        if (conversationManager.hasActiveConversation(player)) {
            // Only end the conversation on direct interaction (right-click), not during chat
            if (isDirectInteraction && conversationManager.isNPCInConversation(player, npcName)) {
                conversationManager.endConversation(player);
                return true;
            }

            // Check if NPC is disabled/busy
            if (disabledNPCs.contains(npc.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + npcName + " " + ChatColor.RED + "is busy.");
                return true;
            }

            // Add this NPC to the existing conversation
            conversationManager.addNPCToConversation(player, npc);
            return true;
        } else {
            // Player is not in a conversation

            // Check if NPC is disabled/busy
            if (disabledNPCs.contains(npc.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + npcName + " " + ChatColor.RED + "is busy.");
                return true;
            }

            // Check if NPC is already in a conversation with someone else
            if (conversationManager.isNPCInConversation(npcName)) {
                // Join the existing conversation
                conversationManager.addPlayerToConversation(player, npcName);
                return true;
            }

            // Start a new conversation with this NPC
            List<NPC> npcs = new ArrayList<>();
            npcs.add(npc);
            conversationManager.startGroupConversation(player, npcs);

            // Set current NPC for the player
            playerCurrentNPC.put(playerUUID, npc.getUniqueId());
            player.sendMessage(ChatColor.GRAY + "You are now talking to " + npcName + ".");
            return true;
        }
    }

    // Event: PlayerInteractEntityEvent
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // Ignore off-hand interactions
        }
        Player player = event.getPlayer();
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());
        if (npc == null) {
            return;
        }

        // Handle the conversation logic
        boolean handled = handleConversation(player, npc, true);

        // If NPC has SentinelTrait, open a custom GUI for commands
        if (handled && npc.hasTrait(SentinelTrait.class)) {
            SentinelTrait sentinel = npc.getTrait(SentinelTrait.class);
            // Open GUI for NPC commands (e.g., Follow Me)
            // For simplicity, we'll skip GUI implementation in this example
        }
    }


    public CompletableFuture<Location> getNPCPos(String npcName) {
        // Return a CompletableFuture for the NPC's position
        return npcUtils.getNPCByNameAsync(npcName).thenApply(npc -> {
            if (npc != null && npc.getEntity() != null) {
                return npc.getEntity().getLocation(); // Return the NPC's location
            } else {
                return null; // NPC not found or doesn't have an entity
            }
        });
    }



    // getNPCByName async method
    public NPC getNPCByName(String npcName) {
        NPC foundNPC = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(npcName)) {
                foundNPC = npc;
                break;
            }
        }
        return foundNPC;
    }

    public UUID getNPCUUID(String npcName) {
        NPC foundNPC = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(npcName)) {
                foundNPC = npc;
                break;
            }
        }
        if (foundNPC != null) {
            return foundNPC.getUniqueId();
        } else {
            return null;
        }
    }




    // Event: AsyncPlayerChatEvent
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Check if player is enabled in DisabledPlayers
        if (disabledPlayers.contains(player.getName())) {
            return;
        }

        // First, collect all nearby NPCs
        List<NPC> nearbyNPCs = getNearbyNPCs(player, chatRadius);

        // Check if any nearby NPC is already in a conversation with another player
        boolean joinedExistingConversation = false;
        for (NPC npc : nearbyNPCs) {
            if (!disabledNPCs.contains(npc.getUniqueId()) &&
                    conversationManager.isNPCInConversation(npc.getName())) {

                // Get the conversation this NPC is already part of
                GroupConversation existingConvo = conversationManager.getActiveNPCConversation(npc.getName());

                // Check if this is a different conversation than what the player is currently in
                if (existingConvo != null &&
                        (!conversationManager.hasActiveConversation(player) ||
                                !existingConvo.equals(conversationManager.getActiveConversation(player)))) {

                    // End the player's current conversation if they're in one
                    if (conversationManager.hasActiveConversation(player)) {
                        conversationManager.endConversation(player);
                    }

                    // Add the player to the existing conversation
                    conversationManager.addPlayerToConversation(player, npc.getName());

                    // Add the player's message to the conversation
                    if (conversationManager.hasActiveConversation(player)) {
                        conversationManager.addPlayerMessage(player, message, chatEnabled);
                    }

                    joinedExistingConversation = true;
                    break;
                }
            }
        }

        // Only proceed with normal handling if player didn't join an existing conversation
        if (!joinedExistingConversation) {
            // Handle conversations with nearby NPCs as before
            for (NPC npc : nearbyNPCs) {
                if (!disabledNPCs.contains(npc.getUniqueId())) {
                    handleConversation(player, npc, false);

                    // If player now has an active conversation, add the message
                    if (conversationManager.hasActiveConversation(player)) {
                        conversationManager.addPlayerMessage(player, message, chatEnabled);

                    }
                }
            }

            // Check for NPCs that need to be removed from player's conversation
            if (conversationManager.hasActiveConversation(player)) {
                // Get all NPCs in the player's conversation
                GroupConversation conversation = conversationManager.getActiveConversation(player);
                if (conversation != null) {
                    // Check each NPC in the conversation if they're still nearby
                    List<String> npcNames = new ArrayList<>(conversation.getNpcNames());
                    for (String npcName : npcNames) {
                        NPC npc = getNPCByName(npcName);
                        if (npc != null && npc.getEntity() != null &&
                                !nearbyNPCs.contains(npc)) {
                            // NPC is in conversation but no longer nearby
                            conversationManager.removeNPCFromConversation(player, npc, !nearbyNPCs.isEmpty());
                        }
                    }
                }
            }
        }
    }


    public String randomColor(String npcName) {
        int hash = Math.abs(npcName.hashCode()); // Ensure non-negative value

        // Convert hash into an HSL-based color for better distribution
        float hue = (hash % 360) / 360.0f; // Keep within 0-1 range
        float saturation = 0.7f; // 70% saturation (not too gray)
        float brightness = 0.8f; // 80% brightness (not too dark)

        Color color = Color.getHSBColor(hue, saturation, brightness);

        // Convert to hex format
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    // Improved nearby NPC detection
    private List<NPC> getNearbyNPCs(Player player, int radius) {
        List<NPC> nearby = new ArrayList<>();
        Location playerLoc = player.getLocation();

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
                continue; // Skip invalid NPCs
            }

            try {
                Location npcLoc = npc.getEntity().getLocation();

                // First check if they're in the same world
                if (npcLoc.getWorld() != null && playerLoc.getWorld() != null &&
                        npcLoc.getWorld().equals(playerLoc.getWorld())) {

                    // Calculate squared distance first (more efficient)
                    double distanceSquared = npcLoc.distanceSquared(playerLoc);
                    double radiusSquared = radius * radius;

                    if (distanceSquared <= radiusSquared) {
                        nearby.add(npc);
                        //getLogger().info("Found nearby NPC: " + npc.getName() + " at distance: " +
                                //Math.sqrt(distanceSquared));
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Error calculating distance to NPC " + npc.getName() + ": " + e.getMessage());
            }
        }

        return nearby;
    }

    // Get nearby NPCs relative to another NPC
    private List<NPC> getNearbyNPCs(NPC sourceNPC, int radius) {
        List<NPC> nearby = new ArrayList<>();

        if (sourceNPC == null || !sourceNPC.isSpawned() || sourceNPC.getEntity() == null) {
            return nearby; // Return empty list if source NPC is invalid
        }

        Location sourceLocation = sourceNPC.getEntity().getLocation();

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null || npc.equals(sourceNPC)) {
                continue; // Skip invalid NPCs and the source NPC itself
            }

            try {
                Location npcLoc = npc.getEntity().getLocation();

                // Check if they're in the same world
                if (npcLoc.getWorld() != null && sourceLocation.getWorld() != null &&
                        npcLoc.getWorld().equals(sourceLocation.getWorld())) {

                    // Calculate squared distance (more efficient)
                    double distanceSquared = npcLoc.distanceSquared(sourceLocation);
                    double radiusSquared = radius * radius;

                    if (distanceSquared <= radiusSquared) {
                        nearby.add(npc);
                        //getLogger().info("Found nearby NPC: " + npc.getName() + " at distance: " +
                                //Math.sqrt(distanceSquared) + " from " + sourceNPC.getName());
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Error calculating distance between NPCs " + sourceNPC.getName() +
                        " and " + npc.getName() + ": " + e.getMessage());
            }
        }

        return nearby;
    }

    public void broadcastNPCMessage(String aiResponse, String defaultNpcName, boolean shouldFollow, NPC finalNpc, UUID playerUUID, Player player, String avatar, String color) {
        MiniMessage mm = MiniMessage.miniMessage();
        int maxLineWidth = 40; // Adjust based on desired character limit per line
        String padding = "             "; // Space padding to align text with the image

        // Split response into lines to handle multi-line input
        String[] lines = aiResponse.split("\\n+");
        List<Component> parsedMessages = new ArrayList<>();

        String currentNpcName = defaultNpcName;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue; // Skip empty lines

            // Regex to capture NPC name and dialogue/emote
            Pattern pattern = Pattern.compile("^([A-Za-z0-9_\\s]+):\\s*(.*)$");
            Matcher matcher = pattern.matcher(line);

            String cleanedMessage = line; // Default to the full line if no match

            if (matcher.find()) {
                currentNpcName = matcher.group(1).trim(); // Extract NPC name
                cleanedMessage = matcher.group(2).trim(); // Extract message content

                // Remove all duplicate NPC name prefixes (e.g., "Ubyr: Ubyr: Ubyr:")
                while (cleanedMessage.startsWith(currentNpcName + ":")) {
                    cleanedMessage = cleanedMessage.substring(currentNpcName.length() + 1).trim();
                }
            }

            // Handle emotes inside the message (*content*)
            String formattedMessage = cleanedMessage.replaceAll("\\*(.*?)\\*", "<gray><italic>$1</italic></gray>");

            // Split the message into multiple lines to fit within the width limit
            List<String> wrappedLines = wrapTextWithFormatting(formattedMessage, maxLineWidth);
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
            // Send the NPC header (name above the text) use color in a tag
// In the broadcastNPCMessage method:

// Get avatar as a string with legacy formatting
            String rawAvatar = "";
            if (!Objects.equals(avatar, "")) {
                try {
                    if (itemsAdderEnabled) {
                        rawAvatar = new FontImageWrapper("npcavatars:" + avatar).getString();
                    } else {
                        rawAvatar = "            ";
                    }
                } catch (Exception e) {
                    getLogger().warning("Error loading avatar for NPC " + currentNpcName + ": " + e.getMessage());
                    rawAvatar = "            ";
                }
            } else {
                rawAvatar = "            ";
            }

// Create components directly
            Component avatarComponent = LegacyComponentSerializer.legacySection().deserialize(rawAvatar);
            // add two spaces in between
            Component nameComponent = mm.deserialize(" <" + color + ">" + currentNpcName + "</" + color + ">");

// Combine them
            parsedMessages.add(Component.empty().append(avatarComponent).append(nameComponent));

            // Add padding spaces before each wrapped line for alignment
            for (String wrappedLine : wrappedLines) {
                parsedMessages.add(mm.deserialize(padding + wrappedLine));
            }
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
            parsedMessages.add(mm.deserialize(padding)); // Empty line for spacing
        }

        // Dispatch all parsed messages to players
        String finalCurrentNpcName = currentNpcName;

        Bukkit.getScheduler().runTask(this, () -> {
            // Only send message to players who are nearby OR have permission
            Location npcLocation = null;
            if (finalNpc != null && finalNpc.getEntity() != null) {
                npcLocation = finalNpc.getEntity().getLocation();
            } else {
                return; // No valid NPC location
            }

            // Determine which players can see the message
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean shouldSee = false;

                // Check for admin permission to bypass distance check
                if (p.hasPermission("storymaker.npc.hearglobal")) {
                    shouldSee = true;
                }
                // Check distance if we have a valid NPC location
                else if (npcLocation != null) {
                    if (p.getWorld().equals(npcLocation.getWorld()) &&
                            p.getLocation().distance(npcLocation) <= chatRadius) {
                        shouldSee = true;
                    }
                }

                // Send messages to players who should see them
                if (shouldSee) {
                    for (Component message : parsedMessages) {
                        p.sendMessage(message);
                    }
                }
            }

            // Handle follow logic for the last NPC message
            if (shouldFollow && finalNpc != null && finalNpc.hasTrait(SentinelTrait.class) &&
                    playerUUID != null && player != null) {
                // Existing follow logic remains unchanged
                SentinelTrait sentinel = finalNpc.getTrait(SentinelTrait.class);
                // Handle sentinel follow logic
            }
        });
    }

    /**
     * Splits a message into multiple lines, each with a maximum width.
     */
    private List<String> wrapTextWithFormatting(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        int safetyLimit = 100; // to avoid infinite loops

        while (!text.isEmpty() && safetyLimit-- > 0) {
            // if the remaining plain text fits, we're done
            if (getPlainTextLength(text) <= maxWidth) {
                result.add(text);
                break;
            }

            // Find a break point based solely on plain text length.
            int breakPoint = findBreakPoint(text, maxWidth);
            String line = text.substring(0, breakPoint).trim();
            String remainingText = text.substring(breakPoint).trim();

            // Get all active (open but not closed) tags at the end of 'line'
            List<String> activeTags = getActiveTags(line);
            // Append closing tags in reverse order to properly close them
            StringBuilder closingTags = new StringBuilder();
            for (int i = activeTags.size() - 1; i >= 0; i--) {
                closingTags.append(getClosingTag(activeTags.get(i)));
            }
            String finalizedLine = line + closingTags.toString();
            result.add(finalizedLine);

            // For the next line, prepend the active tags so that the formatting continues
            StringBuilder reopening = new StringBuilder();
            for (String tag : activeTags) {
                reopening.append(tag);
            }
            text = reopening.toString() + remainingText;
        }

        return result;
    }

    private int getPlainTextLength(String text) {
        // Remove all tags and return the length of what remains.
        return text.replaceAll("<[^>]+>", "").length();
    }

    private int findBreakPoint(String text, int maxWidth) {
        int count = 0;
        int lastSpaceIndex = -1;
        boolean inTag = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                inTag = true;
            }
            if (!inTag) {
                count++;
                if (c == ' ') {
                    lastSpaceIndex = i;
                }
            }
            if (c == '>') {
                inTag = false;
            }
            if (count >= maxWidth) {
                return (lastSpaceIndex != -1) ? lastSpaceIndex : i + 1;
            }
        }
        return text.length();
    }

    private List<String> getActiveTags(String text) {
        // This method parses the string and returns a list of all formatting tags that have been opened but not closed.
        List<String> active = new ArrayList<>();
        Pattern pattern = Pattern.compile("</?(gray|italic)>");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag.startsWith("</")) {
                // For a closing tag, remove the last matching open tag if it exists.
                String openTag = "<" + tag.substring(2);
                int lastIndex = active.lastIndexOf(openTag);
                if (lastIndex != -1) {
                    active.remove(lastIndex);
                }
            } else {
                active.add(tag);
            }
        }
        return active;
    }

    private String getClosingTag(String openTag) {
        // Simply turn an opening tag into its corresponding closing tag.
        if (openTag.startsWith("<") && openTag.endsWith(">")) {
            return "</" + openTag.substring(1);
        }
        return "";
    }







    public void addUserMessage(String npcName, String playerName, String message) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("user", playerName + ": " + message));
        saveNPCConversationHistory(npcName, npcConversationHistory);
    }

    public void addUserMessageToHistory(List<ConversationMessage> messageHistory, String playerName, String message) {
        messageHistory.add(new ConversationMessage("user", playerName + ": " + message));
    }

    public List<String> getGeneralContexts() {
        return generalContexts;
    }

    public int getRadiantRadius() {
        return radiantRadius;
    }

    public int getChatRadius() {
        return chatRadius;
}

    public void setQuestTitle(String title) {
        // Wait for setQuestObj to be called
        tempQuestTitle = title;
    }

    public void setQuestObj(String obj) {
        questObj = obj;
        questTitle = tempQuestTitle;
    }

    private void loadGeneralContexts() {
        File file = new File(getDataFolder(), "general.yml");
        if (!file.exists()) {
            saveResource("general.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        generalContexts = config.getStringList("contexts");
        Bukkit.getLogger().info("Loaded " + generalContexts.size() + " general contexts.");
        if (generalContexts == null) {
            generalContexts = new ArrayList<>();
        }
    }

    private File loadDataFiles() {
        File dataFolder = new File(getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File[] files = dataFolder.listFiles();
        if (files == null) {
            return null;
        }

        // disabled-npcs.yml file
        File disabledNPCsFile = new File(getDataFolder(), "disabled-npcs.yml");
        if (!disabledNPCsFile.exists()) {
            saveResource("disabled-npcs.yml", false);
        }

        // Load disabled NPCs
        FileConfiguration disabledNPCsConfig = YamlConfiguration.loadConfiguration(disabledNPCsFile);

        // set disabled npcs to the list convert string to UUID
        disabledNPCs = disabledNPCsConfig.getStringList("disabled-npcs").stream()
                .map(UUID::fromString).collect(Collectors.toSet());


        // disabled-players.yml file
        File disabledPlayersFile = new File(getDataFolder(), "disabled-players.yml");
        if (!disabledPlayersFile.exists()) {
            saveResource("disabled-players.yml", false);
        }
        // Load disabled players
        FileConfiguration disabledPlayersConfig = YamlConfiguration.loadConfiguration(disabledPlayersFile);

        // set disabled players to the list convert string to player name
        disabledPlayers = new ArrayList<>(disabledPlayersConfig.getStringList("disabled-players"));

        return disabledNPCsFile;
    }

    public List<ConversationMessage> getMessages(FileConfiguration npcData) {
        List<ConversationMessage> npcConversationHistory = new ArrayList<>();
        List<Map<?, ?>> historyList = npcData.getMapList("conversationHistory");
        for (Map<?, ?> map : historyList) {
            String role = (String) map.get("role");
            String content = (String) map.get("content");
            npcConversationHistory.add(new ConversationMessage(role, content));
        }
        return npcConversationHistory;
    }

    public void addSystemMessage(String npcName, String systemMessage) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("system", systemMessage));
        saveNPCConversationHistory(npcName, npcConversationHistory);
    }

    public void addNPCMessage(String npcName, String npcMessage) {
        FileConfiguration npcData = getNPCData(npcName);
        List<ConversationMessage> npcConversationHistory = getMessages(npcData);
        npcConversationHistory.add(new ConversationMessage("assistant", npcMessage));
        saveNPCConversationHistory(npcName, npcConversationHistory);
       // broadcastNPCMessage(npcMessage, npcName, false, null, null, null, "cyan");

    }


    // Fetch NPC data dynamically from YAML
    public FileConfiguration getNPCData(String npcName) {
        return npcDataManager.loadNPCData(npcName);
    }

    // Save NPC conversation history back to YAML
    public void saveNPCConversationHistory(String npcName, List<ConversationMessage> conversationHistory) {
        FileConfiguration config = npcDataManager.loadNPCData(npcName);

        List<Map<String, String>> historyList = new ArrayList<>();
        for (ConversationMessage message : conversationHistory) {
            Map<String, String> map = new HashMap<>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            historyList.add(map);
        }

        config.set("conversationHistory", historyList);
        npcDataManager.saveNPCFile(npcName, config);
    }

    public void saveNPCRelationValue(String npcName, String relationName, String value) {
        FileConfiguration config = npcDataManager.loadNPCData(npcName);
        String currentValue = config.getString("relations." + relationName, "0");

        // Extract the integer value, including negative sign if present
        currentValue = currentValue.replaceAll("[^\\d-]", "");
        int currentValueInt = currentValue.isEmpty() ? 0 : Integer.parseInt(currentValue);

        // Extract the integer value from the input value, including negative sign if present
        value = value.replaceAll("[^\\d-]", "");
        int valueInt = value.isEmpty() ? 0 : Integer.parseInt(value);

        int updatedValue = currentValueInt + valueInt;
        config.set("relations." + relationName, updatedValue);
        npcDataManager.saveNPCFile(npcName, config);
    }




    // Event: PlayerQuitEvent
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        playerCurrentNPC.remove(playerUUID);
        // Remove the player from any active conversations
        if (conversationManager.hasActiveConversation(player)) {
            conversationManager.endConversation(player);
        }
    }

    // Shared HTTP client for better connection pooling
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // Semaphore to limit concurrent API calls (adjust based on your API limits)
    private final Semaphore apiRateLimiter = new Semaphore(5);

    // Request queue tracking
    private final AtomicInteger pendingRequests = new AtomicInteger(0);

    /**
     * Gets an AI response from OpenRouter.ai with improved concurrency handling
     *
     * @param conversation The conversation history to send
     * @return The AI response or null if an error occurred
     */
    public String getAIResponse(List<ConversationMessage> conversation) {
        if (openAIKey.isEmpty()) {
            getLogger().warning("OpenAI API Key is not set!");
            return null;
        }

        // For very high load situations, reject requests if too many pending
        if (pendingRequests.get() > 20) {
            getLogger().warning("Too many pending AI requests, rejecting new request");
            return "Sorry, the AI service is currently overloaded. Please try again later.";
        }

        pendingRequests.incrementAndGet();

        try {
            // Try to acquire a permit with timeout
            if (!apiRateLimiter.tryAcquire(10, TimeUnit.SECONDS)) {
                pendingRequests.decrementAndGet();
                getLogger().warning("API rate limit reached, couldn't acquire permit within timeout");
                return "Sorry, the AI service is currently busy. Please try again later.";
            }

            try {
                // Create JSON request
                JsonObject requestObject = new JsonObject();
                requestObject.addProperty("model", aiModel);
                requestObject.addProperty("max_tokens", 500);

                JsonArray messagesArray = new JsonArray();
                for (ConversationMessage message : conversation) {
                    JsonObject messageObject = new JsonObject();
                    messageObject.addProperty("role", message.getRole());
                    messageObject.addProperty("content", message.getContent());
                    messagesArray.add(messageObject);
                }
                requestObject.add("messages", messagesArray);

                // Build request
                RequestBody body = RequestBody.create(
                        requestObject.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url("https://openrouter.ai/api/v1/chat/completions")
                        .addHeader("Authorization", "Bearer " + openAIKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build();

                // Execute request synchronously but from a CompletableFuture context
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        getLogger().warning("API request failed: " + response.code() + " - " + response.message());
                        return null;
                    }

                    // Parse response
                    String jsonResponse = response.body().string();
                    JsonObject responseObject = gson.fromJson(jsonResponse, JsonObject.class);

                    if (responseObject.has("choices") && responseObject.getAsJsonArray("choices").size() > 0) {
                        JsonObject choice = responseObject.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                            return choice.getAsJsonObject("message").get("content").getAsString();
                        }
                    }

                    getLogger().warning("Unexpected API response format: " + jsonResponse);
                    return null;
                }
            } finally {
                // Always release the permit
                apiRateLimiter.release();
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error getting AI response", e);
            return null;
        } finally {
            pendingRequests.decrementAndGet();
        }
    }

    // Helper method to build conversation context
    private String buildConversationContext(List<ConversationMessage> conversation) {
        StringBuilder contextBuilder = new StringBuilder();
        for (ConversationMessage msg : conversation) {
            contextBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return contextBuilder.toString();
    }

    public List<String> getTraitList() {
        return traitList;
    }

    public List<String> getQuirkList() {
        return quirkList;
    }

    public List<String> getMotivationList() {
        return motivationList;
    }

    public List<String> getFlawList() {
        return flawList;
    }

    public List<String> getToneList() {
        return toneList;
    }

    // Inner class to represent conversation messages
    public static class ConversationMessage {
        private String role;
        private String content;

        public ConversationMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
