package me.calo.islands.content;

import me.calo.islands.domain.Destination;

import java.util.Objects;

/** One persisted override of an existing objective, including its independent start point. */
public record ManagedObjective(ObjectiveSettings settings, Destination start, boolean enabled,
                               boolean deleted, long revision) {
    public ManagedObjective {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(start);
        if (revision < 0) throw new IllegalArgumentException("Invalid activity revision");
    }
    public String id() { return settings.id(); }
    public ManagedObjective next(ObjectiveSettings settings, Destination start, boolean enabled, boolean deleted) {
        return new ManagedObjective(settings, start, enabled, deleted, revision + 1);
    }
}
