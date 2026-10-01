package me.calo.islands;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.calo.islands.data.*;
import me.calo.islands.domain.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
@Testcontainers(disabledWithoutDocker=true)
final class RegionPointMigrationMariaDbTest {
    @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4").withDatabaseName("calo_point_migration_test");
    @Test void interruptedV3MigrationPreservesV2RowsAndPersistsPointsAndOrientation() throws Exception {
        HikariConfig config=new HikariConfig();config.setJdbcUrl(DB.getJdbcUrl());config.setUsername(DB.getUsername());config.setPassword(DB.getPassword());
        try(HikariDataSource source=new HikariDataSource(config);var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE calo_schema_version(id TINYINT PRIMARY KEY,version INT NOT NULL) ENGINE=InnoDB");
            sql.executeUpdate("INSERT INTO calo_schema_version VALUES(1,2)");
            sql.executeUpdate("CREATE TABLE calo_world_locks(world_name VARCHAR(128) PRIMARY KEY) ENGINE=InnoDB");
            sql.executeUpdate("CREATE TABLE calo_regions(id VARCHAR(64) PRIMARY KEY,world_name VARCHAR(128) NOT NULL,min_x INT,min_y INT,min_z INT,max_x INT,max_y INT,max_z INT,active BOOLEAN,version INT) ENGINE=InnoDB");
            sql.executeUpdate("CREATE TABLE calo_cities(id VARCHAR(64) PRIMARY KEY,region_id VARCHAR(64),world_name VARCHAR(128),x DOUBLE,y DOUBLE,z DOUBLE,version INT) ENGINE=InnoDB");
            sql.executeUpdate("INSERT INTO calo_regions VALUES('old_region','test_world',0,-64,0,20,319,20,1,7)");
            sql.executeUpdate("INSERT INTO calo_cities VALUES('old_city','old_region','test_world',5.5,64,6.5,4)");
            sql.executeUpdate("ALTER TABLE calo_regions ADD COLUMN point_world VARCHAR(128) NULL");
            new SchemaMigrator(source).migrate();new SchemaMigrator(source).migrate();
            RegionStore store=new RegionStore(source);assertEquals(8,store.schemaVersion());
            Region old=store.region("old_region").orElseThrow();assertNull(old.destination());assertTrue(old.active());assertEquals(7,old.version());
            assertEquals(new Bounds(0,-64,0,20,319,20),old.bounds());
            City city=store.city("old_city").orElseThrow();assertEquals(4,city.version());assertEquals(5.5,city.x());assertEquals(0,city.yaw());
            RegionService service=new RegionService(store,w -> Optional.of(new RegionService.WorldHeight(-64,320)));
            Destination point=new Destination("test_world",6.25,64,7.75,135,-25);service.setRegionPoint("old_region",point);
            service.moveCity("old_city","old_region",5.5,64,6.5,72,-15);
            RegionService restarted=new RegionService(new RegionStore(source),w -> Optional.of(new RegionService.WorldHeight(-64,320)));
            assertEquals(point,restarted.region("old_region").orElseThrow().destination());
            assertEquals(72,restarted.city("old_city").orElseThrow().yaw());assertEquals(-15,restarted.city("old_city").orElseThrow().pitch());
            restarted.setRegionPoint("old_region",null);assertNull(new RegionStore(source).region("old_region").orElseThrow().destination());
            var definition=new me.calo.islands.content.ActivityDefinition("migration_event",me.calo.islands.content.ActivityDefinition.Kind.EVENT,
                    "old_region",point,true,java.util.Set.of("content.enter","content.member"));
            var run=new me.calo.islands.content.ActivityRun(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),definition,
                    me.calo.islands.content.ActivityRun.State.RUNNING,0,1);
            var activities=new ActivityStore(source);activities.insert(run);
            assertEquals(run,new ActivityStore(source).find(run.runId()).orElseThrow());
            var progressed=run.next(run.state(),12);assertTrue(activities.update(progressed,1));assertFalse(activities.update(progressed,1));
            assertEquals(progressed,new ActivityStore(source).running().getFirst());
            var cancelled=progressed.next(me.calo.islands.content.ActivityRun.State.CANCELLED,12);assertTrue(activities.update(cancelled,2));
            assertTrue(new ActivityStore(source).running().isEmpty());assertEquals(cancelled,new ActivityStore(source).find(run.runId()).orElseThrow());
        }
    }
}
