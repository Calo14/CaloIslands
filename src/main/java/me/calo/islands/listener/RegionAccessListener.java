package me.calo.islands.listener;

import me.calo.islands.domain.RegionService;
import me.calo.islands.core.Messages;
import me.calo.islands.core.ProtectionSettings;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Local entry gate; block actions use RegionProtectionListener, claims remain with Lands. */
public final class RegionAccessListener implements Listener {
    private final RegionService regions;
    private final Messages messages;
    private final ProtectionSettings protection;

    public RegionAccessListener(RegionService regions, Messages messages, ProtectionSettings protection) {
        this.regions = regions;
        this.messages = messages;
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        checkEntry(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        checkEntry(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        checkEntry(event);
    }

    private void checkEntry(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) return;
        if (regions.mayEnter(from.getWorld().getName(), from.getX(), from.getY(), from.getZ(),
                to.getWorld().getName(), to.getX(), to.getY(), to.getZ(),
                protection.denyActiveEntry())) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(messages.text("entry-denied"));
    }

}
