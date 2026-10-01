package me.calo.islands.domain;
/** Immutable arrival point, independent of geometry. */
public record Destination(String world, double x, double y, double z, float yaw, float pitch) {
    public Destination {
        if (world == null || world.isBlank() || world.length() > 128 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90)
            throw new IllegalArgumentException("Destino inválido.");
    }
}
