package me.calo.islands;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.PreviewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PreviewGeometryTest {
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
