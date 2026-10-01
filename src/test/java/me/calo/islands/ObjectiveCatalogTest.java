package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.data.ObjectiveDefinitionStore;
import me.calo.islands.data.SchemaMigrator;
import me.calo.islands.domain.*;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class ObjectiveCatalogTest {
    private final Destination start = new Destination("world", 5, 64, 5, 0, 0);
    private final Region region = new Region("coast", "world", new Bounds(0, 0, 0, 20, 100, 20), true, 1, start);
    private ObjectiveSettings settings(String name) {
        return new ObjectiveSettings("defense_a", name, ObjectiveSettings.Mode.DEFENSE, "coast",
                8, 5, 0, 2, 5, 120, null, null, List.of(), 0);
    }
    private JdbcDataSource database() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:objectives_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE calo_objective_definitions (id VARCHAR(64) PRIMARY KEY, region_id VARCHAR(64), payload TEXT NOT NULL, enabled BOOLEAN NOT NULL, deleted BOOLEAN NOT NULL, revision BIGINT NOT NULL)");
        }
        return source;
    }
    @Test void createEditEnableCancelDeleteAndRestartUseOnePersistedCatalog() throws Exception {
        var source = database(); var store = new ObjectiveDefinitionStore(source);
        RegionService regions = mock(RegionService.class);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        Player admin = mock(Player.class);
        when(admin.isOnline()).thenReturn(true); when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        AtomicBoolean access = new AtomicBoolean(true);
        var catalog = new ObjectiveCatalog(store, regions, (player, point) -> access.get(), Map.of());
        ObjectiveActivityService runtime = mock(ObjectiveActivityService.class);
        when(runtime.current("defense_a")).thenReturn(Optional.empty());
        catalog.attach(runtime);
        ManagedObjective created = catalog.create(admin, settings("Faro"), start);
        assertFalse(created.enabled()); assertEquals(1, created.revision());
        assertEquals("Faro", new ObjectiveCatalog(store, regions, (p,d) -> true, Map.of()).require("defense_a").settings().name());
        ManagedObjective enabled = catalog.setEnabled(admin, "defense_a", 1, true);
        verify(runtime).upsert(settings("Faro"), start);
        ManagedObjective edited = catalog.edit(admin, "defense_a", 2, settings("Defensa del faro"), start);
        assertEquals(3, edited.revision()); assertEquals("Defensa del faro", store.list().getFirst().settings().name());
        assertThrows(IllegalStateException.class, () -> catalog.edit(admin, "defense_a", 2, settings("Viejo"), start));
        ActivityRun running = mock(ActivityRun.class);
        when(runtime.current("defense_a")).thenReturn(Optional.of(running));
        assertThrows(IllegalStateException.class, () -> catalog.delete(admin, "defense_a", 3));
        assertThrows(IllegalStateException.class, () -> catalog.setEnabled(admin, "defense_a", 3, false));
        when(runtime.cancel(admin, "defense_a")).thenReturn(true);
        catalog.cancel(admin, "defense_a"); verify(runtime).cancel(admin, "defense_a");
        when(runtime.current("defense_a")).thenReturn(Optional.empty());
        catalog.delete(admin, "defense_a", 3);
        assertTrue(catalog.list().isEmpty()); verify(runtime, atLeastOnce()).remove("defense_a");
        var restarted = new ObjectiveCatalog(store, regions, (p,d) -> true,
                Map.of("defense_a", settings("Config anterior")));
        assertTrue(restarted.list().isEmpty(), "Tombstone keeps a deleted config objective deleted after restart");
        assertTrue(restarted.enabledSettings().isEmpty());
        ManagedObjective recreated = restarted.create(admin, settings("Nuevo faro"), start);
        assertEquals(5, recreated.revision());
    }
    @Test void invalidFieldsPermissionsAndExternalProtectionNeverReachStorage() throws Exception {
        var store = new ObjectiveDefinitionStore(database());
        RegionService regions = mock(RegionService.class);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        Player admin = mock(Player.class);
        when(admin.isOnline()).thenReturn(true); when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        AtomicBoolean access = new AtomicBoolean(false);
        var catalog = new ObjectiveCatalog(store, regions, (p,d) -> access.get(), Map.of(), Set.of("reserved"));
        assertThrows(IllegalArgumentException.class, () -> catalog.create(admin, settings("Faro"), start));
        assertTrue(store.list().isEmpty());
        access.set(true);
        when(admin.hasPermission("caloislands.admin")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> catalog.create(admin, settings("Faro"), start));
        when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> catalog.create(admin, settings("Faro"),
                new Destination("world", 25, 64, 5, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> catalog.create(admin,
                new ObjectiveSettings("reserved", "Reservada", ObjectiveSettings.Mode.DEFENSE, "coast",
                        8, 5, 0, 1, 5, 120, null, null, List.of(), 0), start));
        assertFalse(ObjectiveCatalog.ordinaryMob(org.bukkit.entity.EntityType.WITHER));
        assertTrue(ObjectiveCatalog.ordinaryMob(org.bukkit.entity.EntityType.ZOMBIE));
        assertTrue(store.list().isEmpty());
    }
    @Test void enabledDefinitionReloadsAndRegistersExistingRecoveryHandler() throws Exception {
        var store = new ObjectiveDefinitionStore(database());
        RegionService regions = mock(RegionService.class);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        Player admin = mock(Player.class);
        when(admin.isOnline()).thenReturn(true); when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        var first = new ObjectiveCatalog(store, regions, (p,d) -> true, Map.of());
        first.create(admin, settings("Faro"), start);
        first.setEnabled(admin, "defense_a", 1, true);
        var restarted = new ObjectiveCatalog(store, regions, (p,d) -> true, Map.of());
        assertEquals(start, restarted.enabledStarts().get("defense_a"));
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("CaloIslands"); when(plugin.namespace()).thenReturn("caloislands");
        Server server = mock(Server.class); when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L)))
                .thenReturn(mock(BukkitTask.class));
        var activities = mock(me.calo.islands.core.ActivityService.class);
        var objective = new ObjectiveActivityService(plugin, activities, regions,
                restarted.enabledSettings(), restarted.enabledStarts(), (p, action, location) -> true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            objective.start();
        }
        verify(activities).registerResumable(eq("defense_a"), any(), any());
        objective.stop();
    }
    @Test void v11MigrationIsRepeatableAfterAnInterruptedVersionUpdate() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:objective_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE calo_schema_version (id TINYINT PRIMARY KEY, version INT NOT NULL)");
            sql.execute("INSERT INTO calo_schema_version VALUES (1,10)");
            sql.execute("CREATE TABLE calo_regions (id VARCHAR(64) PRIMARY KEY)");
        }
        new SchemaMigrator(source).migrate();
        try (var connection=source.getConnection();var sql=connection.createStatement()) {
            try (var rows=sql.executeQuery("SELECT version FROM calo_schema_version WHERE id=1")) {
                assertTrue(rows.next());assertEquals(11,rows.getInt(1));
            }
            sql.execute("UPDATE calo_schema_version SET version=10 WHERE id=1");
        }
        new SchemaMigrator(source).migrate();
        try (var connection=source.getConnection();var sql=connection.createStatement();
             var rows=sql.executeQuery("SELECT COUNT(*) FROM calo_objective_definitions")) {
            assertTrue(rows.next());assertEquals(0,rows.getInt(1));
        }
    }
}
