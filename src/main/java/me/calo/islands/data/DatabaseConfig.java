package me.calo.islands.data;

import org.bukkit.configuration.ConfigurationSection;

public record DatabaseConfig(String host, int port, String name, String username,
                             String password, int maximumPoolSize, long connectionTimeoutMs) {
    public static DatabaseConfig read(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Missing database section");
        String host = required(section, "host");
        String name = required(section, "name");
        String username = required(section, "username");
        String password = section.getString("password");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Missing database.password");
        int port = section.getInt("port", -1);
        ConfigurationSection pool = section.getConfigurationSection("pool");
        if (pool == null) throw new IllegalArgumentException("Missing database.pool section");
        int size = pool.getInt("maximum-size", -1);
        long timeout = pool.getLong("connection-timeout-ms", -1);
        if (!host.matches("[a-zA-Z0-9.:-]{1,255}") || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid database host or schema name");
        }
        if (port < 1 || port > 65535 || size < 1 || size > 32 || timeout < 250 || timeout > 60000) {
            throw new IllegalArgumentException("Invalid database port or pool settings");
        }
        if (username.equalsIgnoreCase("CHANGE_ME") || password.trim().equalsIgnoreCase("CHANGE_ME")) {
            throw new IllegalArgumentException("Configure CaloIslands database credentials on the server");
        }
        return new DatabaseConfig(host, port, name, username, password, size, timeout);
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + name;
    }

    private static String required(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing database." + key);
        return value.trim();
    }
}
