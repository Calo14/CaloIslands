package me.calo.islands.content;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit staff policy only. Missing policy keeps the intent in WAITING_POLICY. */
public final class ActivityRewardRules {
    public record Line(String kind, String resourceId, long amount) {
        public Line {
            if (!Set.of("EXPERIENCE", "COINS", "MATERIAL").contains(kind)
                    || resourceId == null || amount < 1 || amount > 1_000_000_000L
                    || kind.equals("MATERIAL") && !resourceId.matches("[A-Za-z0-9_.:-]{1,128}")
                    || !kind.equals("MATERIAL") && !resourceId.isEmpty())
                throw new IllegalArgumentException("Invalid activity reward line");
        }
    }
    public record Rule(long minimumContribution, List<Line> rewards) {
        public Rule {
            if (minimumContribution < 0 || rewards == null || rewards.isEmpty() || rewards.size() > 16)
                throw new IllegalArgumentException("Invalid activity reward policy");
            rewards = rewards.stream().sorted(Comparator.comparing(Line::kind)
                    .thenComparing(Line::resourceId).thenComparingLong(Line::amount)).toList();
        }
        public String payload() {
            StringBuilder value = new StringBuilder("v1\n");
            for (Line line : rewards) value.append(line.kind()).append('\t').append(line.resourceId())
                    .append('\t').append(line.amount()).append('\n');
            return value.toString();
        }
    }
    private ActivityRewardRules() { }
    public static Map<String, Rule> read(ConfigurationSection root, Set<String> contentIds) {
        if (root == null) return Map.of();
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            if (!contentIds.contains(id)) throw new IllegalArgumentException("Unknown activity reward content: " + id);
            ConfigurationSection row = root.getConfigurationSection(id);
            if (row == null || !(row.get("minimum-contribution") instanceof Number minimum)
                    || !integer(minimum))
                throw new IllegalArgumentException("Missing activity reward eligibility: " + id);
            List<?> raw = row.getList("rewards");
            if (raw == null) throw new IllegalArgumentException("Missing activity rewards: " + id);
            List<Line> lines = new ArrayList<>();
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind)
                        || !(map.get("amount") instanceof Number amount)
                        || !integer(amount))
                    throw new IllegalArgumentException("Invalid activity reward: " + id);
                Object resource = map.containsKey("resource-id") ? map.get("resource-id") : "";
                if (!(resource instanceof String resourceId))
                    throw new IllegalArgumentException("Invalid activity reward resource: " + id);
                lines.add(new Line(kind, resourceId, amount.longValue()));
            }
            rules.put(id, new Rule(minimum.longValue(), lines));
        }
        return Map.copyOf(rules);
    }
    private static boolean integer(Number value) {
        double number = value.doubleValue();
        return Double.isFinite(number) && number == Math.rint(number)
                && number >= Long.MIN_VALUE && number < 0x1.0p63;
    }
    public static List<Line> decode(String payload) {
        if (payload == null || !payload.startsWith("v1\n")) throw new IllegalArgumentException("Unknown reward payload");
        List<Line> lines = new ArrayList<>();
        for (String raw : payload.substring(3).split("\n")) {
            if (raw.isBlank()) continue;
            String[] parts = raw.split("\t", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid reward payload");
            try { lines.add(new Line(parts[0], parts[1], Long.parseLong(parts[2]))); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid reward payload", failure); }
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty reward payload");
        return List.copyOf(lines);
    }
}
