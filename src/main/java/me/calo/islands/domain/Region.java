package me.calo.islands.domain;

public record Region(String id, String world, Bounds bounds, boolean active, int version) {
    public Region {
        Ids.require(id);
        if (world == null || world.isBlank() || world.length() > 128) {
            throw new IllegalArgumentException("World name is required");
        }
        if (bounds == null || version < 1) {
            throw new IllegalArgumentException("Invalid region bounds or version");
        }
    }
}
