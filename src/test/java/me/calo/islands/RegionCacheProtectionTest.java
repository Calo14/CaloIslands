package me.calo.islands;

import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.data.RegionRepository;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionProtectionPolicy;
import me.calo.islands.domain.RegionService;
import me.calo.islands.listener.RegionProtectionListener;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class RegionCacheProtectionTest {
    @Test
    void recoveredRegionProtectsNonAdminWithoutStorageCallsPerEvent() throws Exception {
        RegionRepository store = mock(RegionRepository.class);
        Region saved = new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 4);
        when(store.regions()).thenReturn(List.of(saved));
        RegionService recovered = new RegionService(store, name -> Optional.of(new RegionService.WorldHeight(0, 100)));
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 20, 10));
        Player normal = mock(Player.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(normal);
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(recovered, ProtectionSettings.read(null)));
        for (int i = 0; i < 20; i++) listener.onBreak(event);
        verify(event, times(20)).setCancelled(true);
        verify(store).regions();
        verifyNoMoreInteractions(store);
        assertEquals(4, recovered.region("test").orElseThrow().version());
    }
}
