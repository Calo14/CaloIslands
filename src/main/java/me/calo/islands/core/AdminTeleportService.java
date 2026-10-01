package me.calo.islands.core;

import me.calo.islands.domain.*;
import me.calo.islands.integration.ExternalProtection;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.sql.SQLException;
import java.util.List;

/** Validates exact persisted arrival points. Normal teleport events and all protection authorities remain active. */
public final class AdminTeleportService {
    public enum DestinationStatus { VALID, INVALID, WORLD_UNAVAILABLE, TERRAIN_UNCHECKED }
    private final RegionService regions;
    private final List<ExternalProtection> authorities;
    private final Messages messages;
    private final boolean denyActiveEntry;
    public AdminTeleportService(RegionService regions, List<ExternalProtection> authorities) {
        this(regions, authorities, new Messages(new java.io.File("messages.yml")));
    }
    public AdminTeleportService(RegionService regions, List<ExternalProtection> authorities, Messages messages) {
        this(regions, authorities, messages, false);
    }
    public AdminTeleportService(RegionService regions, List<ExternalProtection> authorities, Messages messages, boolean denyActiveEntry) {
        this.regions = regions; this.authorities = List.copyOf(authorities); this.messages = messages;
        this.denyActiveEntry = denyActiveEntry;
    }
    public void region(Player player, String id) {
        requirePermission(player);
        Region region = regions.region(id).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-region")));
        if (region.destination() == null) throw new IllegalArgumentException(messages.text("region-no-point"));
        destination(player, region.destination());
    }

    public void city(Player player, String id) throws SQLException {
        requirePermission(player);
        City city = regions.city(id).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-city")));
        Region region = regions.region(city.regionId()).orElseThrow(() -> new IllegalArgumentException(messages.text("city-region-missing")));
        if (!region.world().equals(city.world()) || !region.bounds().contains(city.x(), city.y(), city.z())
                || !region.bounds().contains(city.x(), city.y() + 1, city.z()))
            throw new IllegalArgumentException(messages.text("destination-invalid"));
        destination(player, city.destination());
    }
    private void destination(Player player, Destination point) {
        teleport(player, checkedDestination(player, point));
    }
    /** Read-only access check shared with future activities; it grants no administrative bypass. */
    public boolean canAccess(Player player, Destination point) {
        try { checkedDestination(player, point); return true; }
        catch (RuntimeException | LinkageError denied) { return false; }
    }
    /** Menu inspection never loads chunks; the exact access check still runs before teleporting. */
    public DestinationStatus inspectDestination(Destination point) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException(messages.text("server-thread-required"));
        World world = Bukkit.getWorld(point.world());
        if (world == null) return DestinationStatus.WORLD_UNAVAILABLE;
        Location at = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        if (Math.abs(point.x()) > 29999984 || Math.abs(point.z()) > 29999984
                || point.y() < world.getMinHeight() + 1 || point.y() + 1.8 >= world.getMaxHeight()
                || !world.getWorldBorder().isInside(at)) return DestinationStatus.INVALID;
        for (int x = (int)Math.floor(point.x()-.3); x <= (int)Math.floor(point.x()+.3); x++)
            for (int z = (int)Math.floor(point.z()-.3); z <= (int)Math.floor(point.z()+.3); z++)
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return DestinationStatus.TERRAIN_UNCHECKED;
        return safe(at) ? DestinationStatus.VALID : DestinationStatus.INVALID;
    }
    private Location checkedDestination(Player player, Destination point) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException(messages.text("server-thread-required"));
        Location at = new Location(world(point.world()), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        if (!at.getWorld().getWorldBorder().isInside(at) || !safe(at))
            throw new IllegalArgumentException(messages.text("destination-invalid"));
        Location from = player.getLocation();
        boolean allowed = denyActiveEntry
                ? regions.mayEnter(from.getWorld().getName(), from.getX(), from.getY(), from.getZ(), point.world(), point.x(), point.y(), point.z(), true)
                : regions.mayEnter(from.getWorld().getName(), from.getX(), from.getY(), from.getZ(), point.world(), point.x(), point.y(), point.z());
        if (!allowed)
            throw new IllegalArgumentException(messages.text("entry-denied"));
        try {
            for (ExternalProtection authority : authorities)
                if (authority.deniesEntry(player, at)) throw new IllegalArgumentException(messages.text("entry-denied"));
        } catch (LinkageError failure) { throw new IllegalStateException(messages.text("integration-error")); }
        return at;
    }
    private World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException(messages.text("world-unloaded", "world", name));
        return world;
    }
    public static boolean safe(Location at) {
        World world = at.getWorld();
        if (world == null || !Double.isFinite(at.getX()) || !Double.isFinite(at.getY()) || !Double.isFinite(at.getZ())
                || Math.abs(at.getX()) > 29999984 || Math.abs(at.getZ()) > 29999984
                || at.getY() < world.getMinHeight() + 1 || at.getY() + 1.8 >= world.getMaxHeight()) return false;
        // Check the player's full .6 x 1.8 bounding box, including fractional stored city coordinates.
        for (int x = (int) Math.floor(at.getX() - .3); x <= (int) Math.floor(at.getX() + .3); x++)
            for (int z = (int) Math.floor(at.getZ() - .3); z <= (int) Math.floor(at.getZ() + .3); z++) {
                Block floor = world.getBlockAt(x, at.getBlockY() - 1, z);
                // Full-width support at foot height; a fence/wall protruding into the player
                // or a lower slab cannot masquerade as a safe floor.
                var support = floor.getBoundingBox();
                if (support == null || support.getMinX() > x || support.getMaxX() < x + 1
                        || support.getMinZ() > z || support.getMaxZ() < z + 1
                        || Math.abs(support.getMaxY() - at.getBlockY()) > .000001
                        || support.getHeight() <= 0 || floor.isLiquid() || hazard(floor.getType())) return false;
                for (int y = at.getBlockY(); y <= (int) Math.floor(at.getY() + 1.8); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.isPassable() || block.isLiquid() || hazard(block.getType())) return false;
                }
            }
        return true;
    }
    private static boolean hazard(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW,
                 SWEET_BERRY_BUSH, WITHER_ROSE, POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL -> true;
            default -> false;
        };
    }
    private void requirePermission(Player player) {
        if (!player.hasPermission("caloislands.admin")) throw new IllegalArgumentException(messages.text("no-permission"));
    }
    private void teleport(Player player, Location at) {
        player.closeInventory();
        if (!player.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN))
            throw new IllegalArgumentException(messages.text("teleport-cancelled"));
        messages.send(player, "teleport-success");
    }
}
