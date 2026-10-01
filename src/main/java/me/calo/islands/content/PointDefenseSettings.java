package me.calo.islands.content;

import me.calo.islands.domain.Ids;
import org.bukkit.configuration.ConfigurationSection;

/** No gameplay values are supplied when the activity is enabled. Staff config owns them. */
public record PointDefenseSettings(String id, String regionId, int durationSeconds,
                                   double radiusBlocks) {
    public PointDefenseSettings {
        Ids.require(id);
        Ids.require(regionId);
        if (durationSeconds < 1 || durationSeconds > 86_400
                || !Double.isFinite(radiusBlocks) || radiusBlocks <= 0 || radiusBlocks > 128)
            throw new IllegalArgumentException("Invalid point defense duration or radius");
    }

    public static PointDefenseSettings read(ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) return null;
        for (String key : new String[]{"id", "region-id", "duration-seconds", "radius-blocks"})
            if (!section.isSet(key)) throw new IllegalArgumentException("activity.defense." + key + " is required");
        if (!section.isInt("duration-seconds") || !(section.get("radius-blocks") instanceof Number radius))
            throw new IllegalArgumentException("Invalid activity.defense duration or radius type");
        return new PointDefenseSettings(section.getString("id"), section.getString("region-id"),
                section.getInt("duration-seconds"), radius.doubleValue());
    }
}
