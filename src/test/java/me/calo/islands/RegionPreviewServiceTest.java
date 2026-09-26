package me.calo.islands;

import me.calo.islands.core.Messages;
import me.calo.islands.core.PreviewSettings;
import me.calo.islands.core.RegionPreviewService;
import me.calo.islands.domain.RegionSelectionService;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class RegionPreviewServiceTest {
    @Test
    void worldEditAndIndependentRegionPreviewStayPersonalAndDoNotMutateSelection() {
        RegionSelectionService selections = new RegionSelectionService();
        me.calo.islands.core.SelectionSource source = mock(me.calo.islands.core.SelectionSource.class);
        RegionPreviewService preview = new RegionPreviewService(selections,
                new PreviewSettings(Particle.END_ROD, Particle.FLAME, 10, 192),
                new Messages(new File("src/main/resources/messages.yml")), source);
        Player admin = mock(Player.class), other = mock(Player.class); World world = mock(World.class);
        UUID id = UUID.randomUUID(); when(admin.getUniqueId()).thenReturn(id); when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        when(admin.getWorld()).thenReturn(world); when(world.getName()).thenReturn("test_world"); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        var selection = new RegionSelectionService.Selection(new RegionSelectionService.Position("test_world", 0, 60, 0), new RegionSelectionService.Position("test_world", 10, 80, 10));
        when(source.selection(admin)).thenReturn(selection); preview.renderFrame(List.of(admin, other)); verify(admin).sendActionBar(contains("fullheight"));
        clearInvocations(admin); preview.region(id, new me.calo.islands.domain.Region("preview_region", "test_world", new me.calo.islands.domain.Bounds(2, 70, 2, 5, 75, 5), false, 1));
        preview.renderFrame(List.of(admin, other)); verify(admin).sendActionBar(contains("4x6x4"));
        verify(source, times(1)).selection(admin); verify(source, never()).clear(any());
        org.junit.jupiter.api.Assertions.assertTrue(selections.get(id).isEmpty()); verify(other, never()).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt());
        preview.clear(id); clearInvocations(admin); preview.renderFrame(List.of(admin)); verify(admin).sendActionBar(contains("fullheight"));
    }
    @Test
    void rendersOnlyForSelectingAdminWithoutWorldBlockOrDatabaseAccess() {
        RegionSelectionService selections = new RegionSelectionService();
        Messages messages = new Messages(new File("src/main/resources/messages.yml"));
        RegionPreviewService preview = new RegionPreviewService(selections,
                new PreviewSettings(Particle.END_ROD, Particle.FLAME, 10, 192), messages);
        Player admin = mock(Player.class);
        Player other = mock(Player.class);
        World world = mock(World.class);
        UUID adminId = UUID.randomUUID();
        when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        when(other.hasPermission("caloislands.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("test_world");
        when(world.getMinHeight()).thenReturn(-40);
        when(world.getMaxHeight()).thenReturn(200);
        selections.setFirst(adminId, "test_world", 0, 60, 0);
        selections.setSecond(adminId, "test_world", 10, 80, 10);

        preview.renderFrame(List.of(admin, other));
        verify(admin, times(180)).spawnParticle(eq(Particle.END_ROD), anyDouble(), anyDouble(), anyDouble(), eq(1));
        verify(admin, atLeastOnce()).spawnParticle(eq(Particle.END_ROD), anyDouble(), eq(-40.0), anyDouble(), eq(1));
        verify(admin, atLeastOnce()).spawnParticle(eq(Particle.END_ROD), anyDouble(), eq(200.0), anyDouble(), eq(1));
        verify(admin, times(2)).spawnParticle(eq(Particle.FLAME), anyDouble(), anyDouble(), anyDouble(), eq(3));
        verify(admin).spawnParticle(Particle.FLAME, 0.5, 60.5, 0.5, 3);
        verify(admin).spawnParticle(Particle.FLAME, 10.5, 80.5, 10.5, 3);
        verify(admin).sendActionBar(org.mockito.ArgumentMatchers.<String>argThat(message -> message.contains("test_world")
                && message.contains("0,60,0") && message.contains("10,80,10")
                && message.contains("11x240x11")));
        verify(other, never()).sendActionBar(anyString());
        verify(world).getName();
        verify(world).getMinHeight();
        verify(world).getMaxHeight();
        verifyNoMoreInteractions(world); // No getBlockAt, setType, or other world mutation.

        clearInvocations(admin);
        selections.setMode(adminId, RegionSelectionService.Mode.EXACT);
        preview.renderFrame(List.of(admin));
        verify(admin).sendActionBar(org.mockito.ArgumentMatchers.<String>argThat(message -> message.contains("exact")
                && message.contains("11x21x11")));
        verify(admin, atLeastOnce()).spawnParticle(eq(Particle.END_ROD), anyDouble(), eq(60.0), anyDouble(), eq(1));
        verify(admin, atLeastOnce()).spawnParticle(eq(Particle.END_ROD), anyDouble(), eq(81.0), anyDouble(), eq(1));

        clearInvocations(admin);
        selections.togglePreview(adminId);
        preview.renderFrame(List.of(admin));
        verify(admin, never()).sendActionBar(anyString());
        selections.clear(adminId);
        preview.renderFrame(List.of(admin));
        verify(admin, never()).sendActionBar(anyString());
        selections.setFirst(adminId, "other_world", 0, 60, 0);
        selections.setSecond(adminId, "other_world", 10, 80, 10);
        preview.renderFrame(List.of(admin));
        verify(admin, never()).sendActionBar(anyString());
    }
}
