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
    private final Region region = new Region("region_a", "world_a", new Bounds(0, 60, 0, 10, 80, 10), true, 1, new Destination("world_a", 5.5, 64, 5.5, 73, -12));
    @Test void menuInspectionDoesNotLoadTerrainAndLabelsLoadedHazards() {
        setup(); var service = new AdminTeleportService(regions,List.of());
        try (MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true); bukkit.when(()->Bukkit.getWorld("world_a")).thenReturn(world);
            clearInvocations(world);
            assertEquals(AdminTeleportService.DestinationStatus.TERRAIN_UNCHECKED,service.inspectDestination(region.destination()));
            verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            assertEquals(AdminTeleportService.DestinationStatus.VALID,service.inspectDestination(region.destination()));
            when(world.getBlockAt(anyInt(),eq(63),anyInt())).thenReturn(mock(Block.class));
            assertEquals(AdminTeleportService.DestinationStatus.INVALID,service.inspectDestination(region.destination()));
            bukkit.when(()->Bukkit.getWorld("world_a")).thenReturn(null);
            assertEquals(AdminTeleportService.DestinationStatus.WORLD_UNAVAILABLE,service.inspectDestination(region.destination()));
        }
    }
    @Test void accessChecksRespectEntryPolicyAndRejectCallsOutsideServerThread() {
        setup(); var messages=new me.calo.islands.core.Messages(new java.io.File("src/main/resources/messages.yml"));
        var service=new AdminTeleportService(regions,List.of(),messages,true);
        try (MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true); bukkit.when(()->Bukkit.getWorld("world_a")).thenReturn(world);
            assertFalse(service.canAccess(player,region.destination()));
            verify(regions).mayEnter(anyString(),anyDouble(),anyDouble(),anyDouble(),anyString(),anyDouble(),anyDouble(),anyDouble(),eq(true));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class,()->service.region(player,"region_a"));
            assertFalse(service.canAccess(player,region.destination()));
        }
        verify(player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    private void setup() {
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.getName()).thenReturn("world_a"); when(player.hasPermission("caloislands.admin")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        when(regions.region("region_a")).thenReturn(Optional.of(region));
        when(regions.mayEnter(anyString(), anyDouble(), anyDouble(), anyDouble(), anyString(), anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class); when(border.isInside(any())).thenReturn(true); when(world.getWorldBorder()).thenReturn(border);
        Block floor = mock(Block.class), air = mock(Block.class); when(floor.getType()).thenReturn(Material.STONE);
        when(air.getType()).thenReturn(Material.AIR); when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            if (call.getArgument(1, Integer.class) != 63) return air;
            int x = call.getArgument(0), z = call.getArgument(2);
            when(floor.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(x, 63, z, x + 1, 64, z + 1)); return floor;
        });
    }
    @Test void regionWithoutPointNeverFallsBackToCentre() {
        setup(); when(regions.region("region_a")).thenReturn(Optional.of(new Region("region_a","world_a",region.bounds(),true,1)));
        assertThrows(IllegalArgumentException.class,()->new AdminTeleportService(regions,List.of()).region(player,"region_a"));
        verify(player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
        verifyNoInteractions(world.getWorldBorder());
    }
    @Test void storedPointWithUnsafeFloorAndInactiveEntryIsRejected() {
        setup();
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(()->Bukkit.getWorld("world_a")).thenReturn(world);
            when(regions.mayEnter(anyString(),anyDouble(),anyDouble(),anyDouble(),anyString(),anyDouble(),anyDouble(),anyDouble())).thenReturn(false);
            assertThrows(IllegalArgumentException.class,()->new AdminTeleportService(regions,List.of()).region(player,"region_a"));
            when(world.getBlockAt(anyInt(),eq(63),anyInt())).thenReturn(mock(Block.class));
            assertThrows(IllegalArgumentException.class,()->new AdminTeleportService(regions,List.of()).region(player,"region_a"));
        }
        verify(player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void regionUsesStoredPointOrientationAndNativeTeleportCause() {
        setup(); when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world); new AdminTeleportService(regions, List.of()).region(player, "region_a");
        }
        var destination = org.mockito.ArgumentCaptor.forClass(Location.class); verify(player).teleport(destination.capture(), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(5.5, destination.getValue().getX()); assertEquals(64, destination.getValue().getY()); assertEquals(5.5, destination.getValue().getZ());
        assertEquals(73, destination.getValue().getYaw()); assertEquals(-12, destination.getValue().getPitch());
    }
    @Test void cityUsesPersistedLocationAndExternalEntryDenialCannotBeBypassed() throws Exception {
        setup(); City city = new City("city_a", "region_a", "world_a", 5.5, 64, 5.5, 1); when(regions.city("city_a")).thenReturn(Optional.of(city));
        ExternalProtection authority = mock(ExternalProtection.class); when(authority.deniesEntry(eq(player), any())).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
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
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
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
