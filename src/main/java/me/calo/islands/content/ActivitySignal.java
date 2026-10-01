package me.calo.islands.content;

import java.util.UUID;

/** Consumers may deduplicate with (protocolVersion, runId, revision). Contains no reward instructions. */
public record ActivitySignal(int protocolVersion, Type type, ActivityRun checkpoint, UUID actor) {
    public static final int VERSION = 1;
    public static final int GROUP_VERSION = 2;
    public enum Type { STARTED, PROGRESS, COMPLETED, CANCELLED, FAILED, RECOVERED,
        JOINED, CONTRIBUTED, LEFT }
    public ActivitySignal(int protocolVersion, Type type, ActivityRun checkpoint) {
        this(protocolVersion, type, checkpoint, null);
    }
    public ActivitySignal {
        if ((protocolVersion != VERSION && protocolVersion != GROUP_VERSION)
                || type == null || checkpoint == null
                || (type == Type.JOINED || type == Type.CONTRIBUTED || type == Type.LEFT)
                    && (protocolVersion != GROUP_VERSION || actor == null)
                || protocolVersion == VERSION && actor != null)
            throw new IllegalArgumentException("Invalid activity signal");
    }
}
