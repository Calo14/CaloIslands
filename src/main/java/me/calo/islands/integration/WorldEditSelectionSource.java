package me.calo.islands.integration;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import me.calo.islands.core.SelectionSource;
import me.calo.islands.domain.RegionSelectionService.Position;
import me.calo.islands.domain.RegionSelectionService.Selection;
import org.bukkit.entity.Player;

/** Loaded only when the WorldEdit plugin is enabled. No WorldEdit data is persisted here. */
public final class WorldEditSelectionSource implements SelectionSource {
    public WorldEditSelectionSource() {
        if (WorldEdit.getInstance().getSessionManager() == null)
            throw new IllegalStateException("WorldEdit session manager unavailable");
        try {
            BukkitAdapter.class.getMethod("adapt", Player.class);
            com.sk89q.worldedit.LocalSession.class.getMethod("getSelectionWorld");
            com.sk89q.worldedit.LocalSession.class.getMethod("getSelection", World.class);
            com.sk89q.worldedit.LocalSession.class.getMethod("getRegionSelector", World.class);
            com.sk89q.worldedit.regions.RegionSelector.class.getMethod("clear");
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("WorldEdit selection API incompatible", failure);
        }
    }

    @Override
    public Selection selection(Player player) {
        var actor = BukkitAdapter.adapt(player);
        var session = WorldEdit.getInstance().getSessionManager().get(actor);
        World world = session.getSelectionWorld();
        if (world == null) return null;
        try {
            Region region = session.getSelection(world);
            if (!(region instanceof CuboidRegion))
                throw new UnsupportedShape();
            var min = region.getMinimumPoint();
            var max = region.getMaximumPoint();
            return new Selection(new Position(world.getName(), min.x(), min.y(), min.z()),
                    new Position(world.getName(), max.x(), max.y(), max.z()));
        } catch (IncompleteRegionException ex) {
            return null;
        }
    }

    @Override
    public void clear(Player player) {
        var actor = BukkitAdapter.adapt(player);
        var session = WorldEdit.getInstance().getSessionManager().get(actor);
        World world = session.getSelectionWorld();
        if (world != null) {
            session.getRegionSelector(world).clear();
            session.dispatchCUISelection(actor);
        }
    }
}
