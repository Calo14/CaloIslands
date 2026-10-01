package me.calo.islands.content;

import java.util.UUID;

/** A single already-confirmed GoldenRPG combat or support action. */
public record BossAction(UUID actionId, UUID playerId, Kind kind, double amount,
                         Double remainingHealthFraction) {
    public enum Kind { DAMAGE, HEAL, PROTECTION, CONTROL, SHIELD_BREAK, REVIVE, SUPPORT }
    public BossAction {
        if (actionId == null || playerId == null || kind == null
                || !Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000D)
            throw new IllegalArgumentException("Invalid boss contribution");
        if (kind == Kind.DAMAGE) {
            if (remainingHealthFraction == null || !Double.isFinite(remainingHealthFraction)
                    || remainingHealthFraction < 0 || remainingHealthFraction > 1)
                throw new IllegalArgumentException("Damage requires a committed health fraction");
        } else if (remainingHealthFraction != null) {
            throw new IllegalArgumentException("Support must not change boss health");
        }
    }
}
