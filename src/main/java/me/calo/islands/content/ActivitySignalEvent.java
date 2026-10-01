package me.calo.islands.content;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous notification after the checkpoint commits in CaloIslands. */
public final class ActivitySignalEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ActivitySignal signal;
    public ActivitySignalEvent(ActivitySignal signal) { this.signal = java.util.Objects.requireNonNull(signal); }
    public ActivitySignal signal() { return signal; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
