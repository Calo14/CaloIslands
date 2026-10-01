package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.core.ActivityService;
import me.calo.islands.domain.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointDefenseServiceTest {
    @Test void enabledConfigurationRequiresRealDurationAndRadius() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("activity.defense.enabled", true);
        yaml.set("activity.defense.id", "defense_event");
        yaml.set("activity.defense.region-id", "test_region");
        assertThrows(IllegalArgumentException.class, () -> PointDefenseSettings.read(
                yaml.getConfigurationSection("activity.defense")));
        yaml.set("activity.defense.duration-seconds", 10);
        yaml.set("activity.defense.radius-blocks", 3.0);
        assertEquals(10, PointDefenseSettings.read(
                yaml.getConfigurationSection("activity.defense")).durationSeconds());
        yaml.set("activity.defense.radius-blocks", -1);
        assertThrows(IllegalArgumentException.class, () -> PointDefenseSettings.read(
                yaml.getConfigurationSection("activity.defense")));
    }

    @Test void activationUsesPersistedRunAndTickCommitsProgressBeforeCompletion() throws Exception {
        RegionService regions = mock(RegionService.class);
        ActivityService activities = mock(ActivityService.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        Destination point = new Destination("test_world", 5, 64, 5, 0, 0);
        Region region = new Region("test_region", "test_world",
                new Bounds(0, -64, 0, 20, 319, 20), true, 1, point);
        when(regions.region("test_region")).thenReturn(Optional.of(region));
        when(regions.operational(region)).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("caloislands.activity")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("test_world");
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        PointDefenseService defense = new PointDefenseService(plugin, activities, regions,
                new PointDefenseSettings("defense_event", "test_region", 1, 3));
        ActivityDefinition definition = new ActivityDefinition("defense_event",
                ActivityDefinition.Kind.EVENT, "test_region", point, true, Set.of());
        ActivityRun run = new ActivityRun(UUID.randomUUID(), playerId, definition,
                ActivityRun.State.RUNNING, 0, 1);
        ActivityRun progressed = run.next(ActivityRun.State.RUNNING, 1);
        when(activities.start(eq(definition), eq(player))).thenReturn(run);
        when(activities.checkpoint(run.runId())).thenReturn(Optional.of(run), Optional.of(progressed));
        when(activities.progress(run.runId(), 1)).thenReturn(progressed);
        assertTrue(defense.activate(player));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            defense.tick();
            defense.tick();
        }
        verify(activities).progress(run.runId(), 1);
        verify(activities).complete(run.runId());
    }

    @Test void offlinePlayerCancelsWithoutProgress() throws Exception {
        RegionService regions = mock(RegionService.class);
        ActivityService activities = mock(ActivityService.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        Destination point = new Destination("test_world", 5, 64, 5, 0, 0);
        Region region = new Region("test_region", "test_world",
                new Bounds(0, -64, 0, 20, 319, 20), true, 1, point);
        when(regions.region("test_region")).thenReturn(Optional.of(region));
        when(regions.operational(region)).thenReturn(true, false);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("caloislands.activity")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("test_world");
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        PointDefenseService defense = new PointDefenseService(plugin, activities, regions,
                new PointDefenseSettings("defense_event", "test_region", 10, 3));
        ActivityDefinition definition = new ActivityDefinition("defense_event",
                ActivityDefinition.Kind.EVENT, "test_region", point, true, Set.of());
        ActivityRun run = new ActivityRun(UUID.randomUUID(), playerId, definition,
                ActivityRun.State.RUNNING, 0, 1);
        when(activities.start(eq(definition), eq(player))).thenReturn(run);
        when(activities.checkpoint(run.runId())).thenReturn(Optional.of(run));
        assertTrue(defense.activate(player));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(null);
            defense.tick();
            verify(activities, never()).progress(any(), anyLong());
        }
        verify(activities).cancel(run.runId());
        verify(activities, never()).progress(any(), anyLong());
    }

    @Test void interactionCannotActivateWhenActivityPermissionIsDenied() throws Exception {
        RegionService regions = mock(RegionService.class);
        ActivityService activities = mock(ActivityService.class);
        Player player = mock(Player.class);
        Destination point = new Destination("test_world", 5, 64, 5, 0, 0);
        Region region = new Region("test_region", "test_world",
                new Bounds(0, -64, 0, 20, 319, 20), true, 1, point);
        when(regions.region("test_region")).thenReturn(Optional.of(region));
        PointDefenseService defense = new PointDefenseService(mock(JavaPlugin.class), activities,
                regions, new PointDefenseSettings("defense_event", "test_region", 10, 3));

        assertFalse(defense.activate(player));
        verify(activities, never()).start(any(), any());
    }
}
