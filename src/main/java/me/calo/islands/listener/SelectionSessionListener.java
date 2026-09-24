package me.calo.islands.listener;

import me.calo.islands.domain.RegionSelectionService;
import me.calo.islands.core.SelectionSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Clears temporary mode and fallback preview state in either selector mode. */
public final class SelectionSessionListener implements Listener {
    private final RegionSelectionService selections;
    private final SelectionSource external;

    public SelectionSessionListener(RegionSelectionService selections) { this(selections, null); }

    public SelectionSessionListener(RegionSelectionService selections, SelectionSource external) {
        this.selections = selections;
        this.external = external;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (external != null) {
            try { external.clear(event.getPlayer()); }
            catch (RuntimeException | LinkageError ignored) { /* WorldEdit may have ended its session already. */ }
        }
        selections.clear(event.getPlayer().getUniqueId());
    }
}
