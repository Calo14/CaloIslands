package me.calo.islands.core;

import me.calo.islands.domain.PreviewGeometry;
import me.calo.islands.domain.RegionSelectionService;
import org.bukkit.entity.Player;

/** Called only by the plugin's synchronous Bukkit task. Uses player-only packets. */
public final class RegionPreviewService {
    private java.util.function.Supplier<java.util.List<me.calo.islands.domain.Region>> registry = java.util.List::of;
    public void setRegions(java.util.function.Supplier<java.util.List<me.calo.islands.domain.Region>> registry) { this.registry = registry; }
    private final java.util.Set<java.util.UUID> viewers = new java.util.HashSet<>();
    private final RegionSelectionService selections;
    private PreviewSettings settings;
    public void updateSettings(PreviewSettings settings) { clearAll(); this.settings = settings; }
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

    public boolean active(java.util.UUID player) { return viewers.contains(player) && selections.previewEnabled(player); }
    public boolean showingRegion(java.util.UUID player, String id) {
        var region = regionPreviews.get(player);
        return active(player) && region != null && region.id().equals(id);
    }
    public void startSelection(java.util.UUID player) {
        clear(player); viewers.add(player);
        if (!selections.previewEnabled(player)) selections.togglePreview(player);
    }
    public void region(java.util.UUID player, me.calo.islands.domain.Region region) {
        regionPreviews.put(player, region); viewers.add(player);
        if (!selections.previewEnabled(player)) selections.togglePreview(player);
    }
    public void clear(java.util.UUID player) { regionPreviews.remove(player); }
    public void stop(java.util.UUID player) {
        clear(player); viewers.remove(player);
        if (selections.previewEnabled(player)) selections.togglePreview(player);
    }
    public void clearAll() {
        var ids = new java.util.HashSet<>(viewers);
        ids.addAll(regionPreviews.keySet());
        for (java.util.UUID id : ids) stop(id);
        viewers.clear();
        regionPreviews.clear();
    }
    public void invalidate(String regionId) {
        for (var entry : java.util.List.copyOf(regionPreviews.entrySet()))
            if (entry.getValue().id().equals(regionId)) stop(entry.getKey());
    }

    public void renderFrame(Iterable<? extends Player> players) {
        for (Player player : players) {
            if (!player.hasPermission("caloislands.admin")) {
                if (viewers.contains(player.getUniqueId())) stop(player.getUniqueId());
                continue;
            }
            if (!selections.previewEnabled(player.getUniqueId())) continue;
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
            viewers.add(player.getUniqueId());
            RegionSelectionService.Mode mode = region == null ? selections.mode(player.getUniqueId()) : RegionSelectionService.Mode.EXACT;
            var bounds = selection.effectiveBounds(mode,
                    player.getWorld().getMinHeight(), player.getWorld().getMaxHeight());
            final String worldName = selection.world();
            boolean conflict = registry.get().stream().anyMatch(r -> r.world().equals(worldName)
                    && (region == null || !r.id().equals(region.id())) && r.bounds().overlaps(bounds));
            boolean invalid = conflict || bounds.minY() < player.getWorld().getMinHeight() || bounds.maxY() >= player.getWorld().getMaxHeight();
            var color = invalid ? settings.invalidColor() : region == null ? settings.selectionColor()
                    : region.active() ? settings.activeColor() : settings.inactiveColor();
            var dust = new org.bukkit.Particle.DustOptions(color, 1.25f);
            for (PreviewGeometry.Point point : PreviewGeometry.sampledVolume(bounds, settings.maxParticlesPerFrame(),
                    settings.maxPointSpacing(), settings.gridSpacing()))
                player.spawnParticle(org.bukkit.Particle.DUST, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0, dust);
            String state = messages.text(invalid ? "preview-status-invalid" : region == null ? "preview-status-selection"
                    : region.active() ? "preview-status-active" : "preview-status-inactive");
            String destination = region == null || region.destination() == null ? messages.text("point-unset") : region.destination().world()
                    + " " + region.destination().x() + "," + region.destination().y() + "," + region.destination().z();
            player.sendActionBar(messages.text("preview-actionbar", "world", selection.world(),
                    "pos1", selection.first(), "pos2", selection.second(),
                    "dimensions", selection.dimensions(bounds), "mode", mode.name().toLowerCase(),
                    "height", ((long)bounds.maxY()-bounds.minY()+1), "state", state, "point", destination)
                    + " " + messages.text("preview-context", "height", ((long)bounds.maxY()-bounds.minY()+1), "state", state, "point", destination));
        }
    }

}
