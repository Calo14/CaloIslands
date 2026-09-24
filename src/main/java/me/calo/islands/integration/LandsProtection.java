package me.calo.islands.integration;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.Flags;
import me.calo.islands.core.ProtectionSettings.Action;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Queries the claim area and role flags; Lands remains the claims authority. */
public final class LandsProtection implements ExternalProtection {
    private final LandsIntegration lands;

    public LandsProtection(Plugin owner) {
        lands = LandsIntegration.of(owner);
        if (lands == null) throw new IllegalStateException("Lands API unavailable");
        if (Flags.BLOCK_BREAK == null || Flags.BLOCK_PLACE == null || Flags.INTERACT_CONTAINER == null)
            throw new IllegalStateException("Lands role flags unavailable");
    }

    @Override
    public boolean denies(Player player, Action action, Location location) {
        if (player == null) return false; // Native Lands handles non-player events.
        var area = lands.getArea(location);
        if (area == null) return false;
        var flag = switch (action) {
            case BLOCK_BREAK -> Flags.BLOCK_BREAK;
            case BLOCK_PLACE, BUCKET -> Flags.BLOCK_PLACE;
            case CONTAINER -> Flags.INTERACT_CONTAINER;
            case BLOCK_INTERACT -> Flags.INTERACT_GENERAL;
            case ENTITY_INTERACT -> Flags.INTERACT_GENERAL;
            case PVP_DAMAGE -> Flags.ATTACK_PLAYER;
            default -> null; // Native Lands listeners own the other flags.
        };
        return flag != null && !area.hasFlag(player, flag, false);
    }
}
