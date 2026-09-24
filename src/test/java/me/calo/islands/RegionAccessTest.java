package me.calo.islands;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionAccessPolicy;
import me.calo.islands.domain.RegionService;
import me.calo.islands.core.Messages;
import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.listener.RegionAccessListener;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class RegionAccessTest {
    @Test
    void inactiveRegionDeniesEntryButAllowsExitAndLoadedActiveRegion() {
        List<Region> inactive = List.of(new Region("test_region", "test_world",
                Bounds.between(0, 60, 0, 10, 80, 10), false, 1));
        AtomicBoolean loaded = new AtomicBoolean(true);
        assertFalse(RegionAccessPolicy.mayEnter(inactive, world -> loaded.get(),
                "test_world", 20, 70, 20, "test_world", 5, 70, 5));
        assertTrue(RegionAccessPolicy.mayEnter(inactive, world -> loaded.get(),
                "test_world", 5, 70, 5, "test_world", 6, 70, 6));
        assertTrue(RegionAccessPolicy.mayEnter(inactive, world -> loaded.get(),
                "test_world", 5, 70, 5, "test_world", 20, 70, 20));
        List<Region> active = List.of(new Region("test_region", "test_world", inactive.get(0).bounds(), true, 2));
        assertTrue(RegionAccessPolicy.mayEnter(active, world -> loaded.get(),
                "test_world", 20, 70, 20, "test_world", 5, 70, 5));
        loaded.set(false);
        assertFalse(RegionAccessPolicy.mayEnter(active, world -> loaded.get(),
                "test_world", 20, 70, 20, "test_world", 5, 70, 5));
        loaded.set(true);
        assertFalse(RegionAccessPolicy.mayEnter(active, world -> loaded.get(),
                "test_world", 20, 70, 20, "test_world", 5, 70, 5, true));
        assertTrue(RegionAccessPolicy.mayEnter(active, world -> loaded.get(),
                "test_world", 5, 70, 5, "test_world", 6, 70, 6, true));
        assertTrue(RegionAccessPolicy.mayEnter(active, world -> loaded.get(),
                "test_world", 5, 70, 5, "test_world", 20, 70, 20, true));
    }

    @Test
    void movementAndTeleportShareConfiguredEntryGate() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location from = new Location(world, 20, 70, 20);
        Location to = new Location(world, 5, 70, 5);
        Player player = mock(Player.class);
        RegionAccessListener listener = new RegionAccessListener(regions,
                new Messages(new File("src/main/resources/messages.yml")),
                new ProtectionSettings(java.util.Set.of(), java.util.Set.of(), true));
        when(regions.mayEnter("test_world", 20, 70, 20,
                "test_world", 5, 70, 5, true)).thenReturn(false);
        PlayerMoveEvent move = mock(PlayerMoveEvent.class);
        when(move.getFrom()).thenReturn(from);
        when(move.getTo()).thenReturn(to);
        when(move.getPlayer()).thenReturn(player);
        listener.onMove(move);
        verify(move).setCancelled(true);
        PlayerTeleportEvent teleport = mock(PlayerTeleportEvent.class);
        when(teleport.getFrom()).thenReturn(from);
        when(teleport.getTo()).thenReturn(to);
        when(teleport.getPlayer()).thenReturn(player);
        listener.onTeleport(teleport);
        verify(teleport).setCancelled(true);
        verify(player, times(2)).sendActionBar(contains("No puedes entrar"));
    }

    @Test
    void overlappingDestinationDeniesEntryEvenWhenBothRegionsAreActive() {
        Region first = new Region("first", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 1);
        Region second = new Region("second", "test_world", new Bounds(5, 0, 5, 15, 20, 15), true, 1);
        assertFalse(RegionAccessPolicy.mayEnter(List.of(first, second), world -> true,
                "test_world", 30, 10, 30, "test_world", 5, 10, 5));
    }
}
