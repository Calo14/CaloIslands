package me.calo.islands.content;

import java.util.Objects;

/** Versioned post-commit boss lifecycle notification; deduplicate by fight ID and revision. */
public record BossSignal(int protocolVersion, Type type, BossFight fight) {
    public static final int VERSION = 1;
    public enum Type { STARTED, PHASE, COMPLETED, DESPAWNED, CANCELLED, RECOVERED }
    public BossSignal {
        if (protocolVersion != VERSION || type == null || fight == null)
            throw new IllegalArgumentException("Invalid boss signal");
        Objects.requireNonNull(fight.fightId());
    }
}
