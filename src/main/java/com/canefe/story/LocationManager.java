package com.canefe.story;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocationManager {

    private static LocationManager instance; // Singleton instance
    private final Story plugin;
    private final Map<String, StoryLocation> locations;
    private final File locationDirectory;

    private LocationManager(Story plugin) {
        this.plugin = plugin;
        this.locationDirectory = new File(plugin.getDataFolder(), "locations");
        this.locations = new HashMap<>();

        if (!locationDirectory.exists()) {
            locationDirectory.mkdirs(); // Create the directory if it doesn't exist
        }
    }

    // Get the singleton instance
    public static synchronized LocationManager getInstance(Story plugin) {
        if (instance == null) {
            instance = new LocationManager(plugin);
        }
        return instance;
    }

    public File getLocationDirectory() {
        return locationDirectory;
    }

    public void saveLocationFile(String locationName, FileConfiguration config) {
        File locationFile = new File(locationDirectory, locationName + ".yml");
        try {
            config.save(locationFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public StoryLocation loadLocationData(String locationName) {
        File locationFile = new File(getLocationDirectory(), locationName + ".yml");
        if (!locationFile.exists()) {
            return null; // Return null if no file exists
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(locationFile);
        StoryLocation storyLocation = new StoryLocation(locationName, config.getStringList("npcNames"), config.getStringList("context"));

        locations.put(locationName, storyLocation);

        return storyLocation;
    }

    // Add a location
    public void addLocation(StoryLocation storyLocation) {
        locations.put(storyLocation.getName(), storyLocation);
    }

    // Get a location by name
    public StoryLocation getLocation(String name) {
        // Check if it's already loaded in memory first
        StoryLocation location = locations.get(name);
        if (location == null) {
            // If not loaded, load it with the method that handles Bukkit locations
            location = loadLocation(name);
            if (location != null) {
                // Store in cache for future use
                locations.put(name, location);
            }
        }
        return location;
    }

    // Get all locations
    public List<StoryLocation> getAllLocations() {
        return List.copyOf(locations.values());
    }

    // Get location-specific global contexts
    // Replace your existing getLocationGlobalContexts method
    public List<String> getLocationGlobalContexts(String locationName) {
        return getAllContextForLocation(locationName);
    }

    // Check if an NPC is part of a location
    public boolean isNPCInLocation(String npcName, String locationName) {
        StoryLocation storyLocation = locations.get(locationName);
        return storyLocation != null && storyLocation.getNpcNames().contains(npcName);
    }

// In your LocationManager class:

    public void loadAllLocations() {
        locations.clear();
        loadLocationsRecursively(locationDirectory, null);
        plugin.getLogger().info("Loaded " + locations.size() + " locations");
    }

    private void loadLocationsRecursively(File directory, String parentPath) {
        if (!directory.exists() || !directory.isDirectory()) return;

        File[] files = directory.listFiles();
        if (files == null) return;

        // First pass: load all YAML files in this directory
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                String locationName = file.getName().replace(".yml", "");
                String fullPath = parentPath == null ? locationName : parentPath + "/" + locationName;

                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                List<String> npcNames = config.getStringList("npcs");
                List<String> context = config.getStringList("context");

                // Use explicit parent from file if available, otherwise use directory structure
                String explicitParent = config.getString("parent", null);
                String effectiveParent = explicitParent != null ? explicitParent : parentPath;

                StoryLocation location = new StoryLocation(fullPath, npcNames, context, effectiveParent);

                // Load Bukkit location if exists
                if (config.contains("world")) {
                    String worldName = config.getString("world");
                    double x = config.getDouble("x");
                    double y = config.getDouble("y");
                    double z = config.getDouble("z");
                    float yaw = (float) config.getDouble("yaw");
                    float pitch = (float) config.getDouble("pitch");

                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world != null) {
                        Location bukkitLocation = new Location(world, x, y, z, yaw, pitch);
                        location.setBukkitLocation(bukkitLocation);
                    }
                }

                locations.put(fullPath, location);
                plugin.getLogger().info("Loaded location: " + fullPath +
                        (effectiveParent != null ? " with parent: " + effectiveParent : ""));
            }
        }

        // Second pass: process subdirectories
        for (File file : files) {
            if (file.isDirectory()) {
                String dirName = file.getName();
                String newParentPath = parentPath == null ? dirName : parentPath + "/" + dirName;
                loadLocationsRecursively(file, newParentPath);
            }
        }
    }

    // Updated to work with the recursive loading system
    public StoryLocation loadLocation(String name) {
        // Check if already loaded
        StoryLocation location = locations.get(name);
        if (location != null) {
            return location;
        }

        // If not loaded, try to find file - first as direct path
        File locationFile = new File(locationDirectory, name + ".yml");

        // If file doesn't exist, try with folder structure
        if (!locationFile.exists() && name.contains("/")) {
            locationFile = new File(locationDirectory, name.replace("/", File.separator) + ".yml");
        }

        if (!locationFile.exists()) {
            return null;
        }

        // Load it and add to cache
        FileConfiguration config = YamlConfiguration.loadConfiguration(locationFile);
        List<String> npcNames = config.getStringList("npcs");
        List<String> context = config.getStringList("context");
        String parentName = config.getString("parent", null);

        // If no explicit parent and this is a path with slashes, infer parent from path
        if (parentName == null && name.contains("/")) {
            parentName = name.substring(0, name.lastIndexOf("/"));
        }

        location = new StoryLocation(name, npcNames, context, parentName);

        // Load Bukkit location
        if (config.contains("world")) {
            String worldName = config.getString("world");
            double x = config.getDouble("x");
            double y = config.getDouble("y");
            double z = config.getDouble("z");
            float yaw = (float) config.getDouble("yaw");
            float pitch = (float) config.getDouble("pitch");

            org.bukkit.World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                Location bukkitLocation = new Location(world, x, y, z, yaw, pitch);
                location.setBukkitLocation(bukkitLocation);
            }
        }

        locations.put(name, location);
        return location;
    }

    public void saveLocation(StoryLocation location) {
        File locationFile = new File(locationDirectory, location.getName() + ".yml");
        FileConfiguration config = new YamlConfiguration();

        // Save basic properties
        config.set("name", location.getName());
        config.set("npcs", location.getNpcNames());
        config.set("context", location.getContext());

        // Save parent location if it exists
        if (location.hasParent()) {
            config.set("parent", location.getParentLocationName());
        }

        // Save Bukkit location if it exists
        Location bukkitLoc = location.getBukkitLocation();
        if (bukkitLoc != null) {
            config.set("world", bukkitLoc.getWorld().getName());
            config.set("x", bukkitLoc.getX());
            config.set("y", bukkitLoc.getY());
            config.set("z", bukkitLoc.getZ());
            config.set("yaw", bukkitLoc.getYaw());
            config.set("pitch", bukkitLoc.getPitch());
        }

        try {
            config.save(locationFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save location: " + location.getName());
            e.printStackTrace();
        }
    }

    // New method to get ALL context for a location (including parent contexts)
    public List<String> getAllContextForLocation(String locationName) {
        List<String> allContext = new ArrayList<>();
        StoryLocation location = getLocation(locationName);

        if (location == null) {
            return allContext;
        }

        // Add this location's context
        allContext.addAll(location.getContext());

        // Recursively add parent contexts
        String parentName = location.getParentLocationName();
        while (parentName != null && !parentName.isEmpty()) {
            StoryLocation parentLocation = getLocation(parentName);
            if (parentLocation == null) {
                break; // Parent not found
            }

            allContext.addAll(parentLocation.getContext());
            parentName = parentLocation.getParentLocationName();
        }

        return allContext;
    }
}
