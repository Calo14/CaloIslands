package me.calo.islands.domain;

import java.util.ArrayList;
import java.util.List;

/** Twelve cuboid edges sampled at a bounded density; never visits the volume. */
public final class PreviewGeometry {
    private PreviewGeometry() { }

    public record Point(double x, double y, double z) { }
    public record Edge(Point start, Point end) { }

    public static List<Edge> edges(Bounds bounds) {
        double x0 = bounds.minX(), x1 = (double) bounds.maxX() + 1;
        double y0 = bounds.minY(), y1 = (double) bounds.maxY() + 1;
        double z0 = bounds.minZ(), z1 = (double) bounds.maxZ() + 1;
        List<Edge> result = new ArrayList<>(12);
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) {
                result.add(new Edge(new Point(x0, y, z), new Point(x1, y, z)));
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) {
                result.add(new Edge(new Point(x, y0, z), new Point(x, y1, z)));
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) {
                result.add(new Edge(new Point(x, y, z0), new Point(x, y, z1)));
            }
        }
        return List.copyOf(result);
    }

    public static List<Point> sampledEdges(Bounds bounds, int maxParticlesPerFrame) {
        if (maxParticlesPerFrame < 30) throw new IllegalArgumentException("Particle budget must be at least 30");
        int samplesPerEdge = (maxParticlesPerFrame - 6) / 12;
        List<Point> samples = new ArrayList<>(samplesPerEdge * 12);
        for (Edge edge : edges(bounds)) {
            for (int i = 0; i < samplesPerEdge; i++) {
                double t = (double) i / (samplesPerEdge - 1);
                samples.add(new Point(
                        edge.start().x() + (edge.end().x() - edge.start().x()) * t,
                        edge.start().y() + (edge.end().y() - edge.start().y()) * t,
                        edge.start().z() + (edge.end().z() - edge.start().z()) * t));
            }
        }
        return List.copyOf(samples);
    }
}
