package me.calo.islands.content;

import me.calo.islands.domain.Ids;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Staff-owned, fully specified non-boss objectives. No rewards or costs are defined here. */
public record ObjectiveSettings(String id, String name, Mode mode, String regionId,
                                double radius, int goal, int captureAt, int maxParticipants,
                                long perPlayerLimit, int timeoutSeconds, Material blockMaterial,
                                EntityType mobType, List<Point> route, double stepBlocks) {
    public enum Mode { DEFENSE, CAPTURE_CONTROL, STRUCTURE, ESCORT, COLLECTION, MOB_EVENT }
    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Invalid activity route point");
        }
    }
    public ObjectiveSettings {
        Ids.require(id); Ids.require(regionId);
        if (name == null || name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)
                || mode == null || !Double.isFinite(radius) || radius <= 0 || radius > 64
                || goal < 1 || goal > 10_000 || maxParticipants < 1 || maxParticipants > 32
                || perPlayerLimit < 1 || perPlayerLimit > 100_000 || timeoutSeconds < 1
                || timeoutSeconds > 86_400 || route == null || !Double.isFinite(stepBlocks)
                || stepBlocks < 0 || stepBlocks > 16)
            throw new IllegalArgumentException("Invalid activity objective");
        route = List.copyOf(route);
        if (Math.multiplyExact(perPlayerLimit, maxParticipants) < goal)
            throw new IllegalArgumentException("Activity contribution capacity below goal");
        if ((mode == Mode.DEFENSE || mode == Mode.CAPTURE_CONTROL)
                && (long) timeoutSeconds * maxParticipants < goal
                || mode == Mode.ESCORT && timeoutSeconds < goal)
            throw new IllegalArgumentException("Activity timeout cannot reach goal");
        if (mode == Mode.CAPTURE_CONTROL && (captureAt < 1 || captureAt >= goal))
            throw new IllegalArgumentException("Invalid capture threshold");
        if ((mode == Mode.STRUCTURE || mode == Mode.COLLECTION)
                && (blockMaterial == null || blockMaterial == Material.AIR
                || blockMaterial == Material.CAVE_AIR || blockMaterial == Material.VOID_AIR
                || Bukkit.getServer() != null && !blockMaterial.isBlock()))
            throw new IllegalArgumentException("Invalid activity block material");
        if (mode == Mode.MOB_EVENT && (mobType == null || !mobType.isAlive() || !mobType.isSpawnable()
                || goal > 32)) throw new IllegalArgumentException("Invalid activity mob event");
        if (mode == Mode.ESCORT && (route.size() < 2 || route.size() > 32 || stepBlocks <= 0))
            throw new IllegalArgumentException("Invalid escort route");
        if (mode == Mode.ESCORT && goal != Math.ceil(routeDistance(route) / stepBlocks))
            throw new IllegalArgumentException("Invalid escort step count");
    }

    public static Map<String, ObjectiveSettings> read(ConfigurationSection root) {
        if (root == null) return Map.of();
        Map<String, ObjectiveSettings> result = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection row = root.getConfigurationSection(id);
            if (row == null) throw new IllegalArgumentException("activity.objectives." + id + " must be a section");
            if (!(row.get("enabled") instanceof Boolean enabled))
                throw new IllegalArgumentException("activity.objectives." + id + ".enabled must be a boolean");
            if (!enabled) continue;
            if (result.size() >= 100) throw new IllegalArgumentException("Too many activity objectives");
            for (String key : List.of("name", "mode", "region-id", "radius-blocks",
                    "max-participants", "max-contribution-per-player", "timeout-seconds"))
                if (!row.isSet(key)) throw new IllegalArgumentException("activity.objectives." + id + "." + key + " is required");
            Mode mode;
            try { mode = Mode.valueOf(row.getString("mode", "")); }
            catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid activity mode: " + id, failure); }
            Material block = null;
            if (mode == Mode.STRUCTURE || mode == Mode.COLLECTION)
                block = Material.matchMaterial(row.getString("block-material", ""));
            EntityType mob = null;
            if (mode == Mode.MOB_EVENT) {
                try { mob = EntityType.valueOf(row.getString("mob-type", "")); }
                catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid activity mob type: " + id, failure); }
            }
            List<Point> route = new ArrayList<>();
            if (mode == Mode.ESCORT) {
                for (String raw : row.getStringList("route")) {
                    String[] parts = raw.split(",", -1);
                    if (parts.length != 3) throw new IllegalArgumentException("Invalid escort route: " + id);
                    try { route.add(new Point(Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()))); }
                    catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid escort route: " + id, failure); }
                }
            }
            double step = mode == Mode.ESCORT ? requireNumber(row, "step-blocks") : 0;
            int goal = mode == Mode.ESCORT ? (int) Math.ceil(routeDistance(route) / step)
                    : requireInt(row, "goal");
            ObjectiveSettings settings = new ObjectiveSettings(id, row.getString("name"), mode,
                    row.getString("region-id"), requireNumber(row, "radius-blocks"), goal,
                    mode == Mode.CAPTURE_CONTROL ? requireInt(row, "capture-at") : 0,
                    requireInt(row, "max-participants"), requireLong(row, "max-contribution-per-player"),
                    requireInt(row, "timeout-seconds"), block, mob, route, step);
            result.put(id, settings);
        }
        return Map.copyOf(result);
    }
    private static double requireNumber(ConfigurationSection row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid activity number: " + key);
        return number.doubleValue();
    }
    private static int requireInt(ConfigurationSection row, String key) {
        double value = requireNumber(row, key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid activity integer: " + key);
        return (int) value;
    }
    private static long requireLong(ConfigurationSection row, String key) {
        double value = requireNumber(row, key);
        if (value != Math.rint(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid activity integer: " + key);
        return (long) value;
    }
    private static double routeDistance(List<Point> route) {
        double distance = 0;
        for (int i = 1; i < route.size(); i++) {
            Point a = route.get(i - 1), b = route.get(i);
            distance += Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2)
                    + Math.pow(b.z() - a.z(), 2));
        }
        return distance;
    }
}
