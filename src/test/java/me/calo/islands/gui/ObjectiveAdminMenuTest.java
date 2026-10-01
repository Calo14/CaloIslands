package me.calo.islands.gui;

import me.calo.islands.content.*;
import me.calo.islands.domain.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class ObjectiveAdminMenuTest {
    private static void click(AdminUiTest.Fixture f, int slot) {
        f.ui.click(f.click(slot, ClickType.LEFT)); f.drain();
    }
    @Test void createFormValidatesInputAndConfirmsOnceBeforeSaving() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            ObjectiveCatalog catalog = mock(ObjectiveCatalog.class);
            ObjectiveActivityService runtime = mock(ObjectiveActivityService.class);
            RegionService regions = mock(RegionService.class);
            World world = mock(World.class); when(world.getName()).thenReturn("world");
            when(f.player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
            Destination start = new Destination("world",5,64,5,0,0);
            Region region = new Region("coast","world",new Bounds(0,0,0,20,100,20),true,1,start);
            when(regions.regions()).thenReturn(List.of(region));
            when(regions.region("coast")).thenReturn(Optional.of(region));
            AtomicReference<ManagedObjective> saved = new AtomicReference<>();
            when(catalog.list()).thenAnswer(call -> saved.get()==null ? List.of() : List.of(saved.get()));
            when(catalog.require("defense_a")).thenAnswer(call -> saved.get());
            when(runtime.current("defense_a")).thenReturn(Optional.empty());
            when(catalog.create(eq(f.player),any(),eq(start))).thenAnswer(call -> {
                ManagedObjective value = new ManagedObjective(call.getArgument(1),start,false,false,1);
                saved.set(value); return value;
            });
            ObjectiveAdminMenu menu = new ObjectiveAdminMenu(f.ui,catalog,runtime,regions,p -> {});
            menu.list(f.player,0); assertTrue(f.title().contains("Actividades"));
            click(f,40); assertTrue(f.title().contains("Tipo")); click(f,10);
            assertTrue(f.title().contains("Editar"));
            click(f,10); f.chat("defense_a");
            click(f,11); f.chat("Defensa del faro");
            click(f,13); click(f,10);
            click(f,14);
            click(f,19); f.chat("0");
            assertTrue(f.title()==null || !f.title().contains("Editar"), "Invalid value keeps chat prompt open");
            f.chat("5");
            click(f,20); f.chat("120");
            click(f,30); assertTrue(f.title().contains("Guardar"));
            verify(catalog,never()).create(any(),any(),any());
            var confirm=f.click(30,ClickType.LEFT);
            f.ui.click(confirm);f.ui.click(confirm);f.drain();
            verify(catalog,times(1)).create(eq(f.player),argThat(s -> s.id().equals("defense_a")
                    && s.mode()==ObjectiveSettings.Mode.DEFENSE && s.goal()==5 && s.timeoutSeconds()==120),eq(start));
            assertTrue(f.title().contains("Actividad"));
            menu.quit(f.player.getUniqueId());
        }
    }
    @Test void stateDeleteAndRunningCancellationUseConfirmedCatalogOperations() throws Exception {
        try (var f = new AdminUiTest.Fixture()) {
            ObjectiveCatalog catalog = mock(ObjectiveCatalog.class);
            ObjectiveActivityService runtime = mock(ObjectiveActivityService.class);
            RegionService regions = mock(RegionService.class);
            ObjectiveSettings settings = new ObjectiveSettings("defense_a", "Defensa del faro",
                    ObjectiveSettings.Mode.DEFENSE, "coast", 8, 5, 0, 1, 5, 120,
                    null, null, List.of(), 0);
            Destination point = new Destination("world",5,64,5,0,0);
            AtomicReference<ManagedObjective> value = new AtomicReference<>(new ManagedObjective(settings,point,false,false,1));
            when(catalog.list()).thenAnswer(call -> List.of(value.get()));
            when(catalog.require("defense_a")).thenAnswer(call -> value.get());
            when(catalog.setEnabled(f.player,"defense_a",1,true)).thenAnswer(call -> {
                ManagedObjective next=value.get().next(settings,point,true,false); value.set(next); return next;
            });
            when(runtime.current("defense_a")).thenReturn(Optional.empty());
            ObjectiveAdminMenu menu=new ObjectiveAdminMenu(f.ui,catalog,runtime,regions,p -> {});
            menu.list(f.player,0); click(f,10); click(f,12);
            verify(catalog).setEnabled(f.player,"defense_a",1,true);
            click(f,14); click(f,32); verify(catalog,never()).delete(any(),anyString(),anyLong());
            click(f,14); click(f,30); verify(catalog).delete(f.player,"defense_a",2);
            ActivityRun run=mock(ActivityRun.class);
            when(run.runId()).thenReturn(java.util.UUID.randomUUID());
            when(runtime.current("defense_a")).thenReturn(Optional.of(run));
            when(runtime.members(run.runId())).thenReturn(List.of());
            menu.list(f.player,0);click(f,10);click(f,32);click(f,30);
            verify(catalog).cancel(f.player,"defense_a");
        }
    }
}
