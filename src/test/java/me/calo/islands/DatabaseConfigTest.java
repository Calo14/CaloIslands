package me.calo.islands;

import me.calo.islands.data.DatabaseConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DatabaseConfigTest {
    @Test
    void refusesPlaceholderCredentialsAndInvalidSchema() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.host", "localhost"); yaml.set("database.port", 3306);
        yaml.set("database.name", "caloislands_test");
        yaml.set("database.username", "CHANGE_ME"); yaml.set("database.password", "CHANGE_ME");
        yaml.set("database.pool.maximum-size", 4);
        yaml.set("database.pool.connection-timeout-ms", 10000);
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfig.read(yaml.getConfigurationSection("database")));
        yaml.set("database.username", "test_user"); yaml.set("database.password", "test_password");
        assertEquals("jdbc:mariadb://localhost:3306/caloislands_test",
                DatabaseConfig.read(yaml.getConfigurationSection("database")).jdbcUrl());
        yaml.set("database.password", "  test_password  ");
        assertEquals("  test_password  ",
                DatabaseConfig.read(yaml.getConfigurationSection("database")).password());
        yaml.set("database.name", "goldenrpg; DROP TABLE players");
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfig.read(yaml.getConfigurationSection("database")));
    }
}
