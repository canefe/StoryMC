package com.canefe.story;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class NPCDataManager {

    private final JavaPlugin plugin;
    private final File npcDirectory;

    public NPCDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.npcDirectory = new File(plugin.getDataFolder(), "npcs");

        if (!npcDirectory.exists()) {
            npcDirectory.mkdirs(); // Create the directory if it doesn't exist
        }
    }


    public File getNPCDirectory() {
        return npcDirectory;
    }


    public FileConfiguration loadNPCData(String npcName) {
        File npcFile = new File(getNPCDirectory(), npcName + ".yml");
        if (!npcFile.exists()) {
            return new YamlConfiguration(); // Return an empty configuration if no file exists
        }
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

    public void deleteNPCFile(String npcName) {
        File npcFile = new File(npcDirectory, npcName + ".yml");
        if (npcFile.exists()) {
            npcFile.delete();
        }
    }
}

