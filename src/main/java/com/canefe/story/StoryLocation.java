package com.canefe.story;

import org.bukkit.Location;

import java.util.List;

public class StoryLocation {
    private final String name;
    private final List<String> context;
    private Location bukkitLocation; // Optional: Store the Bukkit location if needed
    private String parentLocationName; // Store the parent location name

    public StoryLocation(String name, List<String> context) {
        this.name = name;
        this.context = context;
        this.parentLocationName = null; // Default to no parent
    }

    public StoryLocation(String name, List<String> context, String parentLocationName) {
        this.name = name;
        this.context = context;
        this.parentLocationName = parentLocationName;
    }

    public StoryLocation(String name, List<String> context, Location bukkitLocation) {
        this.name = name;
        this.context = context;
        this.bukkitLocation = bukkitLocation;
        this.parentLocationName = null; // Default to no parent
    }

    public StoryLocation(String name, List<String> context, Location bukkitLocation, String parentLocationName) {
        this.name = name;
        this.context = context;
        this.bukkitLocation = bukkitLocation;
        this.parentLocationName = parentLocationName;
    }

    public String getName() {
        return name;
    }


    public List<String> getContext() {
        return context;
    }

    public Location getBukkitLocation() {
        return bukkitLocation;
    }

    public void setBukkitLocation(Location bukkitLocation) {
        this.bukkitLocation = bukkitLocation;
    }

    public String getParentLocationName() {
        return parentLocationName;
    }

    public void setParentLocationName(String parentLocationName) {
        this.parentLocationName = parentLocationName;
    }

    public boolean hasParent() {
        return parentLocationName != null && !parentLocationName.isEmpty();
    }

    @Override
    public String toString() {
        return "Location{name='" + name + "', bukkitLocation=" + bukkitLocation +
                ", context=" + context + ", parent='" + parentLocationName + "'}";
    }
}