package me.calo.islands.domain;

import java.util.ArrayList;
import java.util.List;

/** Cuboid edges and face grids sampled at bounded density; never visits the volume. */
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

    /** Surface grid capped at four internal divisions per axis, with globally uniform spacing. */
    public static List<Point> sampledVolume(Bounds b, int budget, double pointSpacing, double gridSpacing) {
        if (budget < 96 || !Double.isFinite(pointSpacing) || pointSpacing <= 0 || !Double.isFinite(gridSpacing) || gridSpacing <= 0)
            throw new IllegalArgumentException("Invalid volume budget or spacing");
        List<Edge> lines = new ArrayList<>(edges(b));
        double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {(double)b.maxX()+1,(double)b.maxY()+1,(double)b.maxZ()+1};
        for (int axis=0; axis<3; axis++) {
            int divisions = (int)Math.max(2, Math.min(5, Math.ceil((hi[axis]-lo[axis])/gridSpacing)));
            for (int i=1; i<divisions; i++) {
                double value = lo[axis]+(hi[axis]-lo[axis])*i/divisions;
                int a=(axis+1)%3, c=(axis+2)%3;
                for (double side : new double[]{lo[c],hi[c]}) {
                    double[] start=lo.clone(), end=lo.clone(); start[axis]=end[axis]=value; start[c]=end[c]=side; end[a]=hi[a];
                    lines.add(new Edge(new Point(start[0],start[1],start[2]),new Point(end[0],end[1],end[2])));
                }
                for (double side : new double[]{lo[a],hi[a]}) {
                    double[] start=lo.clone(), end=lo.clone(); start[axis]=end[axis]=value; start[a]=end[a]=side; end[c]=hi[c];
                    lines.add(new Edge(new Point(start[0],start[1],start[2]),new Point(end[0],end[1],end[2])));
                }
            }
        }
        while (lines.size()*2 > budget) lines.remove(lines.size()-1);
        // Reserve all endpoints; coarsen sampling until the strict packet budget fits.
        double spacing=pointSpacing;
        while (count(lines,spacing)>budget) spacing*=1.25;
        java.util.Set<Point> points=new java.util.LinkedHashSet<>();
        for (Edge line:lines) {
            int n=Math.max(1,(int)Math.ceil(length(line)/spacing));
            for(int i=0;i<=n;i++) {
                double t=(double)i/n;
                points.add(new Point(line.start.x+(line.end.x-line.start.x)*t,
                        line.start.y+(line.end.y-line.start.y)*t,line.start.z+(line.end.z-line.start.z)*t));
            }
        }
        return List.copyOf(points);
    }
    private static double length(Edge e) {
        return Math.abs(e.end.x-e.start.x)+Math.abs(e.end.y-e.start.y)+Math.abs(e.end.z-e.start.z);
    }
    private static long count(List<Edge> lines, double spacing) {
        long count=0;
        for(Edge e:lines) count+=(long)Math.ceil(length(e)/spacing)+1;
        return count;
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
