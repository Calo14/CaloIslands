package me.calo.islands.core;

import me.calo.islands.domain.PreviewGeometry;
import me.calo.islands.domain.RegionSelectionService;
import org.bukkit.entity.Player;

/** Called only by the plugin's synchronous Bukkit task. Uses player-only packets. */
public final class RegionPreviewService {
    private final RegionSelectionService selections;
    private final PreviewSettings settings;
    private final Messages messages;

    public RegionPreviewService(RegionSelectionService selections, PreviewSettings settings, Messages messages) {
        this.selections = selections;
        this.settings = settings;
        this.messages = messages;
    }

    public void renderFrame(Iterable<? extends Player> players) {
        for (Player player : players) {
            if (!player.hasPermission("caloislands.admin")
                    || !selections.previewEnabled(player.getUniqueId())) continue;
            RegionSelectionService.Selection selection = selections.get(player.getUniqueId()).orElse(null);
            if (selection == null || !selection.sameWorld()
                    || !selection.world().equals(player.getWorld().getName())) continue;
            RegionSelectionService.Mode mode = selections.mode(player.getUniqueId());
            var bounds = selection.effectiveBounds(mode,
                    player.getWorld().getMinHeight(), player.getWorld().getMaxHeight());
            for (PreviewGeometry.Point point : PreviewGeometry.sampledEdges(
                    bounds, settings.maxParticlesPerFrame())) {
                player.spawnParticle(settings.edgeParticle(), point.x(), point.y(), point.z(), 1);
            }
            markCorner(player, selection.first());
            markCorner(player, selection.second());
            player.sendActionBar(messages.text("preview-actionbar", "world", selection.world(),
                    "pos1", selection.first(), "pos2", selection.second(),
                    "dimensions", selection.dimensions(bounds), "mode", mode.name().toLowerCase()));
        }
    }

    private void markCorner(Player player, RegionSelectionService.Position position) {
        player.spawnParticle(settings.cornerParticle(), position.x() + 0.5,
                position.y() + 0.5, position.z() + 0.5, 3);
    }
}
