package me.calo.islands;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.SessionManager;
import me.calo.islands.core.ProtectionSettings.Action;
import me.calo.islands.integration.WorldGuardProtection;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class WorldGuardProtectionTest {
    @Test
    void readOnlyBuildQueryCanDenyAdminAction() {
        WorldGuard guard = mock(WorldGuard.class);
        WorldGuardPlugin plugin = mock(WorldGuardPlugin.class);
        WorldGuardPlatform platform = mock(WorldGuardPlatform.class);
        RegionContainer container = mock(RegionContainer.class);
        RegionQuery query = mock(RegionQuery.class);
        SessionManager sessions = mock(SessionManager.class);
        Player player = mock(Player.class);
        LocalPlayer local = mock(LocalPlayer.class);
        World bukkitWorld = mock(World.class);
        Location at = new Location(bukkitWorld, 5, 70, 5);
        com.sk89q.worldedit.util.Location converted = mock(com.sk89q.worldedit.util.Location.class);
        when(guard.getPlatform()).thenReturn(platform);
        when(platform.getRegionContainer()).thenReturn(container);
        when(platform.getSessionManager()).thenReturn(sessions);
        when(container.createQuery()).thenReturn(query);
        when(plugin.wrapPlayer(player)).thenReturn(local);
        try (MockedStatic<WorldGuard> wg = mockStatic(WorldGuard.class);
             MockedStatic<WorldGuardPlugin> wgp = mockStatic(WorldGuardPlugin.class);
             MockedStatic<BukkitAdapter> adapter = mockStatic(BukkitAdapter.class)) {
            wg.when(WorldGuard::getInstance).thenReturn(guard);
            wgp.when(WorldGuardPlugin::inst).thenReturn(plugin);
            adapter.when(() -> BukkitAdapter.adapt(at)).thenReturn(converted);
            var adaptedWorld = mock(com.sk89q.worldedit.world.World.class);
            adapter.when(() -> BukkitAdapter.adapt(bukkitWorld)).thenReturn(adaptedWorld);
            var bridge = new WorldGuardProtection();
            assertTrue(bridge.denies(player, Action.BLOCK_BREAK, at));
            assertFalse(bridge.denies(null, Action.BLOCK_BREAK, at));
            verify(query).testBuild(eq(converted), eq(local), any(com.sk89q.worldguard.protection.flags.StateFlag[].class));
            when(sessions.hasBypass(local, adaptedWorld)).thenReturn(true);
            assertFalse(bridge.denies(player, Action.BLOCK_BREAK, at));
            verifyNoMoreInteractions(query);
        }
    }
}
