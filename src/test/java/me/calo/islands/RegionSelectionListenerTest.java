package me.calo.islands;

import me.calo.islands.core.Messages;
import me.calo.islands.core.SelectionTool;
import me.calo.islands.core.SelectionSource;
import me.calo.islands.domain.RegionSelectionService;
import me.calo.islands.listener.RegionSelectionListener;
import me.calo.islands.listener.SelectionSessionListener;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class RegionSelectionListenerTest {
    @Test
    void markedWandCapturesBothCornersAndCancelsBlockInteractions() {
        RegionSelectionService selections = new RegionSelectionService();
        SelectionTool tool = mock(SelectionTool.class);
        Messages messages = mock(Messages.class);
        RegionSelectionListener listener = new RegionSelectionListener(selections, tool, messages);
        ItemStack wand = mock(ItemStack.class);
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        when(tool.isWand(wand)).thenReturn(true);
        when(player.hasPermission("caloislands.admin")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(block.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("test_world");
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(70);
        when(block.getZ()).thenReturn(8);
        PlayerInteractEvent click = mock(PlayerInteractEvent.class);
        when(click.getItem()).thenReturn(wand);
        when(click.getPlayer()).thenReturn(player);
        when(click.getClickedBlock()).thenReturn(block);
        when(click.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_BLOCK);

        listener.onInteract(click);
        assertEquals(4, selections.get(playerId).orElseThrow().first().x());
        assertNull(selections.get(playerId).orElseThrow().second());
        listener.onInteract(click);
        assertTrue(selections.get(playerId).orElseThrow().complete());
        verify(click, times(2)).setCancelled(true);

        assertFalse(selections.togglePreview(playerId));
        new SelectionSessionListener(selections).onQuit(mockQuit(player));
        assertTrue(selections.get(playerId).isEmpty());
        assertTrue(selections.previewEnabled(playerId));
    }

    @Test
    void markedWandCannotDamageBreakOrPlaceBlocks() {
        SelectionTool tool = mock(SelectionTool.class);
        RegionSelectionListener listener = new RegionSelectionListener(
                new RegionSelectionService(), tool, mock(Messages.class));
        ItemStack wand = mock(ItemStack.class);
        when(tool.isWand(wand)).thenReturn(true);
        BlockDamageEvent damage = mock(BlockDamageEvent.class);
        when(damage.getItemInHand()).thenReturn(wand);
        listener.onDamage(damage);
        verify(damage).setCancelled(true);

        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(breaking.getPlayer()).thenReturn(player);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(wand);
        listener.onBreak(breaking);
        verify(breaking).setCancelled(true);

        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getItemInHand()).thenReturn(wand);
        listener.onPlace(placing);
        verify(placing).setCancelled(true);
    }

    @Test
    void quitClearsWorldEditAndTemporaryMode() {
        RegionSelectionService selections = new RegionSelectionService();
        SelectionSource source = mock(SelectionSource.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        selections.setMode(id, RegionSelectionService.Mode.EXACT);
        new SelectionSessionListener(selections, source).onQuit(mockQuit(player));
        verify(source).clear(player);
        assertEquals(RegionSelectionService.Mode.FULLHEIGHT, selections.mode(id));
    }

    private static PlayerQuitEvent mockQuit(Player player) {
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        return quit;
    }
}
