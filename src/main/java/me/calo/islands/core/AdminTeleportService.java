package me.calo.islands.core;

import me.calo.islands.domain.*;
import me.calo.islands.integration.ExternalProtection;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.sql.SQLException;
import java.util.List;

/** Bounded safe-location search. Normal teleport events and all protection authorities remain active. */
public final class AdminTeleportService {
    private final RegionService regions;
    private final List<ExternalProtection> authorities;
    public AdminTeleportService(RegionService regions, List<ExternalProtection> authorities) {
        this.regions = regions; this.authorities = List.copyOf(authorities);
    }
    public void region(Player player, String id) {
        requirePermission(player);
        Region region = regions.region(id).orElseThrow(() -> new IllegalArgumentException("La región ya no existe."));
        World world = world(region.world());
        Bounds b = region.bounds();
        int cx = (int) Math.floor(((double) b.minX() + b.maxX()) / 2);
        int cz = (int) Math.floor(((double) b.minZ() + b.maxZ()) / 2);
        // Centre first, then nearby columns, always inside the registered bounds.
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                long x = (long) cx + dx, z = (long) cz + dz;
                if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ()) continue;
                for (int y = Math.min(b.maxY(), world.getMaxHeight() - 2);
                     y >= Math.max(b.minY(), world.getMinHeight() + 1); y--) {
                    Location at = new Location(world, x + .5, y, z + .5);
                    if (world.getWorldBorder().isInside(at) && safe(at) && b.contains(at.getX(), at.getY() + 1, at.getZ())) {
                        teleport(player, at); return;
                    }
                }
            }
        }
        throw new IllegalArgumentException("No hay un punto seguro cerca del centro. Revisa el terreno o crea una ciudad segura.");
    }
    public void city(Player player, String id) throws SQLException {
        requirePermission(player);
        City city = regions.city(id).orElseThrow(() -> new IllegalArgumentException("La ciudad ya no existe."));
        Region region = regions.region(city.regionId()).orElseThrow(() -> new IllegalArgumentException("La región de la ciudad no existe."));
        if (!region.world().equals(city.world()) || !region.bounds().contains(city.x(), city.y(), city.z()))
            throw new IllegalArgumentException("La ciudad no está en su región registrada.");
        Location at = new Location(world(city.world()), city.x(), city.y(), city.z());
        if (!at.getWorld().getWorldBorder().isInside(at) || !safe(at) || !region.bounds().contains(at.getX(), at.getY() + 1, at.getZ()))
            throw new IllegalArgumentException("La ubicación guardada no es segura. Mueve la ciudad a un punto con suelo y espacio libre.");
        teleport(player, at);
    }
    private static World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException("El mundo no está cargado: " + name);
        return world;
    }
    public static boolean safe(Location at) {
        World world = at.getWorld();
        if (world == null || !Double.isFinite(at.getX()) || !Double.isFinite(at.getY()) || !Double.isFinite(at.getZ())
                || at.getY() < world.getMinHeight() + 1 || at.getY() + 1 >= world.getMaxHeight()) return false;
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
    private static void requirePermission(Player player) {
        if (!player.hasPermission("caloislands.admin")) throw new IllegalArgumentException("No tienes permiso administrativo.");
    }
    private void teleport(Player player, Location at) {
        try {
            for (ExternalProtection authority : authorities)
                if (authority.deniesEntry(player, at)) throw new IllegalArgumentException("Lands o WorldGuard impiden entrar en ese punto.");
        } catch (LinkageError failure) { throw new IllegalStateException("No se pudo comprobar la protección externa."); }
        at.setYaw(player.getLocation().getYaw()); at.setPitch(player.getLocation().getPitch());
        player.closeInventory();
        if (!player.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN))
            throw new IllegalArgumentException("Teletransporte cancelado por la protección del servidor.");
        player.sendMessage("§aTeletransporte completado.");
    }
}
