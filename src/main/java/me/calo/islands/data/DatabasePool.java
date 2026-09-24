package me.calo.islands.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DatabasePool implements AutoCloseable {
    private final HikariDataSource source;

    public DatabasePool(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("CaloIslands-Hikari");
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setValidationTimeout(Math.min(config.connectionTimeoutMs(), 5000));
        hikari.setAutoCommit(true);
        this.source = new HikariDataSource(hikari);
    }

    public HikariDataSource dataSource() { return source; }

    @Override public void close() { source.close(); }
}
