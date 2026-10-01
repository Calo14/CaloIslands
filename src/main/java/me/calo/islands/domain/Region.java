package me.calo.islands.domain;

public record Region(String id, String world, Bounds bounds, boolean active, int version, Destination destination) {
    public Region(String id, String world, Bounds bounds, boolean active, int version) {
        this(id, world, bounds, active, version, null);
    }
    public Region {
        Ids.require(id);
        if (world == null || world.isBlank() || world.length() > 128) {
            throw new IllegalArgumentException("World name is required");
        }
        if (bounds == null || version < 1) {
            throw new IllegalArgumentException("Invalid region bounds or version");
        }
        if (destination != null && !world.equals(destination.world()))
            throw new IllegalArgumentException("El mundo del punto de llegada no coincide con la región.");
    }
}
