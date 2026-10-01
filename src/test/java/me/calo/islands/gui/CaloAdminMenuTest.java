package me.calo.islands.gui;
import me.calo.islands.command.RegionCommand;
import me.calo.islands.core.*;
import me.calo.islands.data.RegionStore;
import me.calo.islands.domain.*;
import org.bukkit.*;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
final class CaloAdminMenuTest {
    private final RegionService regions = mock(RegionService.class);
    private final RegionSelectionService selections = new RegionSelectionService();
    private final SelectionSource source = mock(SelectionSource.class);
    private final RegionPreviewService previews = mock(RegionPreviewService.class);
    private final World world = mock(World.class);
    private final AtomicReference<Region> r = new AtomicReference<>(new Region("region_a", "world_a", new Bounds(0, 60, 0, 10, 80, 10), true, 1));
    private final AtomicReference<City> c = new AtomicReference<>(new City("city_a", "region_a", "world_a", 1.5, 64, 1.5, 1));
    private CaloAdminMenu menu(AdminUiTest.Fixture f) throws Exception {
        when(regions.regions()).thenAnswer(call -> r.get() == null ? List.of() : List.of(r.get()));
        when(regions.region("region_a")).thenAnswer(call -> Optional.ofNullable(r.get()));
        when(regions.operational(any())).thenAnswer(call -> call.getArgument(0, Region.class).active());
        when(regions.cities()).thenAnswer(call -> c.get() == null ? List.of() : List.of(c.get()));
        when(regions.city("city_a")).thenAnswer(call -> Optional.ofNullable(c.get()));
        when(world.getName()).thenReturn("world_a"); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(f.player.getLocation()).thenReturn(new Location(world, 5.5, 64, 5.5)); f.bukkit.when(() -> Bukkit.getWorld("world_a")).thenReturn(world);
        var messages = new Messages(new File("src/main/resources/messages.yml"));
        var command = new RegionCommand(regions, selections, mock(SelectionTool.class), messages, source);
        return new CaloAdminMenu(f.plugin, f.ui, regions, selections, source, previews, command, mock(AdminTeleportService.class), mock(RegionStore.class));
    }
    private void click(AdminUiTest.Fixture f, int slot) throws Exception { f.ui.click(f.click(slot, ClickType.LEFT)); f.drain(); if (f.title() != null) f.awaitQuery(); }
    @Test void regionWithoutDestinationHasDisabledTravelAndPointButtonsUseCapturedPlayerLocation() throws Exception {
        try(var f=new AdminUiTest.Fixture()) {
            var menu=menu(f); menu.open(f.player); click(f,10); click(f,10);
            assertEquals("§7Ir a región",f.top.get().getItem(10).getItemMeta().getDisplayName());
            click(f,10); verify(regions,never()).setRegionPoint(anyString(),any());
            click(f,31); verify(regions).setRegionPoint("region_a",new Destination("world_a",5.5,64,5.5,0,0));
            Region old=r.get(); r.set(new Region(old.id(),old.world(),old.bounds(),old.active(),2,new Destination("world_a",5.5,64,5.5,0,0)));
            menu.regionList(f.player,0); click(f,10); assertEquals("§6Ir a región",f.top.get().getItem(10).getItemMeta().getDisplayName());
            click(f,32); verify(regions).setRegionPoint("region_a",null);
            var close=mock(org.bukkit.event.inventory.InventoryCloseEvent.class); when(close.getInventory()).thenReturn(f.top.get()); when(close.getPlayer()).thenReturn(f.player);
            menu.close(close); verify(previews).stop(f.player.getUniqueId());
            var quit=mock(org.bukkit.event.player.PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(f.player); menu.quit(quit);
            verify(previews,times(2)).stop(f.player.getUniqueId());
        }
    }

    @Test void regionStateDeletionConfirmationAndReopeningUseServiceExactlyOnce() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f); menu.open(f.player); assertEquals("§6Regiones", f.top.get().getItem(10).getItemMeta().getDisplayName());
            when(regions.setActive("region_a", false)).thenAnswer(call -> { Region old = r.get(); Region next = new Region(old.id(), old.world(), old.bounds(), false, 2); r.set(next); return next; });
            click(f, 10); assertTrue(f.title().contains("Regiones")); click(f, 10); click(f, 12); click(f, 30);
            verify(regions).setActive("region_a", false); assertEquals("§6Activar", f.top.get().getItem(12).getItemMeta().getDisplayName());
            click(f, 34); click(f, 32); verify(regions, never()).deleteRegion(anyString());
            doAnswer(call -> { r.set(null); return null; }).when(regions).deleteRegion("region_a");
            click(f, 34); var confirm = f.click(30, ClickType.LEFT); f.ui.click(confirm); f.ui.click(confirm); f.drain();
            verify(regions, times(1)).deleteRegion("region_a"); assertTrue(f.title().contains("Regiones"));
            click(f, 49); menu.open(f.player); assertTrue(f.title().contains("Administración"));
        }
    }
    @Test void cityListMoveAndDeletePreserveCapturedLocationAndRequireConfirmation() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f); menu.open(f.player); click(f, 12); assertTrue(f.title().contains("Ciudades")); click(f, 10);
            when(regions.moveCity("city_a", "region_a", 5.5, 64, 5.5, 0, 0)).thenAnswer(call -> { City next = new City("city_a", "region_a", "world_a", 5.5, 64, 5.5, 2); c.set(next); return next; });
            click(f, 13); verify(regions, never()).moveCity(anyString(), anyString(), anyDouble(), anyDouble(), anyDouble());
            click(f, 30); verify(regions).moveCity("city_a", "region_a", 5.5, 64, 5.5, 0, 0); click(f, 10);
            click(f, 16); click(f, 32); verify(regions, never()).deleteCity(anyString());
            doAnswer(call -> { c.set(null); return null; }).when(regions).deleteCity("city_a");
            click(f, 16); var confirm = f.click(30, ClickType.LEFT); f.ui.click(confirm); f.ui.click(confirm); f.drain(); f.awaitQuery();
            verify(regions, times(1)).deleteCity("city_a"); assertTrue(f.title().contains("Ciudades"));
        }
    }
    @Test void cityDetailReturnsToSameFilteredPageAndUsesFriendlyName() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f);
            List<City> cities = new ArrayList<>();
            for (int i = 0; i < 22; i++) cities.add(new City("city_" + String.format(Locale.ROOT, "%02d", i),
                    "region_a", "world_a", 1.5, 64, 1.5, 1));
            when(regions.cities()).thenReturn(cities);
            menu.open(f.player); click(f, 10); click(f, 10); click(f, 16);
            click(f, 53);
            assertTrue(f.title().contains("Ciudades"));
            assertEquals("§6City 21", f.top.get().getItem(10).getItemMeta().getDisplayName());
            click(f, 10);
            assertTrue(f.title().contains("Ciudad · City 21"));
            click(f, 28); assertTrue(f.title().contains("Región · Region A"));
            click(f, 48); assertTrue(f.title().contains("Ciudad · City 21"));
            click(f, 48);
            assertTrue(f.title().contains("Ciudades · Region A"));
            assertEquals("§6Página 2", f.top.get().getItem(4).getItemMeta().getDisplayName());
            assertEquals("§6City 21", f.top.get().getItem(10).getItemMeta().getDisplayName());
        }
    }
    @Test void worldEditSelectionModesAndPersonalPreviewDoNotReplaceOrClearSelection() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f);
            var selected = new RegionSelectionService.Selection(new RegionSelectionService.Position("world_a", 0, 60, 0), new RegionSelectionService.Position("world_a", 10, 80, 10));
            when(source.selection(f.player)).thenReturn(selected); menu.open(f.player); click(f, 14); assertTrue(f.title().contains("Selección"));
            click(f, 12); assertEquals(RegionSelectionService.Mode.EXACT, selections.mode(f.player.getUniqueId()));
            click(f, 10); assertEquals(RegionSelectionService.Mode.FULLHEIGHT, selections.mode(f.player.getUniqueId()));
            click(f, 16); click(f, 10); assertTrue(selections.previewEnabled(f.player.getUniqueId())); verify(source, never()).clear(any());
            verify(previews).startSelection(f.player.getUniqueId()); assertSame(selected, source.selection(f.player));
        }
    }
    @Test void regionChangedBeforeConfirmationCannotBeDeleted() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f); menu.open(f.player); click(f, 10); click(f, 10); click(f, 34);
            Region original = r.get(); r.set(new Region(original.id(), original.world(), original.bounds(), false, 2)); click(f, 30);
            verify(regions, never()).deleteRegion(anyString()); verify(f.player).sendMessage(contains("cambió"));
        }
    }
    @Test void createCityUsesTheLocationReviewedInConfirmation() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f); menu.open(f.player); click(f, 10); click(f, 10); click(f, 30); f.chat("city_new");
            when(f.player.getLocation()).thenReturn(new Location(world, 8.5, 70, 8.5));
            verify(regions, never()).createCity(anyString(), anyString(), anyDouble(), anyDouble(), anyDouble()); click(f, 30);
            verify(regions).createCity("city_new", "region_a", 5.5, 64, 5.5, 0, 0);
        }
    }
    @Test void createRegionUsesExactWorldEditBoundsAndOnlyThenClearsSelection() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            var menu = menu(f); var selected = new RegionSelectionService.Selection(
                    new RegionSelectionService.Position("world_a", 0, 60, 0), new RegionSelectionService.Position("world_a", 10, 80, 10));
            when(source.selection(f.player)).thenReturn(selected); Bounds bounds = selected.bounds();
            when(regions.createRegion("region_new", "world_a", bounds)).thenReturn(new Region("region_new", "world_a", bounds, false, 1));
            menu.open(f.player); click(f, 14); click(f, 12); click(f, 28); f.chat("region_new");
            verify(source, never()).clear(any()); click(f, 30); verify(regions).createRegion("region_new", "world_a", bounds); verify(source).clear(f.player);
        }
    }
}
