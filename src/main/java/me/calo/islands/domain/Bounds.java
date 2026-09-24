package me.calo.islands.domain;

public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Minimum coordinates must not exceed maximum coordinates");
        }
    }

    public static Bounds between(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Bounds(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x < (double) maxX + 1 && y >= minY && y < (double) maxY + 1
                && z >= minZ && z < (double) maxZ + 1;
    }

    public boolean overlaps(Bounds other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY
                && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
