package com.canefe.story;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIBukkitConfig;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.PlayerArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import kr.toxicity.healthbar.api.event.HealthBarCreateEvent;
import me.libraryaddict.disguise.DisguiseAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    private List<Player> disabledPlayers = new ArrayList<>();

    private List<UUID> disabledNPCs = new ArrayList<>();

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


    // Gson instance for JSON parsing
    private Gson gson = new Gson();

    public NPCDataManager npcDataManager;

    public ConversationManager conversationManager;

    public LocationManager locationManager;

    public NPCUtils npcUtils;

    public NPCManager npcManager;

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
        getLogger().info("AIStorymaker has been enableds!");

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Check if Sentinel and Citizens plugins are present
        if (Bukkit.getPluginManager().getPlugin("Sentinel") == null) {
            getLogger().warning("Sentinel plugin not found! NPC commands will not work.");
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().warning("Citizens plugin not found! NPC interactions will not work.");
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
                        if (disabledPlayers.contains(target)) {
                            disabledPlayers.remove(target);
                            player.sendMessage(ChatColor.GRAY + "Enabled chat for " + target.getName());
                        } else {
                            disabledPlayers.add(target);
                            player.sendMessage(ChatColor.GRAY + "Disabled chat for " + target.getName());
                        }
                    } else {
                        if (disabledPlayers.contains(player)) {
                            disabledPlayers.remove(player);
                            player.sendMessage(ChatColor.GRAY + "Enabled chat for yourself.");
                        } else {
                            disabledPlayers.add(player);
                            player.sendMessage(ChatColor.GRAY + "Disabled chat for yourself.");
                        }
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
        // command /conv <message> <greedy_npc_name>




        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) { //
            new StoryPlaceholderExpansion(this).register(); //
        }

        conversationManager = ConversationManager.getInstance(this);


        npcDataManager = NPCDataManager.getInstance(this);

        locationManager = LocationManager.getInstance(this);

        npcUtils = NPCUtils.getInstance(this);

        npcManager = NPCManager.getInstance(this);

        startProximityTask(this);

        loadGeneralContexts();

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

    public void disableNPCTalking(String npcName) {
        disabledNPCs.add(getNPCUUID(npcName));
    }

    public void enableNPCTalking(String npcName) {
        disabledNPCs.remove(getNPCUUID(npcName));
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

            loadGeneralContexts();


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
        }, 20L * 5, 20L * 5); // Run every second (20 ticks)
    }


    private void handleProximityCheck(Player player) {

        //Disable if player is disabled
        if (disabledPlayers.contains(player)) {
            return;
        }
        // Get the player's current chunk
        Chunk playerChunk = player.getLocation().getChunk();

        // Check only NPCs in the same chunk or adjacent chunks
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getEntity() == null) continue;

            Chunk npcChunk = npc.getEntity().getLocation().getChunk();
            if (!isNearbyChunk(playerChunk, npcChunk)) continue;

            // check if disabled
            if (disabledNPCs.contains(npc.getUniqueId())) {
                continue;
            }

            // Perform proximity check within a 10-block range
            Location npcLocation = npc.getEntity().getLocation();
            if (npcLocation.distance(player.getLocation()) <= radiantRadius) {
                if (isOnCooldown(npc)) continue;
                triggerRadiantConversation(npc, player);
                updateCooldown(npc);
            }
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
        // Find nearby NPCs
        List<NPC> nearbyNPCs = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getEntity() == null) {
                continue;
            }
            if (disabledNPCs.contains(npc.getUniqueId())) {
                continue;
            }
            Location npcLocation = npc.getEntity().getLocation();
            if (npcLocation.getWorld() == player.getWorld() && npcLocation.distance(player.getLocation()) <= radiantRadius) {
                nearbyNPCs.add(npc);
            }
        }

        if (nearbyNPCs.isEmpty()) return;

        // Select a random NPC to talk to
        NPC target = nearbyNPCs.get(new Random().nextInt(nearbyNPCs.size()));


        // only if they are different NPCs
        if (target.getName().equals(initiator.getName())) {
            return;
        }
        // check if the yare already in a conversation
        if (conversationManager.isNPCInConversation(initiator.getName()) || conversationManager.isNPCInConversation(target.getName())) {
            return;
        }


        // Start conversation
        startRadiantConversation(initiator, target, player);
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

    // Event: PlayerInteractEntityEvent
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // Ignore off-hand interactions
        }
        Player player = event.getPlayer();
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());

        if (disabledPlayers.contains(player)) {
            playerCurrentNPC.put(player.getUniqueId(), npc.getUniqueId());
            return;
        }



        if (npc == null) {
            return;
        }

        String npcName = npc.getName();
        UUID playerUUID = player.getUniqueId();

        if (conversationManager.hasActiveConversation(player)) {

            if (conversationManager.isNPCInConversation(player, npcName)) {
                // End Conversation
                conversationManager.endConversation(player);
                return;
            }

            if (disabledNPCs.contains(npc.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + npcName + " " + ChatColor.RED + "is busy.");
                return;
            }

            conversationManager.addNPCToConversation(player, npc);

        } else {

            if (disabledNPCs.contains(npc.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + npcName + " " + ChatColor.RED + "is busy.");
                return;
            }

            if (conversationManager.isNPCInConversation(npcName)) {
                // Join the existing conversation
                conversationManager.addPlayerToConversation(player, npcName);
                return;
            }

            List<NPC> npcs = new ArrayList<>();
            npcs.add(npc);
            conversationManager.startGroupConversation(player, npcs);
            // Set current NPC for the player
            playerCurrentNPC.put(playerUUID, npc.getUniqueId());
            player.sendMessage(ChatColor.GRAY + "You are now talking to " + npcName + ".");
        }

        // If NPC has SentinelTrait, open a custom GUI for commands
        SentinelTrait sentinel = npc.getTrait(SentinelTrait.class);
        if (sentinel != null) {
            // Open GUI for NPC commands (e.g., Follow Me)
            // For simplicity, we'll skip GUI implementation in this example
            // You can integrate Inventory GUI handling here if needed
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


        // check if player is enabled DisabledPlayers
        if (disabledPlayers.contains(player)) {
            return;
        }




        // Check if player is in an active conversation
        List<NPC> nearbyNPCs = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getEntity() == null) {
                continue;
            }
            Location npcLocation = npc.getEntity().getLocation();
            if (npcLocation.getWorld() == player.getWorld() && npcLocation.distance(player.getLocation()) <= chatRadius) {
                nearbyNPCs.add(npc);
            }
        }


        if (conversationManager.hasActiveConversation(player)) {

            List<NPC> currentNPCs = conversationManager.getNPCsInConversation(player);

// Remove NPCs from the conversation that are no longer nearby
            for (NPC npc : currentNPCs) {
                boolean isNearby = nearbyNPCs.stream().anyMatch(n -> n.getName().equals(npc.getName()));
                if (!isNearby) {
                    // NPC is no longer nearby, remove it from the conversation
                    conversationManager.removeNPCFromConversation(player, npc, !nearbyNPCs.isEmpty());
                }
            }

// Add nearby NPCs to the conversation if not already part of it
            for (NPC nearbyNPC : nearbyNPCs) {

                if (!conversationManager.isNPCInConversation(player, nearbyNPC.getName()) && (!disabledNPCs.contains(nearbyNPC.getUniqueId()))) {
                    // NPC is nearby but not yet part of the conversation, add it
                    conversationManager.addNPCToConversation(player, nearbyNPC);
                }
            }






            conversationManager.addPlayerMessage(player, message, chatEnabled);
        } else {


            if (nearbyNPCs.isEmpty()) {
                 player.sendMessage(ChatColor.RED + "You are not currently in an active conversation.");
                 return;
            }

            // Start a new conversation with the first NPC found
            NPC npc = nearbyNPCs.getFirst();
            List<NPC> npcs = new ArrayList<>();
            npcs.add(npc);
            conversationManager.startGroupConversation(player, npcs);

            conversationManager.addPlayerMessage(player, message, chatEnabled);




        }
    }

    public void broadcastNPCMessage(String aiResponse, String defaultNpcName, boolean shouldFollow, NPC finalNpc, UUID playerUUID, Player player, String color) {
        MiniMessage mm = MiniMessage.miniMessage();

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
            Pattern italicPattern = Pattern.compile("\\*(.*?)\\*");
            Matcher italicMatcher = italicPattern.matcher(cleanedMessage);
            String formattedMessage = italicMatcher.replaceAll("<gray><italic>$1</italic></gray>");

            // Format the message with the extracted NPC name
            Component parsedMessage = mm.deserialize(chatFormat
                    .replace("%npc_name%", currentNpcName)
                    .replace("%message%", formattedMessage)
                            .replace("%color%", color));

            parsedMessages.add(parsedMessage);
        }

        // Dispatch all parsed messages to players
        String finalCurrentNpcName = currentNpcName;
        Bukkit.getScheduler().runTask(this, () -> {
            for (Component message : parsedMessages) {
                Bukkit.getServer().sendMessage(message);
            }

            // Handle follow logic for the last NPC message
            if (shouldFollow && finalNpc != null && finalNpc.hasTrait(SentinelTrait.class) && playerUUID != null && player != null) {
                SentinelTrait sentinel = finalNpc.getTrait(SentinelTrait.class);
                sentinel.setGuarding(playerUUID);
                player.sendMessage(ChatColor.GRAY + finalCurrentNpcName + " is now following you.");
            }
        });
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
        broadcastNPCMessage(npcMessage, npcName, false, null, null, null, "cyan");

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
    }

    // Method to interact with OpenAI API and get AI response
    public String getAIResponse(List<ConversationMessage> conversation) {
        if (openAIKey.isEmpty()) {
            getLogger().warning("OpenAI API Key is not set!");
            return null;
        }

        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + openAIKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Build JSON payload
            JsonObject json = new JsonObject();
            json.addProperty("model", aiModel);
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("role", "user");
            messageObj.addProperty("content", buildConversationContext(conversation));
            json.add("messages", gson.toJsonTree(conversation));
            //    "provider": {
            //      "order": [
            //        "OpenAI",
            //        "Together"
            //      ]
            //    }
            JsonObject provider = new JsonObject();
            JsonArray order = new JsonArray();
            order.add("DeepInfra");
            provider.add("order", order);
            json.add("provider", provider);


            String payload = gson.toJson(json);

            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = conn.getResponseCode();
//if (responseCode != 200) {
                //getLogger().warning("OpenAI API responded with code: " + responseCode);
               // return null;
            //}

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
            StringBuilder responseBuilder = new StringBuilder();
            String responseLine;

            while ((responseLine = br.readLine()) != null) {
                responseBuilder.append(responseLine.trim());
            }

            String response = responseBuilder.toString();

            // Parse JSON response
            JsonObject responseJson = gson.fromJson(response, JsonObject.class);
            getLogger().info("OpenAI response: " + responseJson);

            try {
                if (responseJson.has("error")) {
                    JsonObject errorObject = responseJson.getAsJsonObject("error");
                    String errorMessage = errorObject.get("message").getAsString();
                    int errorCode = errorObject.get("code").getAsInt();
                    getLogger().warning("OpenAI response error: " + errorMessage + " (Code: " + errorCode + ")");
                    return null;
                }

                if (responseJson.has("choices")) {
                    JsonObject choiceObject = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (choiceObject.has("message")) {
                        JsonObject messageObject = choiceObject.getAsJsonObject("message");
                        if (messageObject.has("content")) {
                            String aiMessage = messageObject.get("content").getAsString().trim();
                            return aiMessage;
                        }
                    }
                }

                getLogger().warning("Unexpected OpenAI response format.");
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
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
