package me.calo.islands.content;

import me.calo.islands.core.ActivityService;
import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/** Configured cooperative objectives. All score changes use ActivityStore transactions. */
public final class ObjectiveActivityService implements Listener {
    @FunctionalInterface public interface ActionAccess {
        boolean allowed(Player player, ProtectionSettings.Action action, Location location);
    }
    private final JavaPlugin plugin;
    private final ActivityService activities;
    private final RegionService regions;
    private final Map<String, ObjectiveSettings> settings;
    private final Map<String, Destination> starts;
    private final BiPredicate<Player, Location> accessAllowed;
    private final ActionAccess actionAccess;
    private final NamespacedKey entityKey;
    private final Map<String, UUID> active = new HashMap<>();
    private final Map<UUID, Set<UUID>> spawned = new HashMap<>();
    private final Set<String> registered = new HashSet<>();
    private BukkitTask task;
    private boolean started;
    private UUID spawningRunId;

    public ObjectiveActivityService(JavaPlugin plugin, ActivityService activities, RegionService regions,
                                    Map<String, ObjectiveSettings> settings,
                                    BiPredicate<Player, Location> accessAllowed) {
        this(plugin, activities, regions, settings,
                (player, action, location) -> accessAllowed.test(player, location));
    }
    public ObjectiveActivityService(JavaPlugin plugin, ActivityService activities, RegionService regions,
                                    Map<String, ObjectiveSettings> settings, ActionAccess actionAccess) {
        this(plugin, activities, regions, settings, Map.of(), actionAccess);
    }
    public ObjectiveActivityService(JavaPlugin plugin, ActivityService activities, RegionService regions,
                                    Map<String, ObjectiveSettings> settings, Map<String, Destination> starts,
                                    ActionAccess actionAccess) {
        this.plugin = Objects.requireNonNull(plugin);
        this.activities = Objects.requireNonNull(activities);
        this.regions = Objects.requireNonNull(regions);
        if (settings.size() > 100) throw new IllegalArgumentException("Too many activity objectives");
        this.settings = new HashMap<>(settings);
        this.starts = new HashMap<>(starts);
        this.actionAccess = Objects.requireNonNull(actionAccess);
        this.accessAllowed = (player, location) -> actionAccess.allowed(player,
                ProtectionSettings.Action.BLOCK_INTERACT, location);
        this.entityKey = new NamespacedKey(plugin, "activity_entity");
        for (ObjectiveSettings objective : settings.values()) {
            validateRegion(objective);
        }
    }

    public void start() throws SQLException {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities())
            if (entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING)) entity.remove();
        for (ObjectiveSettings objective : settings.values()) {
            activities.registerResumable(objective.id(), this::forget, this::resume);
            registered.add(objective.id());
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        started = true;
    }
    public void stop() {
        if (task != null) task.cancel();
        for (UUID runId : List.copyOf(spawned.keySet())) removeEntities(runId);
        active.clear();
    }
    public List<ObjectiveSettings> definitions() {
        return settings.values().stream().sorted(Comparator.comparing(ObjectiveSettings::name)).toList();
    }
    public Destination startPoint(String id) {
        ObjectiveSettings objective = require(id);
        return point(objective);
    }
    /** Admin changes use the same runtime and recovery handlers as configured objectives. */
    public void upsert(ObjectiveSettings objective, Destination start) throws SQLException {
        boolean newlyRegistered = !settings.containsKey(objective.id());
        if (newlyRegistered && settings.size() >= 100)
            throw new IllegalArgumentException("Too many activity objectives");
        if (active.containsKey(objective.id())) throw new IllegalStateException("Cancela la ejecución antes de editar.");
        Destination previous = starts.get(objective.id());
        if (start == null) starts.remove(objective.id()); else starts.put(objective.id(), start);
        try { validateRegion(objective); }
        catch (RuntimeException invalid) {
            if (previous == null) starts.remove(objective.id()); else starts.put(objective.id(), previous);
            throw invalid;
        }
        settings.put(objective.id(), objective);
        if (started && registered.add(objective.id()))
            activities.registerResumable(objective.id(), this::forget, this::resume);
    }
    public void remove(String id) {
        if (active.containsKey(id)) throw new IllegalStateException("Cancela la ejecución antes de desactivar o eliminar.");
        settings.remove(id); starts.remove(id);
    }
    public Optional<ActivityRun> current(String id) throws SQLException {
        UUID runId = active.get(id);
        return runId == null ? Optional.empty() : activities.checkpoint(runId);
    }
    public List<ActivityMember> members(UUID runId) throws SQLException { return activities.members(runId); }

    /** A narrow exception to CaloIslands region protection for the confirmed objective action. */
    public boolean allowsBlockAction(Player player, Block block, ObjectiveSettings.Mode mode) {
        if (mode != ObjectiveSettings.Mode.STRUCTURE && mode != ObjectiveSettings.Mode.COLLECTION)
            return false;
        for (ObjectiveSettings objective : settings.values()) {
            if (objective.mode() != mode || objective.blockMaterial() != block.getType()) continue;
            UUID runId = active.get(objective.id());
            if (runId == null || !near(player, block.getLocation(), objective.radius())
                    || !nearPoint(block.getLocation(), objective)
                    || !actionAccess.allowed(player, mode == ObjectiveSettings.Mode.COLLECTION
                        ? ProtectionSettings.Action.BLOCK_BREAK : ProtectionSettings.Action.BLOCK_INTERACT,
                        block.getLocation())) continue;
            try {
                if (activeMember(runId, player.getUniqueId(), objective.perPlayerLimit())) return true;
            } catch (SQLException | RuntimeException unavailable) { return false; }
        }
        return false;
    }

    /** Only tagged normal event mobs and active participants receive the scoped combat exception. */
    public boolean allowsMobCombat(Entity damager, Entity victim) {
        Player player = damager instanceof Player direct ? direct
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                    ? shooter : victim instanceof Player target ? target : null;
        Entity mob = victim instanceof Player && damager instanceof Projectile shot
                && shot.getShooter() instanceof Entity shooter ? shooter
                : victim instanceof Player ? damager : victim;
        if (player == null) return false;
        String raw = mob.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (raw == null) return false;
        UUID runId;
        try { runId = UUID.fromString(raw); } catch (IllegalArgumentException malformed) { return false; }
        ObjectiveSettings objective = objective(runId);
        if (objective == null || objective.mode() != ObjectiveSettings.Mode.MOB_EVENT
                || !near(player, mob.getLocation(), objective.radius())
                || !nearPoint(mob.getLocation(), objective)
                || !actionAccess.allowed(player, ProtectionSettings.Action.PVE_DAMAGE, mob.getLocation()))
            return false;
        try { return activeMember(runId, player.getUniqueId(),
                victim instanceof Player ? Long.MAX_VALUE : objective.perPlayerLimit()); }
        catch (SQLException | RuntimeException unavailable) { return false; }
    }

    public boolean allowsInternalSpawn(Location location) {
        UUID runId = spawningRunId;
        ObjectiveSettings objective = runId == null ? null : objective(runId);
        if (objective == null || location.getWorld() == null) return false;
        if (objective.mode() == ObjectiveSettings.Mode.MOB_EVENT) return nearPoint(location, objective);
        if (objective.mode() != ObjectiveSettings.Mode.ESCORT) return false;
        Region region = regions.region(objective.regionId()).orElse(null);
        return region != null && regions.operational(region)
                && region.world().equals(location.getWorld().getName())
                && region.bounds().contains(location.getX(), location.getY(), location.getZ())
                && regions.matchingAt(region.world(), location.getX(), location.getY(), location.getZ())
                        .stream().map(Region::id).toList().equals(List.of(region.id()));
    }

    private boolean activeMember(UUID runId, UUID playerId, long limit) throws SQLException {
        ActivityRun run = activities.checkpoint(runId).orElse(null);
        return run != null && run.state() == ActivityRun.State.RUNNING && activities.members(runId).stream()
                .anyMatch(member -> member.playerId().equals(playerId) && member.active()
                        && member.contribution() < limit);
    }

    private boolean nearPoint(Location location, ObjectiveSettings objective) {
        Region region = regions.region(objective.regionId()).orElse(null);
        Destination point = region == null ? null : point(objective);
        return region != null && regions.operational(region) && point != null
                && location.getWorld() != null && location.getWorld().getName().equals(point.world())
                && location.distanceSquared(new Location(location.getWorld(), point.x(), point.y(), point.z()))
                        <= objective.radius() * objective.radius()
                && regions.matchingAt(point.world(), location.getX(), location.getY(), location.getZ())
                        .stream().map(Region::id).toList().equals(List.of(region.id()));
    }

    public ActivityRun activate(Player player, String id) throws SQLException {
        ObjectiveSettings objective = require(id);
        if (active.containsKey(id)) throw new IllegalStateException("La actividad ya está en curso; únete a ella.");
        Region region = validateRegion(objective);
        Destination point = point(objective);
        if (!near(player, point, objective.radius())
                || !accessAllowed.test(player, player.getLocation()))
            throw new IllegalArgumentException("Acércate al punto con acceso permitido.");
        ActivityDefinition definition = new ActivityDefinition(id, ActivityDefinition.Kind.EVENT,
                region.id(), point, true, Set.of("caloislands.activity"));
        ActivityRun run = activities.start(definition, player);
        active.put(id, run.runId());
        try {
            activities.join(run.runId(), player, objective.maxParticipants());
            ensureEntities(run.runId(), objective, 0);
            return activities.checkpoint(run.runId()).orElseThrow();
        } catch (SQLException | RuntimeException failure) {
            try { activities.cancel(run.runId()); } catch (SQLException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
    public ActivityRun join(Player player, String id) throws SQLException {
        ObjectiveSettings objective = require(id);
        UUID runId = active.get(id);
        if (runId == null) throw new IllegalStateException("La actividad no está en curso.");
        ActivityRun run = activities.checkpoint(runId).orElseThrow();
        Entity escort = objective.mode() == ObjectiveSettings.Mode.ESCORT ? firstEntity(runId) : null;
        if (!(escort == null ? near(player, run.definition().entry(), objective.radius())
                : near(player, escort.getLocation(), objective.radius()))
                || !accessAllowed.test(player, player.getLocation()))
            throw new IllegalArgumentException("Acércate al punto con acceso permitido.");
        return activities.join(runId, player, objective.maxParticipants());
    }
    public boolean leave(Player player) throws SQLException {
        boolean left = false;
        for (UUID runId : List.copyOf(active.values())) {
            if (activities.members(runId).stream().noneMatch(m -> m.playerId().equals(player.getUniqueId()) && m.active()))
                continue;
            activities.leave(runId, player.getUniqueId());
            cancelIfEmpty(runId);
            left = true;
        }
        return left;
    }
    public boolean cancel(Player player, String id) throws SQLException {
        UUID runId = active.get(id);
        if (runId == null) return false;
        ActivityRun run = activities.checkpoint(runId).orElseThrow();
        if (!run.participant().equals(player.getUniqueId()) && !player.hasPermission("caloislands.admin"))
            throw new IllegalArgumentException("Solo quien inició la actividad puede cancelarla.");
        activities.cancel(runId);
        return true;
    }

    public void tick() {
        for (Map.Entry<String, UUID> entry : List.copyOf(active.entrySet())) {
            try {
                ObjectiveSettings objective = require(entry.getKey());
                ActivityRun run = activities.checkpoint(entry.getValue()).orElse(null);
                if (run == null || run.state() != ActivityRun.State.RUNNING) {
                    active.remove(entry.getKey(), entry.getValue());
                    removeEntities(entry.getValue());
                    continue;
                }
                if (Duration.between(activities.startedAt(run.runId()), Instant.now()).getSeconds()
                        >= objective.timeoutSeconds()) { activities.fail(run.runId()); continue; }
                Region region = regions.region(objective.regionId()).orElse(null);
                if (region == null || !regions.operational(region)) { activities.fail(run.runId()); continue; }
                List<Player> present = present(run.runId(), objective);
                if (present.isEmpty()) { cancelIfEmpty(run.runId()); continue; }
                if (run.progress() >= objective.goal()) { activities.complete(run.runId()); continue; }
                switch (objective.mode()) {
                    case DEFENSE, CAPTURE_CONTROL -> {
                        for (Player player : present) {
                            UUID action = actionId("TICK", run.runId(), player.getUniqueId(),
                                    Long.toString(System.currentTimeMillis() / 1000));
                            try { run = activities.contribute(run.runId(), player, action, 1,
                                    objective.perPlayerLimit()); }
                            catch (IllegalStateException limit) { continue; }
                            String stage = objective.mode() == ObjectiveSettings.Mode.CAPTURE_CONTROL
                                    ? (run.progress() < objective.captureAt() ? "Captura" : "Control") : "Defensa";
                            player.sendActionBar(Component.text(objective.name() + " · " + stage + ": "
                                    + Math.min(run.progress(), objective.goal()) + "/" + objective.goal()));
                            if (run.progress() >= objective.goal()) break;
                        }
                    }
                    case ESCORT -> {
                        ensureEntities(run.runId(), objective, run.progress());
                        Entity escort = firstEntity(run.runId());
                        if (escort != null) {
                            Player nearby = present.stream().filter(p -> near(p, escort.getLocation(), objective.radius()))
                                    .findFirst().orElse(null);
                            if (nearby != null) {
                                UUID action = actionId("ESCORT", run.runId(), nearby.getUniqueId(),
                                        Long.toString(System.currentTimeMillis() / 1000));
                                run = activities.contribute(run.runId(), nearby, action, 1,
                                        objective.perPlayerLimit());
                                escort.teleport(routeLocation(objective, run.progress()));
                            }
                        }
                    }
                    case MOB_EVENT -> ensureEntities(run.runId(), objective, run.progress());
                    case STRUCTURE, COLLECTION -> { }
                }
                if (run.progress() >= objective.goal()) activities.complete(run.runId());
            } catch (SQLException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Activity checkpoint unconfirmed", failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        scoreBlock(event.getPlayer(), event.getClickedBlock(), ObjectiveSettings.Mode.STRUCTURE);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        scoreBlock(event.getPlayer(), event.getBlock(), ObjectiveSettings.Mode.COLLECTION);
    }
    private void scoreBlock(Player player, Block block, ObjectiveSettings.Mode mode) {
        for (ObjectiveSettings objective : settings.values()) {
            if (objective.mode() != mode || block.getType() != objective.blockMaterial()) continue;
            UUID runId = active.get(objective.id());
            if (runId == null || !allowsBlockAction(player, block, mode)) continue;
            UUID action = actionId(mode.name(), runId, null, block.getWorld().getName() + ":"
                    + block.getX() + ":" + block.getY() + ":" + block.getZ());
            try {
                ActivityRun run = activities.contribute(runId, player, action, 1, objective.perPlayerLimit());
                if (run.progress() >= objective.goal()) activities.complete(runId);
            } catch (SQLException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Activity interaction unconfirmed", failure);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobDeath(EntityDeathEvent event) {
        String raw = event.getEntity().getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (raw == null) return;
        event.getDrops().clear(); event.setDroppedExp(0);
        UUID runId;
        try { runId = UUID.fromString(raw); } catch (IllegalArgumentException invalid) { return; }
        Set<UUID> entities = spawned.get(runId);
        if (entities != null) entities.remove(event.getEntity().getUniqueId());
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        ObjectiveSettings objective = objective(runId);
        if (objective == null || objective.mode() != ObjectiveSettings.Mode.MOB_EVENT
                || !allowsMobCombat(killer, event.getEntity())) return;
        try {
            ActivityRun run = activities.contribute(runId, killer, event.getEntity().getUniqueId(),
                    1, objective.perPlayerLimit());
            if (run.progress() >= objective.goal()) activities.complete(runId);
        } catch (SQLException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Activity mob death unconfirmed", failure);
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        try { leave(event.getPlayer()); }
        catch (SQLException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Activity disconnect cleanup unconfirmed", failure);
        }
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING)
                    && spawned.values().stream().noneMatch(ids -> ids.contains(entity.getUniqueId()))) entity.remove();
        }
    }

    private void cancelIfEmpty(UUID runId) throws SQLException {
        if (activities.members(runId).stream().noneMatch(ActivityMember::active)) activities.cancel(runId);
    }
    private List<Player> present(UUID runId, ObjectiveSettings objective) throws SQLException {
        List<Player> players = new ArrayList<>();
        ActivityRun run = activities.checkpoint(runId).orElseThrow();
        Entity escort = objective.mode() == ObjectiveSettings.Mode.ESCORT ? firstEntity(runId) : null;
        for (ActivityMember member : activities.members(runId)) {
            if (!member.active()) continue;
            Player player = Bukkit.getPlayer(member.playerId());
            if (player != null && player.isOnline()
                    && (escort == null ? near(player, run.definition().entry(), objective.radius())
                    : near(player, escort.getLocation(), objective.radius()))
                    && accessAllowed.test(player, player.getLocation())) players.add(player);
        }
        players.sort(Comparator.comparing(p -> p.getUniqueId().toString()));
        return players;
    }
    private ObjectiveSettings require(String id) {
        ObjectiveSettings result = settings.get(id);
        if (result == null) throw new IllegalArgumentException("Actividad desconocida.");
        return result;
    }
    private ObjectiveSettings objective(UUID runId) {
        for (Map.Entry<String, UUID> entry : active.entrySet())
            if (entry.getValue().equals(runId)) return settings.get(entry.getKey());
        return null;
    }
    private Region validateRegion(ObjectiveSettings objective) {
        Region region = regions.region(objective.regionId()).orElseThrow(() ->
                new IllegalArgumentException("Activity region does not exist: " + objective.id()));
        Destination point = point(objective);
        if (point == null || !region.bounds().contains(point.x(), point.y(), point.z()))
            throw new IllegalArgumentException("Activity point is missing or outside its region: " + objective.id());
        if (objective.mode() == ObjectiveSettings.Mode.ESCORT) {
            ObjectiveSettings.Point first = objective.route().getFirst();
            if (Math.pow(first.x() - point.x(), 2) + Math.pow(first.y() - point.y(), 2)
                    + Math.pow(first.z() - point.z(), 2) > objective.radius() * objective.radius())
                throw new IllegalArgumentException("Escort route starts beyond activity point: " + objective.id());
            for (ObjectiveSettings.Point p : objective.route())
                if (!region.bounds().contains(p.x(), p.y(), p.z()))
                    throw new IllegalArgumentException("Escort route leaves its region: " + objective.id());
        }
        return region;
    }
    private static boolean near(Player player, Destination point, double radius) {
        return player.getWorld().getName().equals(point.world())
                && near(player, new Location(player.getWorld(), point.x(), point.y(), point.z()), radius);
    }
    private static boolean near(Player player, Location point, double radius) {
        return player.getWorld().equals(point.getWorld())
                && player.getLocation().distanceSquared(point) <= radius * radius;
    }
    private static UUID actionId(String type, UUID runId, UUID playerId, String key) {
        return UUID.nameUUIDFromBytes((type + ":" + runId + ":" + playerId + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }
    private void resume(ActivityRun run) {
        ObjectiveSettings objective = require(run.definition().id());
        UUID previous = active.putIfAbsent(objective.id(), run.runId());
        if (previous != null && !previous.equals(run.runId()))
            throw new IllegalStateException("Duplicate running activity for " + objective.id());
        ensureEntities(run.runId(), objective, run.progress());
    }
    private void forget(ActivityRun run) {
        active.remove(run.definition().id(), run.runId());
        removeEntities(run.runId());
    }
    private void removeEntities(UUID runId) {
        Set<UUID> ids = spawned.remove(runId);
        if (ids == null) return;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }
    private Entity firstEntity(UUID runId) {
        Set<UUID> ids = spawned.get(runId);
        if (ids == null) return null;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) return entity;
        }
        return null;
    }
    private void ensureEntities(UUID runId, ObjectiveSettings objective, long progress) {
        if (objective.mode() != ObjectiveSettings.Mode.ESCORT
                && objective.mode() != ObjectiveSettings.Mode.MOB_EVENT) return;
        Set<UUID> ids = spawned.computeIfAbsent(runId, ignored -> new HashSet<>());
        ids.removeIf(id -> { Entity entity = Bukkit.getEntity(id); return entity == null || !entity.isValid(); });
        if (objective.mode() == ObjectiveSettings.Mode.MOB_EVENT) {
            Location point = pointLocation(objective);
            for (UUID id : ids) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null && (entity.getWorld() != point.getWorld()
                        || entity.getLocation().distanceSquared(point) > objective.radius() * objective.radius()))
                    entity.teleport(point);
            }
        }
        int wanted = objective.mode() == ObjectiveSettings.Mode.ESCORT ? 1
                : Math.max(0, objective.goal() - (int) progress);
        if (progress >= objective.goal()) wanted = 0;
        while (ids.size() < wanted) {
            Location location = objective.mode() == ObjectiveSettings.Mode.ESCORT
                    ? routeLocation(objective, progress)
                    : pointLocation(objective);
            LivingEntity entity;
            spawningRunId = runId;
            try {
                entity = (LivingEntity) location.getWorld().spawnEntity(location,
                        objective.mode() == ObjectiveSettings.Mode.ESCORT ? EntityType.VILLAGER : objective.mobType());
            } finally { spawningRunId = null; }
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
            if (objective.mode() == ObjectiveSettings.Mode.ESCORT) {
                entity.setAI(false); entity.setInvulnerable(true);
                entity.customName(Component.text(objective.name())); entity.setCustomNameVisible(true);
            }
            entity.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, runId.toString());
            ids.add(entity.getUniqueId());
        }
    }
    private Location pointLocation(ObjectiveSettings objective) {
        validateRegion(objective);
        Destination point = point(objective);
        World world = Bukkit.getWorld(point.world());
        if (world == null) throw new IllegalStateException("Activity world unloaded");
        return new Location(world, point.x(), point.y(), point.z());
    }
    private Destination point(ObjectiveSettings objective) {
        return starts.getOrDefault(objective.id(), regions.region(objective.regionId())
                .map(Region::destination).orElse(null));
    }
    private Location routeLocation(ObjectiveSettings objective, long progress) {
        World world = Bukkit.getWorld(validateRegion(objective).world());
        if (world == null) throw new IllegalStateException("Escort world unloaded");
        double remaining = Math.min(progress * objective.stepBlocks(),
                objective.goal() * objective.stepBlocks());
        List<ObjectiveSettings.Point> route = objective.route();
        for (int i = 1; i < route.size(); i++) {
            ObjectiveSettings.Point from = route.get(i - 1), to = route.get(i);
            double length = Math.sqrt(Math.pow(to.x() - from.x(), 2)
                    + Math.pow(to.y() - from.y(), 2) + Math.pow(to.z() - from.z(), 2));
            if (remaining <= length) {
                double fraction = length == 0 ? 1 : remaining / length;
                return new Location(world, from.x() + (to.x() - from.x()) * fraction,
                        from.y() + (to.y() - from.y()) * fraction,
                        from.z() + (to.z() - from.z()) * fraction);
            }
            remaining -= length;
        }
        ObjectiveSettings.Point last = route.getLast();
        return new Location(world, last.x(), last.y(), last.z());
    }
}
