package me.calo.islands;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.calo.islands.data.RegionStore;
import me.calo.islands.data.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
final class CityWorldMigrationMariaDbTest {
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("caloislands_city_migration_test");

    @Test
    void versionOneCityGetsParentWorldWithoutLosingCoordinates() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB.getJdbcUrl());
        config.setUsername(DB.getUsername());
        config.setPassword(DB.getPassword());
        try (HikariDataSource source = new HikariDataSource(config);
             Connection connection = source.getConnection();
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE calo_schema_version (id TINYINT PRIMARY KEY, version INT NOT NULL) ENGINE=InnoDB");
            sql.executeUpdate("INSERT INTO calo_schema_version VALUES (1,1)");
            sql.executeUpdate("""
                    CREATE TABLE calo_regions (
                      id VARCHAR(64) PRIMARY KEY, world_name VARCHAR(128) NOT NULL,
                      min_x INT NOT NULL,min_y INT NOT NULL,min_z INT NOT NULL,
                      max_x INT NOT NULL,max_y INT NOT NULL,max_z INT NOT NULL,
                      active BOOLEAN NOT NULL,version BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    """);
            sql.executeUpdate("""
                    CREATE TABLE calo_cities (
                      id VARCHAR(64) PRIMARY KEY,region_id VARCHAR(64) NOT NULL,
                      x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,version BIGINT NOT NULL,
                      CONSTRAINT fk_calo_city_region FOREIGN KEY (region_id)
                        REFERENCES calo_regions(id) ON DELETE RESTRICT
                    ) ENGINE=InnoDB
                    """);
            sql.executeUpdate("""
                    INSERT INTO calo_regions VALUES
                    ('region_v1','test_world',0,0,0,10,100,10,1,3)
                    """);
            sql.executeUpdate("INSERT INTO calo_cities VALUES ('city_v1','region_v1',5.5,70,6.5,2)");
            // Simulate an interrupted V2 migration after its first implicit DDL commit.
            sql.executeUpdate("ALTER TABLE calo_cities ADD COLUMN world_name VARCHAR(128) NULL");
            new SchemaMigrator(source).migrate();
            new SchemaMigrator(source).migrate();
            var city = new RegionStore(source).city("city_v1").orElseThrow();
            assertEquals("test_world", city.world());
            assertEquals(5.5, city.x());
            assertEquals(70, city.y());
            assertEquals(6.5, city.z());
            assertEquals(2, city.version());
            assertThrows(java.sql.SQLException.class, () -> sql.executeUpdate(
                    "UPDATE calo_cities SET world_name='other_world' WHERE id='city_v1'"));
            try (ResultSet version = sql.executeQuery("SELECT version FROM calo_schema_version WHERE id=1")) {
                assertTrue(version.next());
                assertEquals(2, version.getInt(1));
            }
        }
    }
}
