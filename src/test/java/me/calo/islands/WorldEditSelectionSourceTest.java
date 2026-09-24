package me.calo.islands;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.session.SessionManager;
import me.calo.islands.integration.WorldEditSelectionSource;
import me.calo.islands.core.SelectionSource;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class WorldEditSelectionSourceTest {
    @Test
    void readsCuboidInSelectionWorldAndClearsOnlyWhenAsked() throws Exception {
        Player player = mock(Player.class);
        BukkitPlayer actor = mock(BukkitPlayer.class);
        WorldEdit worldEdit = mock(WorldEdit.class);
        SessionManager manager = mock(SessionManager.class);
        LocalSession session = mock(LocalSession.class);
        com.sk89q.worldedit.world.World world = mock(com.sk89q.worldedit.world.World.class);
        CuboidRegion selected = mock(CuboidRegion.class);
        RegionSelector selector = mock(RegionSelector.class);
        when(worldEdit.getSessionManager()).thenReturn(manager);
        when(manager.get(actor)).thenReturn(session);
        when(session.getSelectionWorld()).thenReturn(world);
        when(world.getName()).thenReturn("test_world");
        when(session.getSelection(world)).thenReturn(selected);
        when(selected.getMinimumPoint()).thenReturn(BlockVector3.at(-3, 10, 2));
        when(selected.getMaximumPoint()).thenReturn(BlockVector3.at(9, 70, 12));
        when(session.getRegionSelector(world)).thenReturn(selector);
        try (MockedStatic<WorldEdit> we = mockStatic(WorldEdit.class);
             MockedStatic<BukkitAdapter> adapter = mockStatic(BukkitAdapter.class)) {
            we.when(WorldEdit::getInstance).thenReturn(worldEdit);
            adapter.when(() -> BukkitAdapter.adapt(player)).thenReturn(actor);
            var source = new WorldEditSelectionSource();
            var selection = source.selection(player);
            assertNotNull(selection);
            assertEquals("test_world", selection.world());
            assertEquals(-3, selection.bounds().minX());
            assertEquals(70, selection.bounds().maxY());
            verify(selector, never()).clear();
            source.clear(player);
            verify(selector).clear();
            verify(session).dispatchCUISelection(actor);
        }
    }

    @Test
    void incompleteAndNonCuboidSelectionsCannotBecomeGameplayRegions() throws Exception {
        Player player = mock(Player.class);
        BukkitPlayer actor = mock(BukkitPlayer.class);
        WorldEdit worldEdit = mock(WorldEdit.class);
        SessionManager manager = mock(SessionManager.class);
        LocalSession session = mock(LocalSession.class);
        com.sk89q.worldedit.world.World world = mock(com.sk89q.worldedit.world.World.class);
        when(worldEdit.getSessionManager()).thenReturn(manager);
        when(manager.get(actor)).thenReturn(session);
        when(session.getSelectionWorld()).thenReturn(world);
        when(session.getSelection(world)).thenThrow(mock(IncompleteRegionException.class))
                .thenReturn(mock(com.sk89q.worldedit.regions.Region.class));
        try (MockedStatic<WorldEdit> we = mockStatic(WorldEdit.class);
             MockedStatic<BukkitAdapter> adapter = mockStatic(BukkitAdapter.class)) {
            we.when(WorldEdit::getInstance).thenReturn(worldEdit);
            adapter.when(() -> BukkitAdapter.adapt(player)).thenReturn(actor);
            var source = new WorldEditSelectionSource();
            assertNull(source.selection(player));
            assertThrows(SelectionSource.UnsupportedShape.class, () -> source.selection(player));
            verify(session, never()).getRegionSelector(world);
        }
    }
}
