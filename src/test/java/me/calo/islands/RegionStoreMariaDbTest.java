package me.calo.islands;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.calo.islands.data.RegionStore;
import me.calo.islands.data.SchemaMigrator;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.RegionService;
import me.calo.islands.domain.RegionSelectionService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
final class RegionStoreMariaDbTest {
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("caloislands_test");

    @Test
    void migrationRegistrationRecoveryAndProtection() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB.getJdbcUrl());
        config.setUsername(DB.getUsername());
        config.setPassword(DB.getPassword());
        config.setMaximumPoolSize(3);
        try (HikariDataSource source = new HikariDataSource(config)) {
            new SchemaMigrator(source).migrate();
            new SchemaMigrator(source).migrate();
            RegionStore store = new RegionStore(source);
            Set<String> loaded = new HashSet<>(Set.of("test_world"));
            RegionService service = new RegionService(store, world -> loaded.contains(world)
                    ? Optional.of(new RegionService.WorldHeight(-40, 200)) : Optional.empty());
            RegionSelectionService selections = new RegionSelectionService();
            UUID admin = UUID.randomUUID();
            selections.setFirst(admin, "test_world", 10, 80, 10);
            selections.setSecond(admin, "test_world", 0, 60, 0);
            Bounds bounds = selections.get(admin).orElseThrow().bounds();
            assertEquals(service.createRegion("test_region", "test_world", bounds),
                    service.createRegion("test_region", "test_world", bounds));
            selections.clear(admin);
            assertTrue(selections.get(admin).isEmpty());
            assertFalse(service.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
            assertThrows(IllegalArgumentException.class, () -> service.createRegion("overlap", "test_world",
                    Bounds.between(5, 65, 5, 15, 75, 15)));
            service.createCity("test_city", "test_region", 5.5, 70, 5.5);
            assertThrows(IllegalArgumentException.class, () -> service.createCity("outside", "test_region", 30, 70, 30));
            assertTrue(service.setActive("test_region", true).active());
            assertThrows(IllegalStateException.class,
                    () -> service.resizeRegion("test_region", Bounds.between(0, 60, 0, 15, 80, 15)));

            var point = new me.calo.islands.domain.Destination("test_world", 5.5, 70, 5.5, 72, -15);
            assertNull(service.region("test_region").orElseThrow().destination());
            service.setRegionPoint("test_region", point);
            RegionService restarted = new RegionService(new RegionStore(source), world -> loaded.contains(world)
                    ? Optional.of(new RegionService.WorldHeight(-40, 200)) : Optional.empty());
            assertTrue(restarted.region("test_region").orElseThrow().active());
            assertEquals("test_world", restarted.city("test_city").orElseThrow().world());
            assertEquals(5.5, restarted.city("test_city").orElseThrow().x());
            assertEquals(point, restarted.region("test_region").orElseThrow().destination());
            restarted.setRegionPoint("test_region", null);
            assertNull(new RegionService(new RegionStore(source), w -> Optional.of(new RegionService.WorldHeight(-40,200)))
                    .region("test_region").orElseThrow().destination());
            loaded.clear();
            assertFalse(restarted.operational(restarted.region("test_region").orElseThrow()));
            loaded.add("test_world");
            assertTrue(restarted.mayEnter("test_world", 20, 70, 20, "test_world", 5, 70, 5));
            restarted.setActive("test_region", false);
            assertThrows(IllegalArgumentException.class,
                    () -> restarted.resizeRegion("test_region", Bounds.between(0, 60, 0, 4, 80, 4)));
            restarted.resizeRegion("test_region", Bounds.between(0, 60, 0, 15, 80, 15));
            restarted.moveCity("test_city", "test_region", 6, 70, 6);
            assertEquals(2, restarted.city("test_city").orElseThrow().version());
            assertEquals("test_world", restarted.city("test_city").orElseThrow().world());
            assertThrows(IllegalStateException.class, () -> restarted.deleteRegion("test_region"));
            restarted.deleteCity("test_city");
            restarted.deleteRegion("test_region");
            assertTrue(restarted.regions().isEmpty());
        }
    }

    @Test
    void simultaneousRegistrationsCannotOverlapInOneWorld() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB.getJdbcUrl());
        config.setUsername(DB.getUsername());
        config.setPassword(DB.getPassword());
        config.setMaximumPoolSize(3);
        try (HikariDataSource source = new HikariDataSource(config);
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            new SchemaMigrator(source).migrate();
            CountDownLatch start = new CountDownLatch(1);
            RegionService first = new RegionService(new RegionStore(source), world -> Optional.of(
                    new RegionService.WorldHeight(-40, 200)));
            RegionService second = new RegionService(new RegionStore(source), world -> Optional.of(
                    new RegionService.WorldHeight(-40, 200)));
            Future<Boolean> a = workers.submit(() -> register(start, first, "concurrent_a"));
            Future<Boolean> b = workers.submit(() -> register(start, second, "concurrent_b"));
            start.countDown();
            assertNotEquals(a.get(), b.get());
            assertEquals(1, new RegionStore(source).regions().stream()
                    .filter(region -> region.world().equals("concurrent_world")).count());
        }
    }

    private static boolean register(CountDownLatch start, RegionService service, String id) throws Exception {
        start.await();
        try {
            service.createRegion(id, "concurrent_world", Bounds.between(0, 0, 0, 10, 10, 10));
            return true;
        } catch (IllegalArgumentException conflict) {
            return false;
        }
    }
}
