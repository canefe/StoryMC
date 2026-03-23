package me.casperge.realisticseasons.api;

import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.season.Season;
import org.bukkit.World;

public class SeasonsAPI {
    private static SeasonsAPI api;

    public static SeasonsAPI getInstance() {
        return api;
    }

    public Date getDate(World world) {
        return null;
    }

    public int getHours(World world) {
        return 0;
    }

    public int getMinutes(World world) {
        return 0;
    }

    public Season getSeason(World world) {
        return null;
    }
}
