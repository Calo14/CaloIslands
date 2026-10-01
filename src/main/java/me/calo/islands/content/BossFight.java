package me.calo.islands.content;

import java.util.UUID;

/** Durable snapshot. The fraction is observed from GoldenRPG's committed damage. */
public record BossFight(UUID fightId, UUID entityId, UUID worldId, BossDefinition definition,
                        State state, int phaseIndex, double healthFraction, long revision) {
    public enum State { ACTIVE, COMPLETED, DESPAWNED, CANCELLED }
    public BossFight {
        if (fightId == null || entityId == null || worldId == null || definition == null || state == null
                || phaseIndex < 0 || phaseIndex >= definition.phases().size()
                || !Double.isFinite(healthFraction) || healthFraction < 0 || healthFraction > 1 || revision < 1)
            throw new IllegalArgumentException("Invalid boss fight snapshot");
    }
    public BossFight advance(double fraction) {
        if (fraction > healthFraction) throw new IllegalArgumentException("Boss health cannot increase from damage");
        int phase = definition.phaseAt(fraction);
        return new BossFight(fightId, entityId, worldId, definition,
                fraction == 0 ? State.COMPLETED : State.ACTIVE,
                Math.max(phaseIndex, phase), fraction, Math.addExact(revision, 1));
    }
    public BossFight terminate(State terminal) {
        if (terminal != State.DESPAWNED && terminal != State.CANCELLED)
            throw new IllegalArgumentException("Invalid boss terminal state");
        return new BossFight(fightId, entityId, worldId, definition,
                terminal, phaseIndex, healthFraction, Math.addExact(revision, 1));
    }
}
