package me.calo.islands.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Temporary per-player block selection; no selection data is persisted. */
public final class RegionSelectionService {
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Set<UUID> previewDisabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();

    public enum Mode { FULLHEIGHT, EXACT }

    public Mode mode(UUID player) { return modes.getOrDefault(player, Mode.FULLHEIGHT); }

    public void setMode(UUID player, Mode mode) { modes.put(player, mode); }

    public Selection setFirst(UUID player, String world, int x, int y, int z) {
        return selections.compute(player, (id, previous) ->
                new Selection(new Position(world, x, y, z), previous == null ? null : previous.second()));
    }

    public Selection setSecond(UUID player, String world, int x, int y, int z) {
        return selections.compute(player, (id, previous) ->
                new Selection(previous == null ? null : previous.first(), new Position(world, x, y, z)));
    }

    public Optional<Selection> get(UUID player) { return Optional.ofNullable(selections.get(player)); }

    public void clear(UUID player) {
        selections.remove(player);
        previewDisabled.remove(player);
        modes.remove(player);
    }

    public boolean previewEnabled(UUID player) { return !previewDisabled.contains(player); }

    public boolean togglePreview(UUID player) {
        if (previewDisabled.remove(player)) return true;
        previewDisabled.add(player);
        return false;
    }

    public record Position(String world, int x, int y, int z) {
        public Position {
            if (world == null || world.isBlank()) throw new IllegalArgumentException("World is required");
        }

        @Override public String toString() { return x + "," + y + "," + z; }
    }

    public record Selection(Position first, Position second) {
        public boolean complete() { return first != null && second != null; }

        public boolean sameWorld() { return complete() && first.world().equals(second.world()); }

        public Bounds bounds() {
            if (!complete()) throw new IllegalStateException("Selection needs two positions");
            if (!sameWorld()) throw new IllegalStateException("Selection positions are in different worlds");
            return Bounds.between(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
        }

        public Bounds effectiveBounds(Mode mode, int minHeight, int maxHeightExclusive) {
            Bounds selected = bounds();
            if (minHeight >= maxHeightExclusive) throw new IllegalArgumentException("Invalid world height range");
            if (mode == Mode.EXACT) return selected;
            return new Bounds(selected.minX(), minHeight, selected.minZ(),
                    selected.maxX(), maxHeightExclusive - 1, selected.maxZ());
        }

        public String world() {
            if (!sameWorld()) throw new IllegalStateException("Selection positions are in different worlds");
            return first.world();
        }

        public String dimensions() {
            return dimensions(bounds());
        }

        public String dimensions(Bounds bounds) {
            return ((long) bounds.maxX() - bounds.minX() + 1) + "x"
                    + ((long) bounds.maxY() - bounds.minY() + 1) + "x"
                    + ((long) bounds.maxZ() - bounds.minZ() + 1);
        }
    }
}
