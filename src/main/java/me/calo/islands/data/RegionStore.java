package me.calo.islands.data;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.City;
import me.calo.islands.domain.Region;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** MariaDB boundary for the single-server region registry. */
public final class RegionStore implements RegionRepository {
    private final DataSource source;

    public RegionStore(DataSource source) { this.source = source; }

    public int schemaVersion() throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT version FROM calo_schema_version WHERE id=1")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    public List<Region> regions() throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM calo_regions ORDER BY id"); ResultSet rows = query.executeQuery()) {
            List<Region> result = new ArrayList<>();
            while (rows.next()) result.add(region(rows));
            return List.copyOf(result);
        }
    }

    public Optional<Region> region(String id) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM calo_regions WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(region(rows)) : Optional.empty();
            }
        }
    }

    public void insert(Region value) throws SQLException {
        transaction(connection -> {
            lockWorld(connection, value.world());
            ensureNoOverlap(connection, value);
            try (PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO calo_regions (id,world_name,min_x,min_y,min_z,max_x,max_y,max_z,active,version,point_world,point_x,point_y,point_z,point_yaw,point_pitch) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                query.setString(1, value.id()); query.setString(2, value.world());
                bounds(query, 3, value.bounds()); query.setBoolean(9, value.active());
                query.setInt(10, value.version()); destination(query, 11, value.destination()); query.executeUpdate();
            }
            return null;
        });
    }

    public boolean update(Region next, int expectedVersion) throws SQLException {
        return transaction(connection -> {
            lockWorld(connection, next.world());
            Region current = regionForUpdate(connection, next.id());
            if (current == null || current.version() != expectedVersion) return false;
            if (!current.world().equals(next.world())) throw new IllegalArgumentException("Region world cannot change");
            if (!current.bounds().equals(next.bounds())) {
                if (current.active()) throw new IllegalStateException("Deactivate the region before resizing");
                ensureCitiesInside(connection, next);
                ensureNoOverlap(connection, next);
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    UPDATE calo_regions SET min_x=?,min_y=?,min_z=?,max_x=?,max_y=?,max_z=?,active=?,version=?,point_world=?,point_x=?,point_y=?,point_z=?,point_yaw=?,point_pitch=?
                    WHERE id=? AND version=?
                    """)) {
                bounds(query, 1, next.bounds()); query.setBoolean(7, next.active());
                query.setInt(8, next.version()); destination(query, 9, next.destination()); query.setString(15, next.id());
                query.setInt(16, expectedVersion); return query.executeUpdate() == 1;
            }
        });
    }

    public boolean deleteRegion(String id, int expectedVersion) throws SQLException {
        return transaction(connection -> {
            Region current = regionForUpdate(connection, id);
            if (current == null || current.version() != expectedVersion) return false;
            if (current.active()) throw new IllegalStateException("Deactivate the region before deleting");
            try (PreparedStatement query = connection.prepareStatement(
                    "DELETE FROM calo_regions WHERE id=? AND version=?")) {
                query.setString(1, id); query.setInt(2, expectedVersion);
                return query.executeUpdate() == 1;
            }
        });
    }

    public List<City> cities() throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM calo_cities ORDER BY id"); ResultSet rows = query.executeQuery()) {
            List<City> result = new ArrayList<>();
            while (rows.next()) result.add(city(rows));
            return List.copyOf(result);
        }
    }

    public Optional<City> city(String id) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM calo_cities WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(city(rows)) : Optional.empty();
            }
        }
    }

    public void insert(City value) throws SQLException {
        transaction(connection -> {
            requireCityInside(connection, value);
            try (PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO calo_cities (id,region_id,world_name,x,y,z,version,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?)")) {
                cityValues(query, value); query.executeUpdate();
            }
            return null;
        });
    }

    public boolean update(City next, int expectedVersion) throws SQLException {
        return transaction(connection -> {
            requireCityInside(connection, next);
            try (PreparedStatement query = connection.prepareStatement("""
                    UPDATE calo_cities SET region_id=?,world_name=?,x=?,y=?,z=?,version=?,yaw=?,pitch=? WHERE id=? AND version=?
                    """)) {
                query.setString(1, next.regionId()); query.setString(2, next.world());
                query.setDouble(3, next.x()); query.setDouble(4, next.y()); query.setDouble(5, next.z());
                query.setInt(6, next.version()); query.setFloat(7, next.yaw()); query.setFloat(8, next.pitch()); query.setString(9, next.id());
                query.setInt(10, expectedVersion); return query.executeUpdate() == 1;
            }
        });
    }

    public boolean deleteCity(String id, int expectedVersion) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "DELETE FROM calo_cities WHERE id=? AND version=?")) {
            query.setString(1, id); query.setInt(2, expectedVersion);
            return query.executeUpdate() == 1;
        }
    }

    private void lockWorld(Connection connection, String world) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO calo_world_locks (world_name) VALUES (?)")) {
            insert.setString(1, world); insert.executeUpdate();
        }
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT world_name FROM calo_world_locks WHERE world_name=? FOR UPDATE")) {
            lock.setString(1, world);
            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next()) throw new SQLException("World lock unavailable");
            }
        }
    }

    private static void ensureNoOverlap(Connection connection, Region next) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id FROM calo_regions WHERE world_name=? AND id<>?
                  AND min_x<=? AND max_x>=? AND min_y<=? AND max_y>=? AND min_z<=? AND max_z>=?
                LIMIT 1
                """)) {
            query.setString(1, next.world()); query.setString(2, next.id());
            query.setInt(3, next.bounds().maxX()); query.setInt(4, next.bounds().minX());
            query.setInt(5, next.bounds().maxY()); query.setInt(6, next.bounds().minY());
            query.setInt(7, next.bounds().maxZ()); query.setInt(8, next.bounds().minZ());
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) throw new IllegalArgumentException("Region overlaps " + rows.getString(1));
            }
        }
    }

    private static void ensureCitiesInside(Connection connection, Region next) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,x,y,z FROM calo_cities WHERE region_id=?")) {
            query.setString(1, next.id());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) if (!next.bounds().contains(rows.getDouble(2), rows.getDouble(3), rows.getDouble(4))) {
                    throw new IllegalArgumentException("City " + rows.getString(1) + " would be outside the region");
                }
            }
        }
    }

    private static void requireCityInside(Connection connection, City value) throws SQLException {
        Region parent = regionForUpdate(connection, value.regionId());
        if (parent == null) throw new IllegalArgumentException("Unknown region: " + value.regionId());
        if (!parent.world().equals(value.world()))
            throw new IllegalArgumentException("City world differs from region " + parent.id());
        if (!parent.bounds().contains(value.x(), value.y(), value.z())) {
            throw new IllegalArgumentException("City location is outside region " + parent.id());
        }
    }

    private static Region regionForUpdate(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM calo_regions WHERE id=? FOR UPDATE")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? region(rows) : null; }
        }
    }

    private static void cityValues(PreparedStatement query, City value) throws SQLException {
        query.setString(1, value.id()); query.setString(2, value.regionId()); query.setString(3, value.world());
        query.setDouble(4, value.x()); query.setDouble(5, value.y()); query.setDouble(6, value.z());
        query.setInt(7, value.version()); query.setFloat(8, value.yaw()); query.setFloat(9, value.pitch());
    }

    private static Destination destination(ResultSet row) throws SQLException {
        String world = row.getString("point_world");
        if (world == null) return null;
        for (String field : new String[]{"point_x", "point_y", "point_z", "point_yaw", "point_pitch"})
            if (row.getObject(field) == null) throw new SQLException("Incomplete region destination");
        return new Destination(world, row.getDouble("point_x"), row.getDouble("point_y"), row.getDouble("point_z"), row.getFloat("point_yaw"), row.getFloat("point_pitch"));
    }
    private static void destination(PreparedStatement query, int first, Destination point) throws SQLException {
        if (point == null) {
            query.setNull(first, java.sql.Types.VARCHAR);
            for (int i=1; i<=5; i++) query.setNull(first+i, java.sql.Types.DOUBLE);
        } else {
            query.setString(first, point.world()); query.setDouble(first+1, point.x()); query.setDouble(first+2, point.y());
            query.setDouble(first+3, point.z()); query.setFloat(first+4, point.yaw()); query.setFloat(first+5, point.pitch());
        }
    }

    private static void bounds(PreparedStatement query, int first, Bounds b) throws SQLException {
        query.setInt(first, b.minX()); query.setInt(first + 1, b.minY()); query.setInt(first + 2, b.minZ());
        query.setInt(first + 3, b.maxX()); query.setInt(first + 4, b.maxY()); query.setInt(first + 5, b.maxZ());
    }

    private static Region region(ResultSet row) throws SQLException {
        return new Region(row.getString("id"), row.getString("world_name"), new Bounds(
                row.getInt("min_x"), row.getInt("min_y"), row.getInt("min_z"),
                row.getInt("max_x"), row.getInt("max_y"), row.getInt("max_z")),
                row.getBoolean("active"), row.getInt("version"), destination(row));
    }

    private static City city(ResultSet row) throws SQLException {
        return new City(row.getString("id"), row.getString("region_id"), row.getString("world_name"),
                row.getDouble("x"), row.getDouble("y"), row.getDouble("z"), row.getInt("version"), row.getFloat("yaw"), row.getFloat("pitch"));
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private interface Work<T> { T run(Connection connection) throws SQLException; }
}
