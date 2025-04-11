package com.canefe.story;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCScheduleManager {
    private static NPCScheduleManager instance;
    private final Story plugin;
    private final Map<String, NPCSchedule> schedules = new ConcurrentHashMap<>();
    private final File scheduleFolder;
    private BukkitTask scheduleTask;

    // Get singleton instance
    public static synchronized NPCScheduleManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new NPCScheduleManager(plugin);
        }
        return instance;
    }

    private NPCScheduleManager(Story plugin) {
        this.plugin = plugin;
        this.scheduleFolder = new File(plugin.getDataFolder(), "schedules");
        if (!scheduleFolder.exists()) {
            scheduleFolder.mkdirs();
        }
        loadAllSchedules();
        startScheduleRunner();
    }

    public void reloadSchedules() {
        // Stop the current task
        if (scheduleTask != null) {
            scheduleTask.cancel();
        }
        // Reload all schedules
        loadAllSchedules();
        // Restart the schedule runner
        startScheduleRunner();
    }

    public void loadAllSchedules() {
        schedules.clear();
        File[] files = scheduleFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                String npcName = file.getName().replace(".yml", "");
                NPCSchedule schedule = loadSchedule(npcName);
                if (schedule != null) {
                    schedules.put(npcName.toLowerCase(), schedule);
                    plugin.getLogger().info("Loaded schedule for NPC: " + npcName);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading schedule from file: " + file.getName());
                e.printStackTrace();
            }
        }
        plugin.getLogger().info("Loaded " + schedules.size() + " NPC schedules");
    }

    public NPCSchedule loadSchedule(String npcName) {
        File scheduleFile = new File(scheduleFolder, npcName + ".yml");
        if (!scheduleFile.exists()) {
            return null;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(scheduleFile);
        NPCSchedule schedule = new NPCSchedule(npcName);

        // Load all time entries
        for (String timeKey : config.getConfigurationSection("schedule").getKeys(false)) {
            try {
                int time = Integer.parseInt(timeKey);
                String locationName = config.getString("schedule." + timeKey + ".location");
                String action = config.getString("schedule." + timeKey + ".action", "idle");
                String dialogue = config.getString("schedule." + timeKey + ".dialogue");

                ScheduleEntry entry = new ScheduleEntry(time, locationName, action, dialogue);
                schedule.addEntry(entry);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid time format in schedule for " + npcName + ": " + timeKey);
            }
        }

        return schedule;
    }

    public void saveSchedule(NPCSchedule schedule) {
        File scheduleFile = new File(scheduleFolder, schedule.getNpcName() + ".yml");
        FileConfiguration config = new YamlConfiguration();

        // Save all time entries
        for (ScheduleEntry entry : schedule.getEntries()) {
            String timePath = "schedule." + entry.getTime();
            config.set(timePath + ".location", entry.getLocationName());
            config.set(timePath + ".action", entry.getAction());
            if (entry.getDialogue() != null) {
                config.set(timePath + ".dialogue", entry.getDialogue());
            }
        }

        try {
            config.save(scheduleFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save schedule for " + schedule.getNpcName());
            e.printStackTrace();
        }
    }

    public NPCSchedule getSchedule(String npcName) {
        return schedules.get(npcName.toLowerCase());
    }

    private void startScheduleRunner() {
        // Stop any existing task
        if (scheduleTask != null) {
            scheduleTask.cancel();
        }

        // Run every minute to check for schedule updates
        scheduleTask = new BukkitRunnable() {
            @Override
            public void run() {
                // if no players are online, skip the schedule check
                if (plugin.getServer().getOnlinePlayers().isEmpty()) {
                    return;
                }
                long gameTime = plugin.getServer().getWorlds().get(0).getTime();
                // Convert to 24-hour format (0-23)
                int hour = (int) ((gameTime / 1000 + 6) % 24); // +6 because MC day starts at 6am

                for (NPCSchedule schedule : schedules.values()) {
                    ScheduleEntry currentEntry = schedule.getEntryForTime(hour);
                    if (currentEntry != null) {
                        executeScheduleEntry(schedule.getNpcName(), currentEntry);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 1200L); // Check every minute
    }

    private void executeScheduleEntry(String npcName, ScheduleEntry entry) {
        // Get NPC entity through your NPC system
        NPC npc = plugin.getNPCDataManager().getNPC(npcName);
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            //plugin.getLogger().warning("Could not find NPC entity for " + npcName);
            return;
        }

        // Handle location movement
        if (entry.getLocationName() != null) {
            StoryLocation location = plugin.getLocationManager().getLocation(entry.getLocationName());
            if (location != null) {
                // Use your existing NPC movement system or teleport
                // This is a placeholder - implement actual movement
                moveNPCToLocation(npc, location);
            }
        }

        // Handle action
        if (entry.getAction() != null) {
            executeAction(npcEntity, entry.getAction());
        }

        // Handle dialogue (announcement)
        if (entry.getDialogue() != null) {
            plugin.broadcastNPCMessage(entry.getDialogue(), npcName, false, npc, null, null, null, "#599B45");
        }
    }

    private void moveNPCToLocation(NPC npc, StoryLocation location) {
        // Implement movement - this will depend on your NPC system
        // This is a placeholder implementation
        Location bukkit_location = location.getBukkitLocation();
        if (bukkit_location == null) {
            plugin.getLogger().warning("No Bukkit location found for " + location.getName());
            return;
        }
        plugin.getLogger().info("Moving NPC to " + bukkit_location);
        plugin.npcManager.walkToLocation(npc, bukkit_location, 1, 1f, 30, null, null);
    }

    private void executeAction(Entity npc, String action) {
        // Implement actions like sitting, working, etc.
        // This depends on your NPC animation system
        switch (action.toLowerCase()) {
            case "sit":
                // Make NPC sit
                break;
            case "work":
                // Make NPC perform work animation
                break;
            case "sleep":
                // Make NPC sleep
                break;
            case "idle":
            default:
                // Default idle behavior
                break;
        }
    }

    public void shutdown() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
        }
    }

    public static class NPCSchedule {
        private final String npcName;
        private final List<ScheduleEntry> entries = new ArrayList<>();

        public NPCSchedule(String npcName) {
            this.npcName = npcName;
        }

        public String getNpcName() {
            return npcName;
        }

        public void addEntry(ScheduleEntry entry) {
            entries.add(entry);
            // Sort entries by time
            entries.sort(Comparator.comparingInt(ScheduleEntry::getTime));
        }

        public List<ScheduleEntry> getEntries() {
            return entries;
        }

        public ScheduleEntry getEntryForTime(int currentHour) {
            // Find the entry with the closest time <= current hour
            ScheduleEntry bestEntry = null;
            int bestTimeDiff = 24; // Maximum possible difference

            for (ScheduleEntry entry : entries) {
                int entryTime = entry.getTime();

                // Check if this entry is applicable for current time
                if (entryTime <= currentHour) {
                    int diff = currentHour - entryTime;
                    if (diff < bestTimeDiff) {
                        bestTimeDiff = diff;
                        bestEntry = entry;
                    }
                }
            }

            // If no entry found before current hour, use the last one (evening)
            if (bestEntry == null && !entries.isEmpty()) {
                return entries.get(entries.size() - 1);
            }

            return bestEntry;
        }
    }

    public static class ScheduleEntry {
        private final int time; // Hour (0-23)
        private final String locationName;
        private final String action;
        private final String dialogue;

        public ScheduleEntry(int time, String locationName, String action, String dialogue) {
            this.time = time;
            this.locationName = locationName;
            this.action = action;
            this.dialogue = dialogue;
        }

        public int getTime() {
            return time;
        }

        public String getLocationName() {
            return locationName;
        }

        public String getAction() {
            return action;
        }

        public String getDialogue() {
            return dialogue;
        }
    }
}