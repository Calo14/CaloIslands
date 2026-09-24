package me.calo.islands.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import me.calo.islands.core.ProtectionSettings.Action;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Consults staff flags without modifying WorldGuard's regions or permissions. */
public final class WorldGuardProtection implements ExternalProtection {
    public WorldGuardProtection() {
        if (WorldGuard.getInstance().getPlatform().getRegionContainer() == null)
            throw new IllegalStateException("WorldGuard region container unavailable");
        if (Flags.BUILD == null || Flags.CHEST_ACCESS == null)
            throw new IllegalStateException("WorldGuard flags unavailable");
    }

    @Override
    public boolean denies(Player player, Action action, Location location) {
        if (player == null) return false; // Native WorldGuard handles non-player events.
        var local = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(
                local, BukkitAdapter.adapt(location.getWorld()))) return false;
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        var at = BukkitAdapter.adapt(location);
        return switch (action) {
            case BLOCK_BREAK, BLOCK_PLACE -> !query.testBuild(at, local, Flags.BUILD);
            case CONTAINER -> !query.testState(at, local, Flags.CHEST_ACCESS);
            case BLOCK_INTERACT, BUCKET -> !query.testState(at, local, Flags.USE);
            case ENTITY_INTERACT -> !query.testState(at, local, Flags.INTERACT);
            case PVP_DAMAGE -> !query.testState(at, local, Flags.PVP);
            default -> false; // Native WorldGuard listeners own the other flags.
        };
    }
}
