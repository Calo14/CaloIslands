package me.calo.islands.content;

import java.util.UUID;

/** Durable checkpoint. Progress is an absolute monotonic counter, without balance semantics. */
public record ActivityRun(UUID runId, UUID participant, ActivityDefinition definition,
                          State state, long progress, long revision) {
    public enum State { RUNNING, COMPLETED, CANCELLED, FAILED }
    public ActivityRun {
        if (runId == null || participant == null || definition == null || state == null || progress < 0 || revision < 1)
            throw new IllegalArgumentException("Invalid activity checkpoint");
    }
    public ActivityRun next(State state, long progress) {
        return new ActivityRun(runId, participant, definition, state, progress, Math.addExact(revision, 1));
    }
}
