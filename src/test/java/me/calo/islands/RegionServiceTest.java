package me.calo.islands;

import me.calo.islands.data.RegionRepository;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.City;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class RegionServiceTest {
    @Test void arrivalPointIsIndependentDurableAndPreservedByRegionEdits() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        RegionService service = new RegionService(repository,w -> Optional.of(new RegionService.WorldHeight(-64,320)));
        Bounds bounds = new Bounds(0,-64,0,10,319,10);
        service.createRegion("point_region","test_world",bounds);
        assertNull(service.region("point_region").orElseThrow().destination());
        var point = new me.calo.islands.domain.Destination("test_world", 50.25,64,50.75,123,-32);
        // Arrival points are not geometry or corners; administrators may choose an entrance outside.
        Region saved=service.setRegionPoint("point_region",point);
        assertEquals(bounds,saved.bounds()); assertEquals(2,saved.version());
        assertEquals(saved,service.setRegionPoint("point_region",point));
        RegionService restarted=new RegionService(repository,w -> Optional.of(new RegionService.WorldHeight(-64,320)));
        assertEquals(point,restarted.region("point_region").orElseThrow().destination());
        assertEquals(point,restarted.setActive("point_region",true).destination());
        restarted.setActive("point_region",false);
        assertEquals(point,restarted.resizeRegion("point_region",new Bounds(0,-64,0,20,319,20)).destination());
        restarted.setRegionPoint("point_region",null);
        assertNull(new RegionService(repository,w -> Optional.of(new RegionService.WorldHeight(-64,320)))
                .region("point_region").orElseThrow().destination());
        assertThrows(IllegalArgumentException.class,()->restarted.setRegionPoint("point_region",
                new me.calo.islands.domain.Destination("another_world",0,64,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->restarted.setRegionPoint("point_region",
                new me.calo.islands.domain.Destination("test_world",0,320,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->new me.calo.islands.domain.Destination("test_world",Double.NaN,64,0,0,0));
    }
    @Test void cityOrientationSurvivesReloadAndCoordinateOnlyCompatibilityCalls() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        RegionService service=new RegionService(repository,w -> Optional.of(new RegionService.WorldHeight(-64,320)));
        service.createRegion("city_region","test_world",new Bounds(0,0,0,20,100,20));
        service.createCity("arrival_city","city_region",5.5,64,5.5,90,-20);
        service.moveCity("arrival_city","city_region",6.5,64,6.5);
        City loaded=new RegionService(repository,w -> Optional.of(new RegionService.WorldHeight(-64,320))).city("arrival_city").orElseThrow();
        assertEquals(90,loaded.destination().yaw()); assertEquals(-20,loaded.destination().pitch());
    }

    @Test
    void committedChangesImmediatelyUpdateEntrySnapshot() throws SQLException {
        MemoryRepository repository = new MemoryRepository();
        RegionService service = new RegionService(repository, world -> Optional.of(
                new RegionService.WorldHeight(-40, 200)));
        Bounds bounds = Bounds.between(0, 60, 0, 10, 80, 10);
        service.createRegion("test_region", "test_world", bounds);
        assertTrue(service.at("test_world", 5, 70, 5).isPresent());
        assertFalse(service.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
        assertEquals(1, service.createRegion("test_region", "test_world", bounds).version());
        assertEquals(1, service.regions().size());
        service.createCity("test_city", "test_region", 5, 70, 5);
        assertEquals("test_world", service.city("test_city").orElseThrow().world());
        assertEquals(1, service.createCity("test_city", "test_region", 5, 70, 5).version());
        assertEquals(1, service.moveCity("test_city", "test_region", 5, 70, 5).version());
        assertEquals(2, service.moveCity("test_city", "test_region", 6, 70, 6).version());
        assertEquals(1, service.resizeRegion("test_region", bounds).version());
        assertThrows(IllegalArgumentException.class,
                () -> service.resizeRegion("test_region", Bounds.between(0, 60, 0, 4, 80, 4)));
        assertEquals(2, service.setActive("test_region", true).version());
        assertEquals(2, service.setActive("test_region", true).version());
        assertEquals(2, service.resizeRegion("test_region", bounds).version());
        assertTrue(service.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
        assertThrows(IllegalStateException.class,
                () -> service.resizeRegion("test_region", Bounds.between(0, 60, 0, 20, 80, 20)));
        service.setActive("test_region", false);
        assertFalse(service.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
        service.deleteCity("test_city");
        service.deleteRegion("test_region");
        assertTrue(service.at("test_world", 5, 70, 5).isEmpty());
    }

    @Test
    void rejectsOverlapAndInvalidCityLocationBeforeStorage() throws SQLException {
        MemoryRepository repository = new MemoryRepository();
        RegionService service = new RegionService(repository, world -> Optional.of(
                new RegionService.WorldHeight(-40, 200)));
        service.createRegion("first_region", "test_world", Bounds.between(0, 60, 0, 10, 80, 10));
        assertThrows(IllegalArgumentException.class, () -> service.createRegion(
                "overlap_region", "test_world", Bounds.between(10, 70, 10, 20, 90, 20)));
        assertTrue(service.region("overlap_region").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.createCity(
                "outside_city", "first_region", 11, 70, 5));
        assertThrows(IllegalArgumentException.class, () -> service.createCity(
                "invalid_city", "first_region", Double.NaN, 70, 5));
        assertTrue(service.cities().isEmpty());
        service.createRegion("other_world", "another_world", Bounds.between(0, 60, 0, 10, 80, 10));
        assertThrows(IllegalArgumentException.class, () -> service.createRegion(
                "too_low", "test_world", new Bounds(30, -41, 30, 40, 80, 40)));
        assertThrows(IllegalArgumentException.class, () -> service.createRegion(
                "too_high", "test_world", new Bounds(30, 60, 30, 40, 200, 40)));
        assertThrows(IllegalArgumentException.class, () -> service.resizeRegion(
                "first_region", new Bounds(0, 60, 0, 10, 200, 10)));
    }

    @Test
    void restartRestoresActiveStateAndRequiresLoadedWorldForEntry() throws SQLException {
        MemoryRepository repository = new MemoryRepository();
        RegionService first = new RegionService(repository, world -> Optional.of(
                new RegionService.WorldHeight(-40, 200)));
        first.createRegion("test_region", "test_world", Bounds.between(0, 60, 0, 10, 80, 10));
        first.createCity("saved_city", "test_region", 5.5, 70, 6.5);
        first.setActive("test_region", true);
        RegionService restarted = new RegionService(repository, world -> Optional.empty());
        assertEquals("test_world", restarted.city("saved_city").orElseThrow().world());
        assertEquals(6.5, restarted.city("saved_city").orElseThrow().z());
        assertTrue(restarted.region("test_region").orElseThrow().active());
        assertFalse(restarted.operational(restarted.region("test_region").orElseThrow()));
        assertFalse(restarted.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
        assertEquals(2, restarted.setActive("test_region", true).version());
        assertThrows(IllegalStateException.class, () -> restarted.createCity(
                "new_city", "test_region", 5, 70, 5));
        assertThrows(IllegalStateException.class, () -> restarted.createRegion(
                "new_region", "test_world", Bounds.between(20, 60, 20, 30, 80, 30)));
    }

    private static final class MemoryRepository implements RegionRepository {
        private final Map<String, Region> regions = new HashMap<>();
        private final Map<String, City> cities = new HashMap<>();
        @Override public List<Region> regions() { return new ArrayList<>(regions.values()); }
        @Override public Optional<Region> region(String id) { return Optional.ofNullable(regions.get(id)); }
        @Override public void insert(Region region) { regions.put(region.id(), region); }
        @Override public boolean update(Region region, int expectedVersion) {
            if (regions.get(region.id()).version() != expectedVersion) return false;
            regions.put(region.id(), region); return true;
        }
        @Override public boolean deleteRegion(String id, int expectedVersion) { return regions.remove(id) != null; }
        @Override public List<City> cities() { return new ArrayList<>(cities.values()); }
        @Override public Optional<City> city(String id) { return Optional.ofNullable(cities.get(id)); }
        @Override public void insert(City city) { cities.put(city.id(), city); }
        @Override public boolean update(City city, int expectedVersion) {
            if (cities.get(city.id()).version() != expectedVersion) return false;
            cities.put(city.id(), city); return true;
        }
        @Override public boolean deleteCity(String id, int expectedVersion) { return cities.remove(id) != null; }
    }
}
