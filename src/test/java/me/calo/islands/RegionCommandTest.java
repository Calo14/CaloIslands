package me.calo.islands;

import me.calo.islands.command.RegionCommand;
import me.calo.islands.core.Messages;
import me.calo.islands.core.SelectionTool;
import me.calo.islands.core.SelectionSource;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.City;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionSelectionService;
import me.calo.islands.domain.RegionService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class RegionCommandTest {
    private final Messages messages = new Messages(new File("src/main/resources/messages.yml"));

    @Test
    void wandAndSelectionCreateNormalizedRegionThenClearOnlyOnSuccess() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionSelectionService selections = new RegionSelectionService();
        SelectionTool tool = mock(SelectionTool.class);
        RegionCommand command = new RegionCommand(regions, selections, tool, messages);
        Player player = admin();
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack wand = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(4);
        when(tool.create()).thenReturn(wand);
        invoke(command, player, "region", "wand");
        verify(inventory).setItem(4, wand);

        UUID id = player.getUniqueId();
        selections.setFirst(id, "test_world", 10, 80, 10);
        selections.setSecond(id, "test_world", 0, 60, 0);
        selections.togglePreview(id);
        Bounds bounds = new Bounds(0, 0, 0, 10, 319, 10);
        Region region = new Region("test_region", "test_world", bounds, false, 1);
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(320);
        when(regions.createRegion("test_region", "test_world", bounds)).thenReturn(region);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("test_world")).thenReturn(world);
            invoke(command, player, "region", "selection");
            verify(player, atLeastOnce()).sendMessage(contains("11x320x11"));
            invoke(command, player, "region", "create", "test_region");
        }
        verify(regions).createRegion("test_region", "test_world", bounds);
        assertTrue(selections.get(id).isEmpty());
        assertTrue(selections.previewEnabled(id));
    }

    @Test
    void crossWorldSelectionCannotCreateAndResizeUsesCurrentSelection() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionSelectionService selections = new RegionSelectionService();
        RegionCommand command = new RegionCommand(regions, selections, mock(SelectionTool.class), messages);
        Player player = admin();
        UUID id = player.getUniqueId();
        selections.setFirst(id, "world_a", 1, 60, 1);
        selections.setSecond(id, "world_b", 4, 80, 4);
        invoke(command, player, "region", "create", "test_region");
        verify(regions, never()).createRegion(anyString(), anyString(), any());
        assertTrue(selections.get(id).isPresent());

        selections.setSecond(id, "world_a", 4, 80, 4);
        invoke(command, player, "region", "mode", "exact");
        assertEquals(RegionSelectionService.Mode.EXACT, selections.mode(id));
        Bounds bounds = new Bounds(1, 60, 1, 4, 80, 4);
        Region current = new Region("test_region", "world_a", new Bounds(0, 60, 0, 5, 80, 5), false, 1);
        when(regions.region("test_region")).thenReturn(Optional.of(current));
        when(regions.resizeRegion("test_region", bounds)).thenReturn(
                new Region("test_region", "world_a", bounds, false, 2));
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(320);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world);
            invoke(command, player, "region", "resize", "test_region");
        }
        verify(regions).resizeRegion("test_region", bounds);
        assertTrue(selections.get(id).isEmpty());
        invoke(command, player, "region", "preview");
        assertFalse(selections.previewEnabled(id));
        invoke(command, player, "region", "preview");
        assertTrue(selections.previewEnabled(id));
        invoke(command, player, "region", "clear");
        assertTrue(selections.get(id).isEmpty());
        assertEquals(RegionSelectionService.Mode.FULLHEIGHT, selections.mode(id));
    }

    @Test
    void worldEditSelectionIsPrimaryAndOnlyClearedAfterSuccessfulSave() throws Exception {
        RegionService regions = mock(RegionService.class);
        SelectionSource worldEdit = mock(SelectionSource.class);
        RegionCommand command = new RegionCommand(regions, new RegionSelectionService(),
                mock(SelectionTool.class), messages, worldEdit);
        Player player = admin();
        invoke(command, player, "region", "create", "test_region");
        verify(regions, never()).createRegion(anyString(), anyString(), any());
        verify(worldEdit, never()).clear(player);

        var selection = new RegionSelectionService.Selection(
                new RegionSelectionService.Position("test_world", 10, 80, 10),
                new RegionSelectionService.Position("test_world", 0, 60, 0));
        when(worldEdit.selection(player)).thenReturn(selection);
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-32);
        when(world.getMaxHeight()).thenReturn(320);
        Bounds bounds = new Bounds(0, -32, 0, 10, 319, 10);
        when(regions.createRegion("test_region", "test_world", bounds)).thenReturn(
                new Region("test_region", "test_world", bounds, false, 1));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("test_world")).thenReturn(world);
            invoke(command, player, "region", "create", "test_region");
        }
        verify(regions).createRegion("test_region", "test_world", bounds);
        verify(worldEdit).clear(player);
    }

    @Test
    void cityCreateAndMoveUseAdministratorsLocationAndRegionWorld() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionCommand command = new RegionCommand(regions, new RegionSelectionService(),
                mock(SelectionTool.class), messages);
        Player player = admin();
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        when(player.getLocation()).thenReturn(new Location(world, 5.5, 70, 6.5),
                new Location(world, 7.5, 70, 8.5));
        Region region = new Region("test_region", "test_world", new Bounds(0, 60, 0, 10, 80, 10), true, 2);
        when(regions.region("test_region")).thenReturn(Optional.of(region));
        when(regions.createCity("test_city", "test_region", 5.5, 70, 6.5)).thenReturn(
                new City("test_city", "test_region", "test_world", 5.5, 70, 6.5, 1));
        when(regions.city("test_city")).thenReturn(Optional.of(
                new City("test_city", "test_region", "test_world", 5.5, 70, 6.5, 1)));
        when(regions.moveCity("test_city", "test_region", 7.5, 70, 8.5)).thenReturn(
                new City("test_city", "test_region", "test_world", 7.5, 70, 8.5, 2));
        invoke(command, player, "city", "create", "test_city", "test_region");
        invoke(command, player, "city", "move", "test_city");
        when(regions.operational(region)).thenReturn(true);
        invoke(command, player, "city", "info", "test_city");
        when(regions.cities()).thenReturn(java.util.List.of(new City(
                "test_city", "test_region", "test_world", 7.5, 70, 8.5, 2)));
        invoke(command, player, "city", "list");
        invoke(command, player, "city", "delete", "test_city");
        verify(regions).createCity("test_city", "test_region", 5.5, 70, 6.5);
        verify(regions).moveCity("test_city", "test_region", 7.5, 70, 8.5);
        verify(regions).deleteCity("test_city");
        verify(player, atLeastOnce()).sendMessage(contains("estado=activa"));
        verify(player, atLeastOnce()).sendMessage(contains("test_city@test_region"));
    }

    @Test
    void cityInfoShowsInactiveStateAndPersistedWorld() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionCommand command = new RegionCommand(regions, new RegionSelectionService(),
                mock(SelectionTool.class), messages);
        Player player = admin();
        when(regions.city("test_city")).thenReturn(Optional.of(new City(
                "test_city", "test_region", "test_world", 5.5, 70, 6.5, 3)));
        when(regions.region("test_region")).thenReturn(Optional.of(new Region(
                "test_region", "test_world", new Bounds(0, 60, 0, 10, 80, 10), false, 4)));
        invoke(command, player, "city", "info", "test_city");
        verify(player).sendMessage(contains("estado=inactiva"));
        verify(player).sendMessage(contains("mundo=test_world"));
        verify(player).sendMessage(contains("versión=3"));
    }

    @Test
    void tabCompletionExposesSelectionCommandsAndOnlyRelevantIds() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionCommand command = new RegionCommand(regions, new RegionSelectionService(),
                mock(SelectionTool.class), messages);
        Player player = admin();
        Command bukkitCommand = mock(Command.class);
        assertEquals(java.util.List.of("wand"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"region", "wa"}));
        assertEquals(java.util.List.of("preview"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"region", "pre"}));
        assertEquals(java.util.List.of("fullheight"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"region", "mode", "full"}));
        when(regions.regions()).thenReturn(java.util.List.of(
                new Region("test_region", "test_world", new Bounds(0, 60, 0, 10, 80, 10), false, 1)));
        assertEquals(java.util.List.of("test_region"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"region", "resize", "test"}));
        assertTrue(command.onTabComplete(player, bukkitCommand, "calo",
                new String[]{"region", "wand", "test"}).isEmpty());
        assertEquals(java.util.List.of("test_region"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"city", "create", "new_city", "test"}));
        when(regions.cities()).thenReturn(java.util.List.of(new City(
                "test_city", "test_region", "test_world", 5, 70, 5, 1)));
        assertEquals(java.util.List.of("test_city"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"city", "move", "test"}));
        assertEquals(java.util.List.of("city"), command.onTabComplete(
                player, bukkitCommand, "calo", new String[]{"ci"}));
        invoke(command, player, "help");
        verify(player, atLeastOnce()).sendMessage(contains("/calo city create"));
    }

    @Test
    void cityCannotBeCreatedFromAnotherWorld() throws Exception {
        RegionService regions = mock(RegionService.class);
        RegionCommand command = new RegionCommand(regions, new RegionSelectionService(),
                mock(SelectionTool.class), messages);
        Player player = admin();
        World world = mock(World.class);
        when(world.getName()).thenReturn("other_world");
        when(player.getLocation()).thenReturn(new Location(world, 5, 70, 5));
        when(regions.region("test_region")).thenReturn(Optional.of(
                new Region("test_region", "test_world", new Bounds(0, 60, 0, 10, 80, 10), true, 2)));
        invoke(command, player, "city", "create", "test_city", "test_region");
        verify(regions, never()).createCity(anyString(), anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    private static Player admin() {
        Player player = mock(Player.class);
        when(player.hasPermission("caloislands.admin")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static void invoke(RegionCommand handler, Player player, String... args) {
        handler.onCommand(player, mock(Command.class), "calo", args);
    }
}
