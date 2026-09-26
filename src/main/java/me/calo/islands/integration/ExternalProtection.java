package me.calo.islands.integration;

import me.calo.islands.core.ProtectionSettings.Action;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Read-only denial check. External plugins still own their flags and event handlers. */
public interface ExternalProtection {
    boolean denies(Player player, Action action, Location location);
    default boolean deniesEntry(Player player, Location location) {
        return denies(player, Action.BLOCK_INTERACT, location);
    }
}
