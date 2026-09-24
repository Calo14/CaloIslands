package me.calo.islands.domain;

import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.integration.ExternalProtection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.List;

/** Read-only snapshot lookup; never changes another plugin's cancellation decision. */
public final class RegionProtectionPolicy {
    public static final String BYPASS_PERMISSION = "caloislands.protection.bypass";
    private final RegionService regions;
    private final ProtectionSettings settings;
    private final List<ExternalProtection> external;

    public RegionProtectionPolicy(RegionService regions, ProtectionSettings settings) {
        this(regions, settings, List.of());
    }

    public RegionProtectionPolicy(RegionService regions, ProtectionSettings settings,
                                  List<ExternalProtection> external) {
        this.regions = regions;
        this.settings = settings;
        this.external = List.copyOf(external);
    }

    public boolean denies(ProtectionSettings.Action action, Location at) {
        List<Region> matches = matches(at);
        if (matches == null || matches.size() > 1) return true;
        if (matches.isEmpty()) return false;
        var region = matches.getFirst();
        return (region.active() ? settings.active() : settings.inactive()).contains(action);
    }

    public boolean denies(Player actor, ProtectionSettings.Action action, Location at) {
        List<Region> matches = matches(at);
        if (matches == null || matches.size() > 1) return true;
        if (matches.isEmpty()) return false;
        // The CaloIslands bypass removes only our decision. Native Lands/WorldGuard
        // listeners still own their event cancellations and are never un-cancelled here.
        if (hasBypass(actor)) return false;
        Region region = matches.getFirst();
        if ((region.active() ? settings.active() : settings.inactive()).contains(action)) return true;
        for (ExternalProtection adapter : external) {
            try {
                if (adapter.denies(actor, action, at)) return true;
            } catch (RuntimeException | LinkageError failure) {
                return true; // An unresolved external denial cannot become a grant.
            }
        }
        return false;
    }

    public boolean hasBypass(Player actor) {
        return actor != null && actor.hasPermission(BYPASS_PERMISSION);
    }

    private List<Region> matches(Location at) {
        if (at == null || at.getWorld() == null) return null;
        try {
            return regions.matchingAt(at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
