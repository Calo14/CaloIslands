package me.calo.islands.core;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Local gameplay-region restrictions; unversioned V2 configuration fails closed. */
public record ProtectionSettings(Set<Action> inactive, Set<Action> active, boolean denyActiveEntry) {
    public enum Action {
        BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, CONTAINER, ENTITY_INTERACT,
        BUCKET, FLUID_FLOW, FIRE, EXPLOSION, PISTON, PVP_DAMAGE, PVE_DAMAGE,
        ENTITY_SPAWN, ENTITY_BLOCK_CHANGE, REDSTONE, DISPENSE
    }

    public ProtectionSettings {
        inactive = Set.copyOf(inactive);
        active = Set.copyOf(active);
    }

    public ProtectionSettings(Set<Action> inactive, Set<Action> active) {
        this(inactive, active, false);
    }

    public static ProtectionSettings read(ConfigurationSection section) {
        if (section == null) return new ProtectionSettings(EnumSet.allOf(Action.class), EnumSet.allOf(Action.class));
        if (section.isSet("policy-version") && !section.isInt("policy-version"))
            throw new IllegalArgumentException("protection.policy-version must be an integer");
        int policyVersion = section.isSet("policy-version") ? section.getInt("policy-version") : 1;
        if (policyVersion < 1 || policyVersion > 3)
            throw new IllegalArgumentException("Unsupported protection.policy-version: " + policyVersion);
        if (section.contains("deny-active-entry") && !section.isBoolean("deny-active-entry"))
            throw new IllegalArgumentException("protection.deny-active-entry must be a boolean");
        Set<Action> inactive = readActions(section, "inactive", EnumSet.allOf(Action.class));
        Set<Action> active = policyVersion == 1 ? EnumSet.allOf(Action.class)
                : readActions(section, "active", EnumSet.allOf(Action.class));
        if (policyVersion < 3) {
            // Older files cannot silently leave newly introduced event families unprotected.
            inactive = withNewActions(inactive);
            active = withNewActions(active);
        }
        return new ProtectionSettings(inactive, active,
                section.getBoolean("deny-active-entry", false));
    }

    private static Set<Action> withNewActions(Set<Action> existing) {
        EnumSet<Action> upgraded = existing.isEmpty() ? EnumSet.noneOf(Action.class) : EnumSet.copyOf(existing);
        upgraded.add(Action.REDSTONE);
        upgraded.add(Action.DISPENSE);
        return upgraded;
    }

    private static Set<Action> readActions(ConfigurationSection section, String key, Set<Action> defaults) {
        if (!section.contains(key)) return defaults;
        if (!section.isList(key)) throw new IllegalArgumentException("protection." + key + " must be a list");
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        List<?> entries = section.getList(key);
        for (Object entry : entries) {
            if (!(entry instanceof String value)) throw new IllegalArgumentException("Invalid protection action");
            try {
                actions.add(Action.valueOf(value));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown protection action: " + value, ex);
            }
        }
        return actions;
    }
}
