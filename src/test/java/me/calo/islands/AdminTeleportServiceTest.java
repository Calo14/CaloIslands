package me.calo.islands;
import me.calo.islands.core.AdminTeleportService;
import me.calo.islands.domain.*;
import me.calo.islands.integration.ExternalProtection;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
final class AdminTeleportServiceTest {
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final RegionService regions = mock(RegionService.class);
    private final Region region = new Region("region_a", "world_a", new Bounds(0, 60, 0, 10, 80, 10), true, 1);
    private void setup() {
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.getName()).thenReturn("world_a"); when(player.hasPermission("caloislands.admin")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        when(regions.region("region_a")).thenReturn(Optional.of(region));
        WorldBorder border = mock(WorldBorder.class); when(border.isInside(any())).thenReturn(true); when(world.getWorldBorder()).thenReturn(border);
        Block floor = mock(Block.class), air = mock(Block.class); when(floor.getType()).thenReturn(Material.STONE);
        when(air.getType()).thenReturn(Material.AIR); when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            if (call.getArgument(1, Integer.class) != 63) return air;
            int x = call.getArgument(0), z = call.getArgument(2);
            when(floor.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(x, 63, z, x + 1, 64, z + 1)); return floor;
        });
    }
    @Test void regionUsesSafeCentreAndNativeTeleportCause() {
        setup(); when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world); new AdminTeleportService(regions, List.of()).region(player, "region_a");
        }
        var destination = org.mockito.ArgumentCaptor.forClass(Location.class); verify(player).teleport(destination.capture(), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(5.5, destination.getValue().getX()); assertEquals(64, destination.getValue().getY()); assertEquals(5.5, destination.getValue().getZ());
    }
    @Test void cityUsesPersistedLocationAndExternalEntryDenialCannotBeBypassed() throws Exception {
        setup(); City city = new City("city_a", "region_a", "world_a", 5.5, 64, 5.5, 1); when(regions.city("city_a")).thenReturn(Optional.of(city));
        ExternalProtection authority = mock(ExternalProtection.class); when(authority.deniesEntry(eq(player), any())).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world);
            assertThrows(IllegalArgumentException.class, () -> new AdminTeleportService(regions, List.of(authority)).city(player, "city_a"));
        }
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void missingPermissionWorldUnsafeFloorAndCancelledTeleportHaveClearFailure() {
        setup(); when(player.hasPermission("caloislands.admin")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> new AdminTeleportService(regions, List.of()).region(player, "region_a"));
        when(player.hasPermission("caloislands.admin")).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertThrows(IllegalArgumentException.class, () -> new AdminTeleportService(regions, List.of()).region(player, "region_a"));
            bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world);
            assertThrows(IllegalArgumentException.class, () -> new AdminTeleportService(regions, List.of()).region(player, "region_a"));
            verify(player, never()).sendMessage("§aTeletransporte completado.");
        }
    }
    @Test void lavaPartialSupportAndBlockedHeadroomAreNeverSafe() {
        setup(); Location at = new Location(world, 5.5, 64, 5.5); assertTrue(AdminTeleportService.safe(at));
        Block hazard = mock(Block.class); when(hazard.getType()).thenReturn(Material.MAGMA_BLOCK);
        when(world.getBlockAt(anyInt(), eq(63), anyInt())).thenReturn(hazard); assertFalse(AdminTeleportService.safe(at));
        when(hazard.getType()).thenReturn(Material.OAK_FENCE); assertFalse(AdminTeleportService.safe(at));
        setup(); Block wall = mock(Block.class); when(wall.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(anyInt(), eq(65), anyInt())).thenReturn(wall); assertFalse(AdminTeleportService.safe(at));
    }
}
