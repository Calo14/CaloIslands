package me.calo.islands.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Pure rule used by movement and teleport events without database access. */
public final class RegionAccessPolicy {
    private RegionAccessPolicy() {}

    public static Optional<Region> at(List<Region> regions, String world, double x, double y, double z) {
        return atAll(regions, world, x, y, z).stream().findFirst();
    }

    public static List<Region> atAll(List<Region> regions, String world, double x, double y, double z) {
        return regions.stream().filter(region -> region.world().equals(world)
                && region.bounds().contains(x, y, z)).toList();
    }

    public static boolean mayEnter(List<Region> regions, Predicate<String> worldLoaded,
                                   String fromWorld, double fromX, double fromY, double fromZ,
                                   String toWorld, double toX, double toY, double toZ) {
        return mayEnter(regions, worldLoaded, fromWorld, fromX, fromY, fromZ,
                toWorld, toX, toY, toZ, false);
    }

    public static boolean mayEnter(List<Region> regions, Predicate<String> worldLoaded,
                                   String fromWorld, double fromX, double fromY, double fromZ,
                                   String toWorld, double toX, double toY, double toZ,
                                   boolean denyActiveEntry) {
        List<Region> destinations = atAll(regions, toWorld, toX, toY, toZ);
        if (destinations.isEmpty()) return true;
        if (destinations.size() != 1) return false;
        Region region = destinations.getFirst();
        boolean alreadyInside = at(regions, fromWorld, fromX, fromY, fromZ)
                .map(origin -> origin.id().equals(region.id())).orElse(false);
        if (alreadyInside) return true;
        return region.active() && worldLoaded.test(region.world()) && !denyActiveEntry;
    }
}
