package me.calo.islands.content;

import me.calo.islands.data.ObjectiveDefinitionRepository;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/** One catalog for config defaults and MariaDB overrides; all runs still use ObjectiveActivityService. */
public final class ObjectiveCatalog {
    private final ObjectiveDefinitionRepository store;
    private final RegionService regions;
    private final BiPredicate<Player, Destination> access;
    private final Set<String> reservedIds;
    private final Map<String, ManagedObjective> definitions = new HashMap<>();
    private ObjectiveActivityService runtime;

    public ObjectiveCatalog(ObjectiveDefinitionRepository store, RegionService regions,
                            BiPredicate<Player, Destination> access,
                            Map<String, ObjectiveSettings> configured) throws SQLException {
        this(store, regions, access, configured, Set.of());
    }
    public ObjectiveCatalog(ObjectiveDefinitionRepository store, RegionService regions,
                            BiPredicate<Player, Destination> access,
                            Map<String, ObjectiveSettings> configured, Set<String> reservedIds) throws SQLException {
        this.store = Objects.requireNonNull(store); this.regions = Objects.requireNonNull(regions);
        this.access = Objects.requireNonNull(access);
        this.reservedIds = Set.copyOf(reservedIds);
        for (ObjectiveSettings objective : configured.values()) {
            Region region = regions.region(objective.regionId()).orElseThrow(() ->
                    new IllegalArgumentException("Unknown configured activity region: " + objective.id()));
            definitions.put(objective.id(), new ManagedObjective(objective,
                    Objects.requireNonNull(region.destination(), "Activity start point missing"), true, false, 0));
        }
        for (ManagedObjective persisted : store.list()) definitions.put(persisted.id(), persisted);
        if (list().size() > 100) throw new IllegalArgumentException("Too many activities");
        for (ManagedObjective value : list()) validate(value);
    }
    public void attach(ObjectiveActivityService runtime) { this.runtime = Objects.requireNonNull(runtime); }
    public List<ManagedObjective> list() {
        return definitions.values().stream().filter(value -> !value.deleted())
                .map(this::withCurrentConfigPoint)
                .sorted(Comparator.comparing(value -> value.settings().name())).toList();
    }
    public ManagedObjective require(String id) {
        ManagedObjective value = definitions.get(id);
        if (value == null || value.deleted()) throw new IllegalArgumentException("Actividad desconocida.");
        return withCurrentConfigPoint(value);
    }
    public Map<String, ObjectiveSettings> enabledSettings() {
        Map<String, ObjectiveSettings> result = new HashMap<>();
        for (ManagedObjective value : list()) if (value.enabled()) result.put(value.id(), value.settings());
        return Map.copyOf(result);
    }
    public Map<String, Destination> enabledStarts() {
        Map<String, Destination> result = new HashMap<>();
        for (ManagedObjective value : list()) if (value.enabled() && value.revision() > 0)
            result.put(value.id(), value.start());
        return Map.copyOf(result);
    }
    public ManagedObjective create(Player actor, ObjectiveSettings settings, Destination start) throws SQLException {
        authorize(actor);
        if (reservedIds.contains(settings.id())) throw new IllegalArgumentException("El identificador pertenece a otra actividad.");
        requireOrdinaryMob(settings);
        ManagedObjective previous = definitions.get(settings.id());
        if (previous != null && !previous.deleted()) throw new IllegalArgumentException("El identificador ya existe.");
        if (previous == null && list().size() >= 100) throw new IllegalArgumentException("Límite de actividades alcanzado.");
        ManagedObjective next = new ManagedObjective(settings, start, false, false,
                previous == null ? 1 : previous.revision() + 1);
        validate(next); requireAccess(actor, next);
        persist(next, previous == null ? 0 : previous.revision());
        return next;
    }
    public ManagedObjective edit(Player actor, String id, long expectedRevision,
                                 ObjectiveSettings settings, Destination start) throws SQLException {
        authorize(actor); ManagedObjective previous = current(id, expectedRevision);
        if (!settings.id().equals(id)) throw new IllegalArgumentException("El identificador no se puede cambiar.");
        requireOrdinaryMob(settings);
        requireIdle(id);
        ManagedObjective next = previous.next(settings, start, previous.enabled(), false);
        validate(next); requireAccess(actor, next); persist(next, previous.revision());
        return next;
    }
    public ManagedObjective setEnabled(Player actor, String id, long expectedRevision, boolean enabled) throws SQLException {
        authorize(actor); ManagedObjective previous = current(id, expectedRevision);
        requireIdle(id);
        ManagedObjective next = previous.next(previous.settings(), previous.start(), enabled, false);
        validate(next); if (enabled) requireAccess(actor, next);
        persist(next, previous.revision()); return next;
    }
    public void delete(Player actor, String id, long expectedRevision) throws SQLException {
        authorize(actor); ManagedObjective previous = current(id, expectedRevision);
        requireIdle(id);
        persist(previous.next(previous.settings(), previous.start(), false, true), previous.revision());
    }
    public void cancel(Player actor, String id) throws SQLException {
        authorize(actor); require(id);
        if (runtime == null || !runtime.cancel(actor, id)) throw new IllegalStateException("No hay ejecución activa.");
    }
    private ManagedObjective current(String id, long revision) {
        ManagedObjective previous = require(id);
        if (previous.revision() != revision) throw new IllegalStateException("La actividad cambió. Vuelve a abrirla.");
        return previous;
    }
    private ManagedObjective withCurrentConfigPoint(ManagedObjective value) {
        if (value.revision() != 0) return value;
        Destination current = regions.region(value.settings().regionId()).map(Region::destination).orElse(null);
        return current == null ? value : new ManagedObjective(value.settings(), current, value.enabled(), false, 0);
    }
    private void persist(ManagedObjective next, long expectedRevision) throws SQLException {
        store.save(next, expectedRevision);
        definitions.put(next.id(), next);
        if (runtime != null) {
            if (next.deleted() || !next.enabled()) runtime.remove(next.id());
            else runtime.upsert(next.settings(), next.start());
        }
    }
    private void requireIdle(String id) throws SQLException {
        if (runtime != null && runtime.current(id).isPresent())
            throw new IllegalStateException("Cancela la ejecución antes de cambiar esta actividad.");
    }
    private static void authorize(Player actor) {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread())
            throw new IllegalStateException("La administración de actividades requiere el hilo del servidor.");
        if (actor == null || !actor.isOnline() || !actor.hasPermission("caloislands.admin"))
            throw new IllegalArgumentException("No tienes permiso para administrar actividades.");
    }
    private void requireAccess(Player actor, ManagedObjective value) {
        if (!access.test(actor, value.start()))
            throw new IllegalArgumentException("Lands o WorldGuard deniegan el punto de inicio.");
    }
    private void validate(ManagedObjective value) {
        ObjectiveSettings objective = value.settings();
        Region region = regions.region(objective.regionId()).orElseThrow(() ->
                new IllegalArgumentException("La región de la actividad no existe."));
        Destination start = value.start();
        if (!region.world().equals(start.world()) || !region.bounds().contains(start.x(), start.y(), start.z()))
            throw new IllegalArgumentException("El inicio debe estar dentro de la región.");
        if (objective.mode() == ObjectiveSettings.Mode.ESCORT) {
            ObjectiveSettings.Point first = objective.route().getFirst();
            if (Math.pow(first.x()-start.x(),2)+Math.pow(first.y()-start.y(),2)+Math.pow(first.z()-start.z(),2)
                    > objective.radius()*objective.radius())
                throw new IllegalArgumentException("La ruta empieza fuera del radio de inicio.");
            for (ObjectiveSettings.Point point : objective.route())
                if (!region.bounds().contains(point.x(), point.y(), point.z()))
                    throw new IllegalArgumentException("La ruta sale de la región.");
        }
    }
    public static boolean ordinaryMob(EntityType type) {
        return type != null && type.isAlive() && type.isSpawnable()
                && !Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON,
                        EntityType.WARDEN, EntityType.ELDER_GUARDIAN).contains(type);
    }
    private static void requireOrdinaryMob(ObjectiveSettings settings) {
        if (settings.mode() == ObjectiveSettings.Mode.MOB_EVENT && !ordinaryMob(settings.mobType()))
            throw new IllegalArgumentException("El evento solo admite mobs normales.");
    }
}
