package me.calo.islands;

import me.calo.islands.integration.OptionalIntegration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class OptionalIntegrationTest {
    @Test
    void absentAndDisabledPluginsNeverLoadApiClasses() {
        PluginManager manager = mock(PluginManager.class);
        assertEquals(OptionalIntegration.Status.ABSENT,
                OptionalIntegration.load(manager, "WorldGuard", () -> { throw new AssertionError(); }).status());
        Plugin plugin = mock(Plugin.class);
        PluginDescriptionFile description = mock(PluginDescriptionFile.class);
        when(description.getVersion()).thenReturn("test");
        when(plugin.getDescription()).thenReturn(description);
        when(manager.getPlugin("WorldGuard")).thenReturn(plugin);
        assertEquals(OptionalIntegration.Status.DISABLED,
                OptionalIntegration.load(manager, "WorldGuard", () -> { throw new AssertionError(); }).status());
    }

    @Test
    void enabledPluginReportsLoadedOrIncompatible() {
        PluginManager manager = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        PluginDescriptionFile description = mock(PluginDescriptionFile.class);
        when(description.getVersion()).thenReturn("test");
        when(plugin.getDescription()).thenReturn(description);
        when(plugin.isEnabled()).thenReturn(true);
        when(manager.getPlugin("Lands")).thenReturn(plugin);
        var loaded = OptionalIntegration.load(manager, "Lands", () -> "adapter");
        assertEquals(OptionalIntegration.Status.LOADED, loaded.status());
        assertEquals("adapter", loaded.adapter());
        var incompatible = OptionalIntegration.load(manager, "Lands", () -> {
            throw new NoClassDefFoundError("old API");
        });
        assertEquals(OptionalIntegration.Status.INCOMPATIBLE, incompatible.status());
        assertNull(incompatible.adapter());
    }
}
