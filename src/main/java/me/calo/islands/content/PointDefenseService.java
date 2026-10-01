package me.calo.islands.content;

import me.calo.islands.core.ActivityService;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** Configured non-kill objective: activate a region point and defend it by remaining nearby. */
public final class PointDefenseService implements Listener {
    private final JavaPlugin plugin;
    private final ActivityService activities;
    private final RegionService regions;
    private final PointDefenseSettings settings;
    private final ActivityDefinition definition;
    private final Map<UUID, UUID> active = new HashMap<>();
    private BukkitTask task;

    public PointDefenseService(JavaPlugin plugin, ActivityService activities,
                               RegionService regions, PointDefenseSettings settings) {
        this.plugin = Objects.requireNonNull(plugin);
        this.activities = Objects.requireNonNull(activities);
        this.regions = Objects.requireNonNull(regions);
        this.settings = Objects.requireNonNull(settings);
        Region region = regions.region(settings.regionId()).orElseThrow(() ->
                new IllegalArgumentException("activity.defense.region-id does not exist"));
        Destination point = region.destination();
        if (point == null || !region.bounds().contains(point.x(), point.y(), point.z()))
            throw new IllegalArgumentException("activity.defense requires a point inside its region");
        definition = new ActivityDefinition(settings.id(), ActivityDefinition.Kind.EVENT,
                region.id(), point, true, java.util.Set.of());
    }

    public void start() throws SQLException {
        activities.registerResumable(settings.id(), this::forget, this::resume);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        active.clear();
    }

    /** Returns false when the player cannot activate this point. Never grants rewards itself. */
    public boolean activate(Player player) throws SQLException {
        if (!player.hasPermission("caloislands.activity") || !near(player, definition.entry()) || !regions.operational(
                regions.region(settings.regionId()).orElseThrow())) return false;
        UUID existing = active.get(player.getUniqueId());
        if (existing != null) {
            ActivityRun run = activities.checkpoint(existing).orElse(null);
            if (run != null && run.state() == ActivityRun.State.RUNNING) return true;
            active.remove(player.getUniqueId());
        }
        ActivityRun run = activities.start(definition, player);
        active.put(player.getUniqueId(), run.runId());
        player.sendMessage("§aPunto activado. Defiéndelo hasta completar el progreso.");
        return true;
    }

    public Optional<ActivityRun> current(UUID playerId) throws SQLException {
        UUID runId = active.get(playerId);
        return runId == null ? Optional.empty() : activities.checkpoint(runId);
    }

    public boolean cancel(Player player) throws SQLException {
        UUID runId = active.get(player.getUniqueId());
        if (runId == null) return false;
        activities.cancel(runId);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()
                || !event.getPlayer().getInventory().getItemInMainHand().getType().isAir()
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        try { activate(event.getPlayer()); }
        catch (SQLException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not activate defense point", failure);
            event.getPlayer().sendMessage("§cNo se confirmó la activación. Inténtalo después.");
        }
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID runId = active.get(event.getPlayer().getUniqueId());
        if (runId == null) return;
        try { activities.fail(runId); }
        catch (SQLException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Defense failure unconfirmed", failure);
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID runId = active.get(event.getPlayer().getUniqueId());
        if (runId == null) return;
        try { activities.cancel(runId); }
        catch (SQLException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Defense disconnect cleanup unconfirmed", failure);
        }
    }

    /** One committed checkpoint per second. A failed write freezes progress and is retried next tick. */
    public void tick() {
        for (Map.Entry<UUID, UUID> entry : List.copyOf(active.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                try { activities.cancel(entry.getValue()); }
                catch (SQLException | RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING, "Defense disconnect cleanup unconfirmed", failure);
                }
                continue;
            }
            try {
                ActivityRun run = activities.checkpoint(entry.getValue()).orElse(null);
                if (run == null || run.state() != ActivityRun.State.RUNNING) {
                    active.remove(entry.getKey());
                    continue;
                }
                Region region = regions.region(settings.regionId()).orElse(null);
                if (region == null || !regions.operational(region)
                        || !near(player, run.definition().entry())) {
                    activities.fail(run.runId());
                    player.sendMessage("§cLa defensa del punto fracasó.");
                    continue;
                }
                if (run.progress() >= settings.durationSeconds()) {
                    activities.complete(run.runId());
                    player.sendMessage("§aPunto defendido. Actividad completada.");
                    continue;
                }
                ActivityRun next = activities.progress(run.runId(), run.progress() + 1);
                player.sendActionBar(Component.text("Defensa: " + next.progress()
                        + " / " + settings.durationSeconds() + " s"));
            } catch (SQLException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Defense checkpoint unconfirmed", failure);
            }
        }
    }

    private boolean near(Player player, Destination point) {
        if (!player.getWorld().getName().equals(point.world())) return false;
        double dx = player.getLocation().getX() - point.x();
        double dy = player.getLocation().getY() - point.y();
        double dz = player.getLocation().getZ() - point.z();
        return dx * dx + dy * dy + dz * dz <= settings.radiusBlocks() * settings.radiusBlocks();
    }

    private void resume(ActivityRun run) {
        if (!run.definition().id().equals(settings.id()))
            throw new IllegalArgumentException("Defense checkpoint content mismatch");
        active.put(run.participant(), run.runId());
    }

    private void forget(ActivityRun run) { active.remove(run.participant(), run.runId()); }
}
