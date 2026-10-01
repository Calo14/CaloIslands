package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.core.ActivityService;
import me.calo.islands.domain.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObjectiveActivityServiceTest {
    @Test void managedStartPointUsesPersistedDestinationAndCanBeRemoved() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("CaloIslands"); when(plugin.namespace()).thenReturn("caloislands");
        ActivityService activities = mock(ActivityService.class);
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class); when(world.getName()).thenReturn("world");
        Player player = mock(Player.class); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 9, 64, 9));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Destination regionPoint = new Destination("world", 5, 64, 5, 0, 0);
        Destination managedPoint = new Destination("world", 9, 64, 9, 0, 0);
        Region region = new Region("coast", "world", new Bounds(0, -64, 0, 20, 319, 20), true, 1, regionPoint);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        ObjectiveSettings settings = new ObjectiveSettings("defense_managed", "Defensa",
                ObjectiveSettings.Mode.DEFENSE, "coast", 2, 1, 0, 1, 1, 60,
                null, null, List.of(), 0);
        ObjectiveActivityService service = new ObjectiveActivityService(plugin, activities, regions,
                Map.of(), (p, location) -> true);
        service.upsert(settings, managedPoint);
        assertEquals(managedPoint, service.startPoint(settings.id()));
        ActivityDefinition definition = new ActivityDefinition(settings.id(), ActivityDefinition.Kind.EVENT,
                "coast", managedPoint, true, java.util.Set.of("caloislands.activity"));
        ActivityRun run = new ActivityRun(UUID.randomUUID(), player.getUniqueId(), definition,
                ActivityRun.State.RUNNING, 0, 1);
        when(activities.start(definition, player)).thenReturn(run);
        when(activities.checkpoint(run.runId())).thenReturn(Optional.of(run));
        assertEquals(run.runId(), service.activate(player, settings.id()).runId());
        verify(activities).start(definition, player);
        assertThrows(IllegalStateException.class, () -> service.remove(settings.id()));
        service.cancel(player, settings.id());
        verify(activities).cancel(run.runId());
        service.stop();
        service.remove(settings.id());
        assertTrue(service.definitions().isEmpty());
    }
    @Test void configuredDefenseActivatesCreditsConfirmedPresenceAndCompletes() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("CaloIslands");
        when(plugin.namespace()).thenReturn("caloislands");
        ActivityService activities = mock(ActivityService.class);
        RegionService regions = mock(RegionService.class);
        Player player = mock(Player.class); World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(player.isOnline()).thenReturn(true);
        UUID playerId = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(playerId);
        Destination point = new Destination("world", 5, 64, 5, 0, 0);
        Region region = new Region("coast", "world", new Bounds(0, -64, 0, 20, 319, 20),
                true, 1, point);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        when(regions.operational(region)).thenReturn(true);
        ObjectiveSettings settings = new ObjectiveSettings("defense", "Defensa del faro",
                ObjectiveSettings.Mode.DEFENSE, "coast", 8, 1, 0, 1,
                1, 120, null, null, List.of(), 0);
        ActivityRun run = new ActivityRun(UUID.randomUUID(), playerId,
                new ActivityDefinition("defense", ActivityDefinition.Kind.EVENT,
                        "coast", point, true, java.util.Set.of("caloislands.activity")),
                ActivityRun.State.RUNNING, 0, 1);
        when(activities.start(eq(run.definition()), eq(player))).thenReturn(run);
        when(activities.join(run.runId(), player, 1)).thenReturn(run.next(ActivityRun.State.RUNNING, 0));
        when(activities.checkpoint(run.runId())).thenReturn(Optional.of(run));
        when(activities.members(run.runId())).thenReturn(List.of(new ActivityMember(playerId, true, 0)));
        when(activities.startedAt(run.runId())).thenReturn(Instant.now());
        when(activities.contribute(eq(run.runId()), eq(player), any(UUID.class), eq(1L), eq(1L)))
                .thenReturn(run.next(ActivityRun.State.RUNNING, 1));
        ObjectiveActivityService service = new ObjectiveActivityService(plugin, activities, regions,
                Map.of(settings.id(), settings), (p, l) -> true);
        assertEquals(run.runId(), service.activate(player, "defense").runId());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            service.tick();
        }
        verify(activities).contribute(eq(run.runId()), eq(player), any(UUID.class), eq(1L), eq(1L));
        verify(activities).complete(run.runId());
    }
    @Test void collectionExceptionRequiresActiveMemberCorrectBlockAndExternalPermission() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("CaloIslands");
        when(plugin.namespace()).thenReturn("caloislands");
        ActivityService activities = mock(ActivityService.class);
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        Location at = new Location(world, 5, 64, 5);
        when(player.getLocation()).thenReturn(at);
        Destination point = new Destination("world", 5, 64, 5, 0, 0);
        Region region = new Region("coast", "world", new Bounds(0, -64, 0, 20, 319, 20),
                true, 1, point);
        when(regions.region("coast")).thenReturn(Optional.of(region));
        when(regions.operational(region)).thenReturn(true);
        when(regions.matchingAt("world", 5, 64, 5)).thenReturn(List.of(region));
        ObjectiveSettings settings = new ObjectiveSettings("collection", "Mina",
                ObjectiveSettings.Mode.COLLECTION, "coast", 8, 2, 0, 1,
                2, 120, Material.STONE, null, List.of(), 0);
        ActivityRun run = new ActivityRun(UUID.randomUUID(), playerId,
                new ActivityDefinition("collection", ActivityDefinition.Kind.EVENT,
                        "coast", point, true, java.util.Set.of("caloislands.activity")),
                ActivityRun.State.RUNNING, 0, 1);
        when(activities.start(eq(run.definition()), eq(player))).thenReturn(run);
        when(activities.checkpoint(run.runId())).thenReturn(Optional.of(run));
        when(activities.members(run.runId())).thenReturn(List.of(new ActivityMember(playerId, true, 0)));
        java.util.concurrent.atomic.AtomicBoolean externalAllows = new java.util.concurrent.atomic.AtomicBoolean(true);
        ObjectiveActivityService service = new ObjectiveActivityService(plugin, activities, regions,
                Map.of(settings.id(), settings), (p, action, location) -> externalAllows.get());
        service.activate(player, "collection");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(block.getLocation()).thenReturn(at);
        assertTrue(service.allowsBlockAction(player, block, ObjectiveSettings.Mode.COLLECTION));
        assertFalse(service.allowsBlockAction(player, block, ObjectiveSettings.Mode.STRUCTURE));
        externalAllows.set(false);
        assertFalse(service.allowsBlockAction(player, block, ObjectiveSettings.Mode.COLLECTION));
        externalAllows.set(true);
        when(activities.members(run.runId())).thenReturn(List.of(new ActivityMember(playerId, false, 0)));
        assertFalse(service.allowsBlockAction(player, block, ObjectiveSettings.Mode.COLLECTION));
    }
}
