package me.calo.islands.data;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Versioned migration of the dedicated CaloIslands schema. Never touches GoldenRPG tables. */
public final class SchemaMigrator {
    public static final int VERSION = 2;
    private final DataSource source;

    public SchemaMigrator(DataSource source) { this.source = source; }

    public void migrate() throws SQLException {
        try (Connection connection = source.getConnection(); Statement ddl = connection.createStatement()) {
            ddl.executeUpdate("""
                CREATE TABLE IF NOT EXISTS calo_schema_version (
                  id TINYINT NOT NULL PRIMARY KEY,
                  version INT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            int version = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT version FROM calo_schema_version WHERE id = 1"); ResultSet rows = query.executeQuery()) {
                if (rows.next()) version = rows.getInt(1);
            }
            if (version > VERSION) throw new SQLException("Unsupported CaloIslands schema version " + version);
            if (version == VERSION) return;
            if (version < 1) {
            // MySQL DDL commits implicitly. Each statement is repeatable; the version is advanced last.
            ddl.executeUpdate("""
                CREATE TABLE IF NOT EXISTS calo_world_locks (
                  world_name VARCHAR(128) NOT NULL PRIMARY KEY
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            ddl.executeUpdate("""
                CREATE TABLE IF NOT EXISTS calo_regions (
                  id VARCHAR(64) NOT NULL PRIMARY KEY,
                  world_name VARCHAR(128) NOT NULL,
                  min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL,
                  max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL,
                  active BOOLEAN NOT NULL DEFAULT FALSE,
                  version BIGINT NOT NULL DEFAULT 1,
                  CONSTRAINT chk_calo_bounds CHECK (min_x<=max_x AND min_y<=max_y AND min_z<=max_z),
                  CONSTRAINT chk_calo_region_active CHECK (active IN (0,1)),
                  CONSTRAINT chk_calo_region_version CHECK (version>=1),
                  INDEX idx_calo_regions_world (world_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            ddl.executeUpdate("""
                CREATE TABLE IF NOT EXISTS calo_cities (
                  id VARCHAR(64) NOT NULL PRIMARY KEY,
                  region_id VARCHAR(64) NOT NULL,
                  x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
                  version BIGINT NOT NULL DEFAULT 1,
                  CONSTRAINT chk_calo_city_version CHECK (version>=1),
                  INDEX idx_calo_cities_region (region_id),
                  CONSTRAINT fk_calo_city_region FOREIGN KEY (region_id)
                    REFERENCES calo_regions(id) ON DELETE RESTRICT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            try (PreparedStatement update = connection.prepareStatement("""
                    INSERT INTO calo_schema_version (id, version) VALUES (1, ?)
                    ON DUPLICATE KEY UPDATE version = VALUES(version)
                    """)) {
                update.setInt(1, 1);
                update.executeUpdate();
            }
            }
            if (version < 2) migrateCityWorld(connection, ddl);
        }
    }

    private static void migrateCityWorld(Connection connection, Statement ddl) throws SQLException {
        // Existing V1 rows inherit the immutable world of their parent region.
        // DDL commits implicitly, so every step is safe to repeat after interruption.
        if (!columnExists(connection, "calo_cities", "world_name"))
            ddl.executeUpdate("ALTER TABLE calo_cities ADD COLUMN world_name VARCHAR(128) NULL");
        ddl.executeUpdate("""
                UPDATE calo_cities c JOIN calo_regions r ON r.id=c.region_id
                SET c.world_name=r.world_name WHERE c.world_name IS NULL
                """);
        if (columnNullable(connection, "calo_cities", "world_name"))
            ddl.executeUpdate("ALTER TABLE calo_cities MODIFY world_name VARCHAR(128) NOT NULL");
        if (!indexExists(connection, "calo_regions", "uq_calo_region_world"))
            ddl.executeUpdate("ALTER TABLE calo_regions ADD UNIQUE KEY uq_calo_region_world (id,world_name)");
        if (!indexExists(connection, "calo_cities", "idx_calo_city_region_world"))
            ddl.executeUpdate("ALTER TABLE calo_cities ADD INDEX idx_calo_city_region_world (region_id,world_name)");
        if (!foreignKeyExists(connection, "calo_cities", "fk_calo_city_region_world"))
            ddl.executeUpdate("""
                    ALTER TABLE calo_cities ADD CONSTRAINT fk_calo_city_region_world
                    FOREIGN KEY (region_id,world_name) REFERENCES calo_regions(id,world_name)
                    ON DELETE RESTRICT
                    """);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE calo_schema_version SET version=2 WHERE id=1")) {
            if (update.executeUpdate() != 1) throw new SQLException("CaloIslands schema version row missing");
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return rows.next();
        }
    }

    private static boolean columnNullable(Connection connection, String table, String column) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            if (!rows.next()) throw new SQLException("Migration column missing: " + table + "." + column);
            return rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        }
    }

    private static boolean indexExists(Connection connection, String table, String name) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (rows.next()) if (name.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    private static boolean foreignKeyExists(Connection connection, String table, String name) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
            while (rows.next()) if (name.equalsIgnoreCase(rows.getString("FK_NAME"))) return true;
            return false;
        }
    }
}
