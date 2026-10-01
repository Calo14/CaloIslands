package me.calo.islands;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.PreviewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PreviewGeometryTest {
    @Test void volumeIncludesCornersSixFacesAndGridWithoutExceedingBudget() {
        for(Bounds b:java.util.List.of(new Bounds(0,60,0,10,80,10),new Bounds(-1000000,-64,-1000000,1000000,319,1000000))) {
            for(int budget:new int[]{96,192,960}) {
                var points=PreviewGeometry.sampledVolume(b,budget,2,16);
                assertTrue(points.size()<=budget);
                for(double x:new double[]{b.minX(),(double)b.maxX()+1})
                    for(double y:new double[]{b.minY(),(double)b.maxY()+1})
                        for(double z:new double[]{b.minZ(),(double)b.maxZ()+1})
                            assertTrue(points.contains(new PreviewGeometry.Point(x,y,z)));
                assertTrue(points.stream().allMatch(p -> p.x()==b.minX() || p.x()==(double)b.maxX()+1
                        || p.y()==b.minY() || p.y()==(double)b.maxY()+1 || p.z()==b.minZ() || p.z()==(double)b.maxZ()+1));
            }
        }
        var small=PreviewGeometry.sampledVolume(new Bounds(0,0,0,10,10,10),960,2,16);
        assertTrue(small.stream().anyMatch(p -> p.y()==11 && p.x()>0 && p.x()<11 && p.z()>0 && p.z()<11));
        assertTrue(small.stream().anyMatch(p -> p.y()==0 && p.x()>0 && p.x()<11 && p.z()>0 && p.z()<11));
    }

    @Test
    void samplesOnlyTwelveEdgesWithinFixedBudgetEvenForHugeRegion() {
        Bounds bounds = new Bounds(-1_000_000, -64, -1_000_000,
                1_000_000, 319, 1_000_000);
        assertEquals(12, PreviewGeometry.edges(bounds).size());
        var points = PreviewGeometry.sampledEdges(bounds, 192);
        assertEquals(180, points.size());
        assertTrue(points.size() + 6 <= 192);
        double minX = bounds.minX(), maxX = (double) bounds.maxX() + 1;
        double minY = bounds.minY(), maxY = (double) bounds.maxY() + 1;
        double minZ = bounds.minZ(), maxZ = (double) bounds.maxZ() + 1;
        assertTrue(points.stream().allMatch(point ->
                (point.x() == minX || point.x() == maxX ? 1 : 0)
                        + (point.y() == minY || point.y() == maxY ? 1 : 0)
                        + (point.z() == minZ || point.z() == maxZ ? 1 : 0) >= 2));
        assertEquals(24, PreviewGeometry.sampledEdges(bounds, 30).size());
    }
}
