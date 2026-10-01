package me.calo.islands.content;

import java.util.Objects;
import java.util.UUID;

/** A completed activity's stable reward request, held until product policy is approved. */
public record ActivityRewardIntent(UUID requestId, UUID runId, UUID playerId, String status, String payload) {
    public ActivityRewardIntent(UUID requestId, UUID runId, UUID playerId, String status) {
        this(requestId, runId, playerId, status, null);
    }
    public ActivityRewardIntent {
        Objects.requireNonNull(requestId); Objects.requireNonNull(runId);
        Objects.requireNonNull(playerId); Objects.requireNonNull(status);
    }
}
