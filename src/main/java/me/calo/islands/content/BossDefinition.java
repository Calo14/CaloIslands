package me.calo.islands.content;

import me.calo.islands.domain.Ids;
import java.util.List;

/** Phase thresholds only; MythicMobs owns entity templates and skills. */
public record BossDefinition(String id, String mythicMobId, List<Phase> phases) {
    public record Phase(String id, double enterAtFraction) {
        public Phase {
            Ids.require(id);
            if (!Double.isFinite(enterAtFraction) || enterAtFraction <= 0 || enterAtFraction > 1)
                throw new IllegalArgumentException("Invalid boss phase threshold");
        }
    }
    public BossDefinition {
        Ids.require(id);
        if (mythicMobId == null || !mythicMobId.matches("[A-Za-z0-9_:-]{2,128}")
                || phases == null || phases.isEmpty())
            throw new IllegalArgumentException("Invalid boss definition");
        phases = List.copyOf(phases);
        if (phases.getFirst().enterAtFraction() != 1.0D)
            throw new IllegalArgumentException("First boss phase must start at full health");
        java.util.Set<String> ids = new java.util.HashSet<>();
        double previous = Double.POSITIVE_INFINITY;
        for (Phase phase : phases) {
            if (!ids.add(phase.id()) || phase.enterAtFraction() >= previous)
                throw new IllegalArgumentException("Boss phases must be unique and descending");
            previous = phase.enterAtFraction();
        }
    }
    public int phaseAt(double healthFraction) {
        if (!Double.isFinite(healthFraction) || healthFraction < 0 || healthFraction > 1)
            throw new IllegalArgumentException("Invalid boss health fraction");
        int selected = 0;
        for (int i = 1; i < phases.size(); i++)
            if (healthFraction <= phases.get(i).enterAtFraction()) selected = i;
        return selected;
    }
}
