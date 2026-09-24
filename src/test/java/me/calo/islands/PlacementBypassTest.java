package me.calo.islands;

import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.data.RegionRepository;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionProtectionPolicy;
import me.calo.islands.domain.RegionService;
import me.calo.islands.listener.RegionProtectionListener;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

final class PlacementBypassTest {
    @Test
    void placementAndBreakUseStartupSnapshotWithoutStorageCalls() throws Exception {
        RegionRepository store = mock(RegionRepository.class);
        when(store.regions()).thenReturn(List.of(new Region("test", "test_world",
                new Bounds(0, 0, 0, 10, 100, 10), true, 1)));
        RegionService regions = new RegionService(store, name -> Optional.empty());
        clearInvocations(store);
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(regions, ProtectionSettings.read(null)));
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location at = new Location(world, 5, 70, 5);
        Block block = mock(Block.class);
        BlockState replaced = mock(BlockState.class);
        when(block.getLocation()).thenReturn(at);
        when(replaced.getLocation()).thenReturn(at);
        Player player = mock(Player.class);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getPlayer()).thenReturn(player);
        when(breaking.getBlock()).thenReturn(block);
        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getPlayer()).thenReturn(player);
        when(placing.getBlockPlaced()).thenReturn(block);
        BlockMultiPlaceEvent multi = mock(BlockMultiPlaceEvent.class);
        when(multi.getPlayer()).thenReturn(player);
        when(multi.getReplacedBlockStates()).thenReturn(List.of(replaced));

        listener.onBreak(breaking);
        listener.onPlace(placing);
        listener.onMultiPlace(multi);
        verify(breaking).setCancelled(true);
        verify(placing).setCancelled(true);
        verify(multi).setCancelled(true);
        verifyNoInteractions(store);
    }

    @Test
    void samePermissionControlsBreakPlaceAndMultiPlaceInBothGameModes() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location at = new Location(world, 5, 70, 5);
        when(regions.matchingAt("test_world", 5, 70, 5)).thenReturn(List.of(
                new Region("test", "test_world", new Bounds(0, 0, 0, 10, 100, 10), true, 1)));
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(regions, ProtectionSettings.read(null)));
        Block block = mock(Block.class);
        BlockState replaced = mock(BlockState.class);
        when(block.getLocation()).thenReturn(at);
        when(replaced.getLocation()).thenReturn(at);

        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.CREATIVE)) {
            for (boolean bypass : List.of(false, true)) {
                Player player = mock(Player.class);
                when(player.getGameMode()).thenReturn(mode);
                when(player.hasPermission(RegionProtectionPolicy.BYPASS_PERMISSION)).thenReturn(bypass);
                BlockBreakEvent breaking = mock(BlockBreakEvent.class);
                when(breaking.getPlayer()).thenReturn(player);
                when(breaking.getBlock()).thenReturn(block);
                listener.onBreak(breaking);
                verify(breaking, bypass ? never() : times(1)).setCancelled(true);
                verify(breaking, never()).setCancelled(false);

                BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
                when(placing.getPlayer()).thenReturn(player);
                when(placing.getBlockPlaced()).thenReturn(block);
                listener.onPlace(placing);
                verify(placing, bypass ? never() : times(1)).setCancelled(true);
                verify(placing, never()).setCancelled(false);

                BlockMultiPlaceEvent multi = mock(BlockMultiPlaceEvent.class);
                when(multi.getPlayer()).thenReturn(player);
                when(multi.getReplacedBlockStates()).thenReturn(List.of(replaced));
                listener.onMultiPlace(multi);
                verify(multi, bypass ? never() : times(1)).setCancelled(true);
                verify(multi, never()).setCancelled(false);
            }
        }
    }

    @Test
    void bypassCoversFramesStandsAndBucketsWithoutUncancellingOthers() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location at = new Location(world, 5, 70, 5);
        when(regions.matchingAt("test_world", 5, 70, 5)).thenReturn(List.of(
                new Region("test", "test_world", new Bounds(0, 0, 0, 10, 100, 10), false, 1)));
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(regions, ProtectionSettings.read(null)));
        Player player = mock(Player.class);
        when(player.hasPermission(RegionProtectionPolicy.BYPASS_PERMISSION)).thenReturn(true);
        Entity entity = mock(Entity.class);
        Hanging frame = mock(Hanging.class);
        when(entity.getLocation()).thenReturn(at);
        when(frame.getLocation()).thenReturn(at);
        HangingPlaceEvent hanging = mock(HangingPlaceEvent.class);
        when(hanging.getPlayer()).thenReturn(player);
        when(hanging.getEntity()).thenReturn(frame);
        listener.onHangingPlace(hanging);
        verify(hanging, never()).setCancelled(anyBoolean());

        EntityPlaceEvent placing = mock(EntityPlaceEvent.class);
        when(placing.getPlayer()).thenReturn(player);
        when(placing.getEntity()).thenReturn(entity);
        listener.onEntityPlace(placing);
        verify(placing, never()).setCancelled(anyBoolean());

        Block block = mock(Block.class);
        Block target = mock(Block.class);
        when(block.getLocation()).thenReturn(at);
        when(block.getRelative(BlockFace.UP)).thenReturn(target);
        when(target.getLocation()).thenReturn(at);
        PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
        when(empty.getPlayer()).thenReturn(player);
        when(empty.getBlockClicked()).thenReturn(block);
        when(empty.getBlockFace()).thenReturn(BlockFace.UP);
        listener.onBucketEmpty(empty);
        verify(empty, never()).setCancelled(anyBoolean());
        PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
        when(fill.getPlayer()).thenReturn(player);
        when(fill.getBlockClicked()).thenReturn(block);
        listener.onBucketFill(fill);
        verify(fill, never()).setCancelled(anyBoolean());

        BlockPlaceEvent alreadyDenied = mock(BlockPlaceEvent.class);
        when(alreadyDenied.isCancelled()).thenReturn(true);
        listener.onPlace(alreadyDenied);
        verify(alreadyDenied, never()).setCancelled(anyBoolean());
        BlockMultiPlaceEvent multiDenied = mock(BlockMultiPlaceEvent.class);
        when(multiDenied.isCancelled()).thenReturn(true);
        listener.onMultiPlace(multiDenied);
        verify(multiDenied, never()).setCancelled(anyBoolean());
    }
}
