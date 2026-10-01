package me.calo.islands.content;

import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Ids;
import java.util.Set;

/** Supplied by future content integrations; no built-in encounters or rewards. */
public record ActivityDefinition(String id, Kind kind, String regionId, Destination entry,
                                 boolean active, Set<String> requiredPermissions) {
    public enum Kind { EVENT, DUNGEON }
    public ActivityDefinition {
        Ids.require(id); Ids.require(regionId);
        if (kind == null || entry == null || requiredPermissions == null || requiredPermissions.size() > 32)
            throw new IllegalArgumentException("Invalid activity definition");
        requiredPermissions = Set.copyOf(requiredPermissions);
        if (requiredPermissions.stream().anyMatch(p -> !p.matches("[a-z0-9_.-]{1,128}")))
            throw new IllegalArgumentException("Invalid access permission");
    }
}
