package com.canefe.story;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class NPCDataManager {

    private static NPCDataManager instance;

    private final JavaPlugin plugin;
    private final Map<String, NPCData> npcDataMap;
    private final File npcDirectory;

    private NPCDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.npcDirectory = new File(plugin.getDataFolder(), "npcs");
        this.npcDataMap = new HashMap<>();

        if (!npcDirectory.exists()) {
            npcDirectory.mkdirs(); // Create the directory if it doesn't exist
        }
    }

    public static NPCDataManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new NPCDataManager(plugin);
        }
        return instance;
    }


    public File getNPCDirectory() {
        return npcDirectory;
    }


    /**
     * Gets a list of all NPC names by scanning the NPC directory for YAML files.
     *
     * @return A list of all NPC names without the .yml extension
     */
    public java.util.List<String> getAllNPCNames() {
        java.util.List<String> npcNames = new java.util.ArrayList<>();

        File[] files = npcDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                // Remove the .yml extension to get the NPC name
                String npcName = fileName.substring(0, fileName.length() - 4);
                npcNames.add(npcName);
            }
        }

        return npcNames;
    }

    public FileConfiguration loadNPCData(String npcName) {
        File npcFile = new File(getNPCDirectory(), npcName + ".yml");
        if (!npcFile.exists()) {
            return new YamlConfiguration(); // Return an empty configuration if no file exists
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(npcFile);

        return YamlConfiguration.loadConfiguration(npcFile);
    }


    public void saveNPCFile(String npcName, FileConfiguration config) {
        File npcFile = new File(npcDirectory, npcName + ".yml");
        try {
            config.save(npcFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public NPC getNPC(String npcName) {
        File npcFile = new File(npcDirectory, npcName + ".yml");
        if (!npcFile.exists()) {
            return null; // Return null if no file exists
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(npcFile);
        // try to find npc by name citizens registry
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(npcName)) {
                return npc;
            }
        }
        return null; // Return null if no NPC with the given name is found
    }

    public void deleteNPCFile(String npcName) {
        File npcFile = new File(npcDirectory, npcName + ".yml");
        if (npcFile.exists()) {
            npcFile.delete();
        }
    }
}

