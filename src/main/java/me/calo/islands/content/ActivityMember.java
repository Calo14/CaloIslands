package me.calo.islands.content;

import java.util.UUID;

/** Confirmed individual contribution within one activity run. */
public record ActivityMember(UUID playerId, boolean active, long contribution) {
    public ActivityMember {
        if (playerId == null || contribution < 0) throw new IllegalArgumentException("Invalid activity member");
    }
}
