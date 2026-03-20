package me.casperge.realisticseasons.calendar;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class DayChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final World w;
    private final Date from;
    private final Date to;

    public DayChangeEvent(World w, Date from, Date to) {
        this.w = w;
        this.from = from;
        this.to = to;
    }

    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    public World getWorld() { return w; }
    public Date getFrom() { return from; }
    public Date getTo() { return to; }
}
