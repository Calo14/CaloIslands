package me.calo.islands.core;

import me.calo.islands.domain.PreviewGeometry;
import me.calo.islands.domain.RegionSelectionService;
import org.bukkit.entity.Player;

/** Called only by the plugin's synchronous Bukkit task. Uses player-only packets. */
public final class RegionPreviewService {
    private final RegionSelectionService selections;
    private final PreviewSettings settings;
    private final Messages messages;
    private final SelectionSource source;
    private final java.util.Map<java.util.UUID, me.calo.islands.domain.Region> regionPreviews = new java.util.HashMap<>();

    public RegionPreviewService(RegionSelectionService selections, PreviewSettings settings, Messages messages) {
        this(selections, settings, messages, null);
    }

    public RegionPreviewService(RegionSelectionService selections, PreviewSettings settings, Messages messages, SelectionSource source) {
        this.selections = selections;
        this.settings = settings;
        this.messages = messages;
        this.source = source;
    }

    public void region(java.util.UUID player, me.calo.islands.domain.Region region) { regionPreviews.put(player, region); }
    public void clear(java.util.UUID player) { regionPreviews.remove(player); }

    public void renderFrame(Iterable<? extends Player> players) {
        for (Player player : players) {
            if (!player.hasPermission("caloislands.admin")
                    || !selections.previewEnabled(player.getUniqueId())) continue;
            var region = regionPreviews.get(player.getUniqueId());
            RegionSelectionService.Selection selection;
            selection = null;
            if (region == null) {
                try { selection = source == null ? selections.get(player.getUniqueId()).orElse(null) : source.selection(player); }
                catch (RuntimeException | LinkageError unavailable) { continue; }
            }
            if (region != null) {
                var b = region.bounds();
                selection = new RegionSelectionService.Selection(
                        new RegionSelectionService.Position(region.world(), b.minX(), b.minY(), b.minZ()),
                        new RegionSelectionService.Position(region.world(), b.maxX(), b.maxY(), b.maxZ()));
            }
            if (selection == null || !selection.sameWorld()
                    || !selection.world().equals(player.getWorld().getName())) continue;
            RegionSelectionService.Mode mode = region == null ? selections.mode(player.getUniqueId()) : RegionSelectionService.Mode.EXACT;
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
