package me.calo.islands.listener;

import me.calo.islands.core.Messages;
import me.calo.islands.core.SelectionTool;
import me.calo.islands.domain.RegionSelectionService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;

/** Captures block clicks and prevents the marked wand from changing blocks. */
public final class RegionSelectionListener implements Listener {
    private final RegionSelectionService selections;
    private final SelectionTool tool;
    private final Messages messages;

    public RegionSelectionListener(RegionSelectionService selections, SelectionTool tool, Messages messages) {
        this.selections = selections;
        this.tool = tool;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!tool.isWand(event.getItem())) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("caloislands.admin")) {
            messages.send(player, "no-permission");
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (action == Action.LEFT_CLICK_BLOCK) {
            selections.setFirst(player.getUniqueId(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            messages.send(player, "selection-first", "world", block.getWorld().getName(),
                    "x", block.getX(), "y", block.getY(), "z", block.getZ());
        } else {
            selections.setSecond(player.getUniqueId(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            messages.send(player, "selection-second", "world", block.getWorld().getName(),
                    "x", block.getX(), "y", block.getY(), "z", block.getZ());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(BlockDamageEvent event) {
        if (tool.isWand(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (tool.isWand(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (tool.isWand(event.getItemInHand())) event.setCancelled(true);
    }

}
