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
    public static final int VERSION = 11;
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
            if (version < 3) {
                for (String column : new String[]{"point_world VARCHAR(128)", "point_x DOUBLE", "point_y DOUBLE", "point_z DOUBLE", "point_yaw FLOAT", "point_pitch FLOAT"})
                    if (!columnExists(connection, "calo_regions", column.split(" ")[0]))
                        ddl.executeUpdate("ALTER TABLE calo_regions ADD COLUMN " + column + " NULL");
                for (String column : new String[]{"yaw", "pitch"})
                    if (!columnExists(connection, "calo_cities", column))
                        ddl.executeUpdate("ALTER TABLE calo_cities ADD COLUMN " + column + " FLOAT NOT NULL DEFAULT 0");
                ddl.executeUpdate("UPDATE calo_schema_version SET version=3 WHERE id=1");
            }
            if (version < 4) {
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_activity_runs (
                          run_id CHAR(36) NOT NULL PRIMARY KEY,
                          participant CHAR(36) NOT NULL,
                          content_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,
                          region_id VARCHAR(64) NOT NULL, entry_world VARCHAR(128) NOT NULL,
                          entry_x DOUBLE NOT NULL, entry_y DOUBLE NOT NULL, entry_z DOUBLE NOT NULL,
                          entry_yaw FLOAT NOT NULL, entry_pitch FLOAT NOT NULL,
                          active BOOLEAN NOT NULL, permissions TEXT NOT NULL,
                          state VARCHAR(16) NOT NULL, progress BIGINT NOT NULL, revision BIGINT NOT NULL,
                          CONSTRAINT chk_calo_activity_state CHECK (state IN ('RUNNING','COMPLETED','CANCELLED')),
                          CONSTRAINT chk_calo_activity_progress CHECK (progress>=0 AND revision>=1),
                          INDEX idx_calo_activity_state (state)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                // Historical checkpoints remain readable if the region is later removed.
                ddl.executeUpdate("UPDATE calo_schema_version SET version=4 WHERE id=1");
            }
            if (version < 5) {
                ddl.executeUpdate("ALTER TABLE calo_activity_runs DROP CONSTRAINT IF EXISTS chk_calo_activity_state");
                ddl.executeUpdate("""
                        ALTER TABLE calo_activity_runs ADD CONSTRAINT chk_calo_activity_state
                        CHECK (state IN ('RUNNING','COMPLETED','CANCELLED','FAILED'))
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=5 WHERE id=1");
            }
            if (version < 6) {
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_boss_fights (
                          fight_id CHAR(36) NOT NULL PRIMARY KEY,
                          entity_id CHAR(36) NOT NULL, world_id CHAR(36) NOT NULL,
                          boss_id VARCHAR(64) NOT NULL, mythic_mob_id VARCHAR(128) NOT NULL,
                          phase_rules TEXT NOT NULL, state VARCHAR(16) NOT NULL,
                          phase_index INT NOT NULL, health_fraction DOUBLE NOT NULL,
                          revision BIGINT NOT NULL,
                          UNIQUE KEY uk_calo_boss_entity (entity_id),
                          INDEX idx_calo_boss_state (state),
                          CONSTRAINT chk_calo_boss_state CHECK (state IN ('ACTIVE','COMPLETED','DESPAWNED','CANCELLED')),
                          CONSTRAINT chk_calo_boss_health CHECK (health_fraction>=0 AND health_fraction<=1)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_boss_participants (
                          fight_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          PRIMARY KEY (fight_id, player_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_boss_actions (
                          sequence BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                          action_id CHAR(36) NOT NULL, fight_id CHAR(36) NOT NULL,
                          player_id CHAR(36) NOT NULL, kind VARCHAR(32) NOT NULL,
                          amount DOUBLE NOT NULL, health_fraction DOUBLE NULL,
                          UNIQUE KEY uk_calo_boss_action (action_id),
                          INDEX idx_calo_boss_action_fight (fight_id, sequence)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_boss_contributions (
                          fight_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          kind VARCHAR(32) NOT NULL, amount DOUBLE NOT NULL,
                          PRIMARY KEY (fight_id, player_id, kind)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_boss_reward_intents (
                          request_id CHAR(36) NOT NULL PRIMARY KEY,
                          fight_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          status VARCHAR(24) NOT NULL DEFAULT 'WAITING_POLICY',
                          UNIQUE KEY uk_calo_boss_reward_player (fight_id, player_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=6 WHERE id=1");
            }
            if (version < 7) {
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_activity_signals (
                          run_id CHAR(36) NOT NULL, revision BIGINT NOT NULL,
                          protocol_version INT NOT NULL, type VARCHAR(24) NOT NULL,
                          state VARCHAR(16) NOT NULL, progress BIGINT NOT NULL,
                          delivered BOOLEAN NOT NULL DEFAULT FALSE,
                          created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                          PRIMARY KEY (run_id,revision),
                          INDEX idx_calo_signal_delivery (delivered,created_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=7 WHERE id=1");
            }
            if (version < 8) {
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_activity_reward_intents (
                          request_id CHAR(36) NOT NULL PRIMARY KEY,
                          run_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          status VARCHAR(24) NOT NULL DEFAULT 'WAITING_POLICY',
                          UNIQUE KEY uk_calo_activity_reward_run (run_id,player_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=8 WHERE id=1");
            }
            if (version < 9) {
                if (!columnExists(connection, "calo_activity_runs", "started_at"))
                    ddl.executeUpdate("ALTER TABLE calo_activity_runs ADD COLUMN started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
                if (!columnExists(connection, "calo_activity_signals", "actor_id"))
                    ddl.executeUpdate("ALTER TABLE calo_activity_signals ADD COLUMN actor_id CHAR(36) NULL");
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_activity_members (
                          run_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          active BOOLEAN NOT NULL, contribution BIGINT NOT NULL DEFAULT 0,
                          PRIMARY KEY (run_id,player_id),
                          CONSTRAINT chk_calo_activity_contribution CHECK (contribution>=0)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_activity_actions (
                          action_id CHAR(36) NOT NULL PRIMARY KEY,
                          run_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL,
                          amount BIGINT NOT NULL,
                          CONSTRAINT chk_calo_activity_action CHECK (amount>0),
                          INDEX idx_calo_activity_actions_run (run_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=9 WHERE id=1");
            }
            if (version < 10) {
                if (!columnExists(connection, "calo_activity_reward_intents", "payload"))
                    ddl.executeUpdate("ALTER TABLE calo_activity_reward_intents ADD COLUMN payload TEXT NULL");
                ddl.executeUpdate("UPDATE calo_schema_version SET version=10 WHERE id=1");
            }
            if (version < 11) {
                ddl.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS calo_objective_definitions (
                          id VARCHAR(64) NOT NULL PRIMARY KEY,
                          region_id VARCHAR(64) NULL,
                          payload TEXT NOT NULL,
                          enabled BOOLEAN NOT NULL,
                          deleted BOOLEAN NOT NULL DEFAULT FALSE,
                          revision BIGINT NOT NULL,
                          CONSTRAINT chk_calo_objective_revision CHECK (revision>=1),
                          CONSTRAINT fk_calo_objective_region FOREIGN KEY (region_id)
                            REFERENCES calo_regions(id) ON DELETE RESTRICT
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                ddl.executeUpdate("UPDATE calo_schema_version SET version=11 WHERE id=1");
            }
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
