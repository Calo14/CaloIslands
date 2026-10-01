package me.calo.islands.domain;

import me.calo.islands.data.RegionRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Local mutations are serialized; MariaDB is durable and a snapshot serves movement checks. */
public final class RegionService {
    private final RegionRepository store;
    private final Function<String, Optional<WorldHeight>> worldHeights;
    private volatile List<Region> snapshot;

    public record WorldHeight(int min, int maxExclusive) {
        public WorldHeight {
            if (maxExclusive <= min) throw new IllegalArgumentException("Invalid world height range");
        }
    }

    public RegionService(RegionRepository store,
                         Function<String, Optional<WorldHeight>> worldHeights) throws SQLException {
        this.store = store;
        this.worldHeights = worldHeights;
        this.snapshot = store.regions();
    }

    public synchronized Region createRegion(String id, String world, Bounds bounds) throws SQLException {
        Region candidate = new Region(id, world, bounds, false, 1);
        Optional<Region> existing = region(id);
        if (existing.isPresent()) {
            if (existing.get().equals(candidate)) return existing.get();
            throw new IllegalArgumentException("Region ID already has different data: " + id);
        }
        requireValidBounds(world, bounds);
        requireNoOverlap(candidate);
        store.insert(candidate);
        withRegion(candidate);
        return candidate;
    }

    public synchronized Region resizeRegion(String id, Bounds bounds) throws SQLException {
        Region current = requireRegion(id);
        if (current.bounds().equals(bounds)) return current;
        if (current.active()) throw new IllegalStateException("Deactivate the region before resizing");
        requireValidBounds(current.world(), bounds);
        for (City city : store.cities()) {
            if (city.regionId().equals(id) && !bounds.contains(city.x(), city.y(), city.z())) {
                throw new IllegalArgumentException("City " + city.id() + " would be outside the region");
            }
        }
        Region next = new Region(id, current.world(), bounds, false, current.version() + 1, current.destination());
        requireNoOverlap(next);
        save(next, current.version());
        withRegion(next);
        return next;
    }

    public synchronized Region setActive(String id, boolean active) throws SQLException {
        Region current = requireRegion(id);
        if (current.active() == active) return current;
        if (active) requireLoadedWorld(current.world());
        Region next = new Region(id, current.world(), current.bounds(), active, current.version() + 1, current.destination());
        save(next, current.version());
        withRegion(next);
        return next;
    }

    public synchronized Region setRegionPoint(String id, Destination destination) throws SQLException {
        Region current = requireRegion(id);
        if (java.util.Objects.equals(current.destination(), destination)) return current;
        if (destination != null) {
            if (!current.world().equals(destination.world())) throw new IllegalArgumentException("El punto debe estar en el mundo de la región.");
            WorldHeight height = requireLoadedWorld(destination.world());
            if (destination.y() < height.min() || destination.y() >= height.maxExclusive())
                throw new IllegalArgumentException("La altura del punto no es válida.");
        }
        Region next = new Region(id, current.world(), current.bounds(), current.active(), current.version()+1, destination);
        save(next, current.version()); withRegion(next); return next;
    }

    public synchronized City createCity(String id, String regionId, double x, double y, double z) throws SQLException {
        return createCity(id, regionId, x, y, z, 0, 0);
    }
    public synchronized City createCity(String id, String regionId, double x, double y, double z, float yaw, float pitch) throws SQLException {
        Region region = requireRegion(regionId);
        City city = new City(id, regionId, region.world(), x, y, z, 1, yaw, pitch);
        Optional<City> existing = store.city(id);
        if (existing.isPresent()) {
            if (existing.get().equals(city)) return existing.get();
            throw new IllegalArgumentException("City ID already has different data: " + id);
        }
        requireLoadedWorld(region.world());
        requireInside(region, x, y, z);
        store.insert(city);
        return city;
    }

    public synchronized City moveCity(String id, String regionId, double x, double y, double z) throws SQLException {
        City old = requireCity(id);
        return moveCity(id, regionId, x, y, z, old.yaw(), old.pitch());
    }
    public synchronized City moveCity(String id, String regionId, double x, double y, double z, float yaw, float pitch) throws SQLException {
        City current = requireCity(id);
        Region region = requireRegion(regionId);
        if (current.regionId().equals(regionId) && Double.compare(current.x(), x) == 0
                && Double.compare(current.y(), y) == 0 && Double.compare(current.z(), z) == 0 && current.yaw() == yaw && current.pitch() == pitch) return current;
        requireLoadedWorld(region.world());
        requireInside(region, x, y, z);
        City next = new City(id, regionId, region.world(), x, y, z, current.version() + 1, yaw, pitch);
        if (!store.update(next, current.version())) throw new IllegalStateException("City changed concurrently");
        return next;
    }

    public synchronized void deleteCity(String id) throws SQLException {
        City city = requireCity(id);
        if (!store.deleteCity(id, city.version())) throw new IllegalStateException("City changed concurrently");
    }

    public synchronized void deleteRegion(String id) throws SQLException {
        Region region = requireRegion(id);
        if (region.active()) throw new IllegalStateException("Deactivate the region before deleting");
        if (store.cities().stream().anyMatch(city -> city.regionId().equals(id))) {
            throw new IllegalStateException("Delete associated cities first");
        }
        if (!store.deleteRegion(id, region.version())) throw new IllegalStateException("Region changed concurrently");
        snapshot = snapshot.stream().filter(value -> !value.id().equals(id)).toList();
    }

    public List<Region> regions() { return snapshot; }
    public List<City> cities() throws SQLException { return store.cities(); }
    public Optional<Region> region(String id) {
        return snapshot.stream().filter(value -> value.id().equals(id)).findFirst();
    }
    public Optional<City> city(String id) throws SQLException { return store.city(id); }

    public Optional<Region> at(String world, double x, double y, double z) {
        return RegionAccessPolicy.at(snapshot, world, x, y, z);
    }

    public List<Region> matchingAt(String world, double x, double y, double z) {
        return RegionAccessPolicy.atAll(snapshot, world, x, y, z);
    }

    public boolean operational(Region region) {
        return region.active() && worldHeights.apply(region.world()).isPresent();
    }

    /** Existing occupants may leave or move within a deactivated region. New entry is denied. */
    public boolean mayEnter(String fromWorld, double fromX, double fromY, double fromZ,
                            String toWorld, double toX, double toY, double toZ) {
        return mayEnter(fromWorld, fromX, fromY, fromZ, toWorld, toX, toY, toZ, false);
    }

    public boolean mayEnter(String fromWorld, double fromX, double fromY, double fromZ,
                            String toWorld, double toX, double toY, double toZ, boolean denyActiveEntry) {
        return RegionAccessPolicy.mayEnter(snapshot, name -> worldHeights.apply(name).isPresent(),
                fromWorld, fromX, fromY, fromZ,
                toWorld, toX, toY, toZ, denyActiveEntry);
    }

    private Region requireRegion(String id) throws SQLException {
        return store.region(id).orElseThrow(() -> new IllegalArgumentException("Unknown region: " + id));
    }

    private City requireCity(String id) throws SQLException {
        return store.city(id).orElseThrow(() -> new IllegalArgumentException("Unknown city: " + id));
    }

    private void requireNoOverlap(Region candidate) throws SQLException {
        for (Region existing : snapshot) {
            if (!existing.id().equals(candidate.id()) && existing.world().equals(candidate.world())
                    && existing.bounds().overlaps(candidate.bounds())) {
                throw new IllegalArgumentException("Region overlaps " + existing.id());
            }
        }
    }

    private static void requireInside(Region region, double x, double y, double z) {
        if (!region.bounds().contains(x, y, z)) {
            throw new IllegalArgumentException("City location is outside region " + region.id());
        }
    }

    private WorldHeight requireLoadedWorld(String world) {
        return worldHeights.apply(world)
                .orElseThrow(() -> new IllegalStateException("World is not loaded: " + world));
    }

    private void requireValidBounds(String world, Bounds bounds) {
        WorldHeight height = requireLoadedWorld(world);
        if (bounds.minY() < height.min() || bounds.maxY() >= height.maxExclusive())
            throw new IllegalArgumentException("Region exceeds world height: " + world);
    }

    private void save(Region next, int previousVersion) throws SQLException {
        if (!store.update(next, previousVersion)) throw new IllegalStateException("Region changed concurrently");
    }

    private void withRegion(Region value) {
        snapshot = Stream.concat(snapshot.stream().filter(existing -> !existing.id().equals(value.id())),
                Stream.of(value)).sorted(java.util.Comparator.comparing(Region::id)).toList();
    }
}
