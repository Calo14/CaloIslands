package me.calo.islands.domain;

public record City(String id, String regionId, String world, double x, double y, double z, int version) {
    public City {
        Ids.require(id);
        Ids.require(regionId);
        if (world == null || world.isBlank()) throw new IllegalArgumentException("City world is required");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || version < 1) {
            throw new IllegalArgumentException("Invalid city location or version");
        }
    }
}
