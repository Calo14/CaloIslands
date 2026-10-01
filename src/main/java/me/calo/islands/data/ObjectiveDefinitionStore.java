package me.calo.islands.data;

import me.calo.islands.content.ManagedObjective;
import me.calo.islands.content.ObjectiveSettings;
import me.calo.islands.domain.Destination;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Optimistic MariaDB storage; every load revalidates the full objective. */
public final class ObjectiveDefinitionStore implements ObjectiveDefinitionRepository {
    private final DataSource source;
    public ObjectiveDefinitionStore(DataSource source) { this.source = source; }

    @Override public List<ManagedObjective> list() throws SQLException {
        List<ManagedObjective> result = new ArrayList<>();
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "SELECT id,payload,enabled,deleted,revision FROM calo_objective_definitions ORDER BY id");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    result.add(decode(rows.getString(1), rows.getString(2), rows.getBoolean(3),
                            rows.getBoolean(4), rows.getLong(5)));
                } catch (RuntimeException failure) {
                    throw new SQLException("Invalid stored activity definition: " + rows.getString(1), failure);
                }
            }
        }
        return List.copyOf(result);
    }

    @Override public void save(ManagedObjective value, long expectedRevision) throws SQLException {
        if (value.revision() != expectedRevision + 1) throw new IllegalArgumentException("Invalid activity revision");
        String payload = encode(value);
        try (var connection = source.getConnection()) {
            if (expectedRevision == 0) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO calo_objective_definitions (id,region_id,payload,enabled,deleted,revision)
                        VALUES (?,?,?,?,?,?)
                        """)) {
                    statement.setString(1, value.id());
                    statement.setString(2, value.deleted() ? null : value.settings().regionId());
                    statement.setString(3, payload);
                    statement.setBoolean(4, value.enabled()); statement.setBoolean(5, value.deleted());
                    statement.setLong(6, value.revision()); statement.executeUpdate();
                }
            } else {
                try (var statement = connection.prepareStatement("""
                        UPDATE calo_objective_definitions SET region_id=?,payload=?,enabled=?,deleted=?,revision=?
                        WHERE id=? AND revision=?
                        """)) {
                    statement.setString(1, value.deleted() ? null : value.settings().regionId());
                    statement.setString(2, payload); statement.setBoolean(3, value.enabled());
                    statement.setBoolean(4, value.deleted()); statement.setLong(5, value.revision());
                    statement.setString(6, value.id()); statement.setLong(7, expectedRevision);
                    if (statement.executeUpdate() != 1) throw new SQLException("Activity definition changed; reload before editing");
                }
            }
        }
    }

    static String encode(ManagedObjective value) {
        ObjectiveSettings s = value.settings(); Destination d = value.start();
        YamlConfiguration row = new YamlConfiguration();
        row.set("name", s.name()); row.set("mode", s.mode().name()); row.set("region", s.regionId());
        row.set("radius", s.radius()); row.set("goal", s.goal()); row.set("capture-at", s.captureAt());
        row.set("participants", s.maxParticipants()); row.set("per-player", s.perPlayerLimit());
        row.set("duration", s.timeoutSeconds());
        if (s.blockMaterial() != null) row.set("block", s.blockMaterial().name());
        if (s.mobType() != null) row.set("mob", s.mobType().name());
        row.set("route", s.route().stream().map(p -> p.x() + "," + p.y() + "," + p.z()).toList());
        row.set("step", s.stepBlocks());
        row.set("world", d.world()); row.set("x", d.x()); row.set("y", d.y()); row.set("z", d.z());
        row.set("yaw", d.yaw()); row.set("pitch", d.pitch());
        return row.saveToString();
    }

    static ManagedObjective decode(String id, String payload, boolean enabled, boolean deleted, long revision) {
        try {
            YamlConfiguration row = new YamlConfiguration(); row.loadFromString(payload);
            List<ObjectiveSettings.Point> route = new ArrayList<>();
            for (String raw : row.getStringList("route")) {
                String[] parts = raw.split(",", -1);
                if (parts.length != 3) throw new IllegalArgumentException("Invalid route point");
                route.add(new ObjectiveSettings.Point(Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            }
            ObjectiveSettings settings = new ObjectiveSettings(id, row.getString("name"),
                    ObjectiveSettings.Mode.valueOf(row.getString("mode", "")), row.getString("region"),
                    number(row, "radius"), exactInt(row, "goal"), exactInt(row, "capture-at"),
                    exactInt(row, "participants"), exactLong(row, "per-player"),
                    exactInt(row, "duration"), row.isString("block") ? Material.matchMaterial(row.getString("block")) : null,
                    row.isString("mob") ? EntityType.valueOf(row.getString("mob")) : null,
                    route, number(row, "step"));
            Destination start = new Destination(row.getString("world"), number(row, "x"), number(row, "y"),
                    number(row, "z"), (float) number(row, "yaw"), (float) number(row, "pitch"));
            return new ManagedObjective(settings, start, enabled, deleted, revision);
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid stored activity: " + id, invalid); }
    }
    private static double number(YamlConfiguration row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalArgumentException("Invalid activity field: " + key);
        return number.doubleValue();
    }
    private static int exactInt(YamlConfiguration row, String key) {
        double value = number(row, key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid activity integer: " + key);
        return (int) value;
    }
    private static long exactLong(YamlConfiguration row, String key) {
        double value = number(row, key);
        if (value != Math.rint(value) || value < Long.MIN_VALUE || value >= 0x1.0p63)
            throw new IllegalArgumentException("Invalid activity integer: " + key);
        return (long) value;
    }
}
