package me.calo.islands.listener;

import me.calo.islands.core.ProtectionSettings.Action;
import me.calo.islands.content.ObjectiveActivityService;
import me.calo.islands.content.ObjectiveSettings;
import me.calo.islands.domain.RegionProtectionPolicy;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;

import java.util.List;

/** Cancels only CaloIslands restrictions; never un-cancels WorldGuard or Lands decisions. */
public final class RegionProtectionListener implements Listener {
    private final RegionProtectionPolicy policy;
    private final ObjectiveActivityService objectives;

    public RegionProtectionListener(RegionProtectionPolicy policy) { this(policy, null); }
    public RegionProtectionListener(RegionProtectionPolicy policy, ObjectiveActivityService objectives) {
        this.policy = policy;
        this.objectives = objectives;
    }

    private boolean deniesPlayer(Player actor, Action action, Location location) {
        return policy.denies(actor, action, location);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.BLOCK_BREAK, event.getBlock().getLocation())
                && (objectives == null || !objectives.allowsBlockAction(event.getPlayer(), event.getBlock(),
                        ObjectiveSettings.Mode.COLLECTION))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        if (deniesPlayer(event.getPlayer(), Action.BLOCK_PLACE, event.getBlockPlaced().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (event.isCancelled()) return;
        if (event.getReplacedBlockStates().stream().anyMatch(state ->
                deniesPlayer(event.getPlayer(), Action.BLOCK_PLACE, state.getLocation()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent event) {
        if (policy.denies(Action.REDSTONE, event.getBlock().getLocation()))
            event.setNewCurrent(event.getOldCurrent());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Action action = clicked.getState() instanceof Container ? Action.CONTAINER : Action.BLOCK_INTERACT;
        if (deniesPlayer(event.getPlayer(), action, clicked.getLocation())
                && (objectives == null || action != Action.BLOCK_INTERACT
                    || !objectives.allowsBlockAction(event.getPlayer(), clicked,
                            ObjectiveSettings.Mode.STRUCTURE))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location at = event.getInventory().getLocation();
        if (at != null && deniesPlayer(player, Action.CONTAINER, at)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Location at = event.getView().getTopInventory().getLocation();
        if (at != null && deniesPlayer(player, Action.CONTAINER, at)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Location at = event.getView().getTopInventory().getLocation();
        if (at != null && deniesPlayer(player, Action.CONTAINER, at)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location from = event.getSource().getLocation();
        Location to = event.getDestination().getLocation();
        if ((from != null && policy.denies(Action.CONTAINER, from))
                || (to != null && policy.denies(Action.CONTAINER, to))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player actor = event.getRemover() instanceof Player player ? player : null;
        if (deniesPlayer(actor, Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.ENTITY_INTERACT, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.BUCKET,
                event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.BUCKET, event.getBlockClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (!event.getBlock().isLiquid()) return;
        if (policy.denies(Action.FLUID_FLOW, event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block source = event.getBlock();
        if (policy.denies(Action.DISPENSE, source.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (source.getBlockData() instanceof org.bukkit.block.data.Directional directional
                && policy.denies(Action.DISPENSE,
                        source.getRelative(directional.getFacing()).getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (deniesPlayer(event.getPlayer(), Action.FIRE, event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (policy.denies(Action.FIRE, event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if ((event.getSource().getType() == org.bukkit.Material.FIRE
                || event.getSource().getType() == org.bukkit.Material.SOUL_FIRE)
                && policy.denies(Action.FIRE, event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { removeProtectedExplosionBlocks(event.blockList()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { removeProtectedExplosionBlocks(event.blockList()); }

    private void removeProtectedExplosionBlocks(List<Block> blocks) {
        blocks.removeIf(block -> policy.denies(Action.EXPLOSION, block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonDenied(event.getBlock(), event.getBlocks(), event.getDirection(), event.getDirection()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonDenied(event.getBlock(), event.getBlocks(), event.getDirection(),
                event.getDirection().getOppositeFace())) event.setCancelled(true);
    }

    private boolean pistonDenied(Block piston, List<Block> moved, org.bukkit.block.BlockFace facing,
                                 org.bukkit.block.BlockFace movement) {
        if (policy.denies(Action.PISTON, piston.getLocation())
                || policy.denies(Action.PISTON, piston.getRelative(facing).getLocation())) return true;
        for (Block block : moved) {
            if (policy.denies(Action.PISTON, block.getLocation())
                    || policy.denies(Action.PISTON, block.getRelative(movement).getLocation())) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Location victim = event.getEntity().getLocation();
        Player actor = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile
                        && projectile.getShooter() instanceof Player player ? player : null;
        boolean playerAttacker = actor != null;
        Action action = event.getEntity() instanceof Player && playerAttacker
                ? Action.PVP_DAMAGE : Action.PVE_DAMAGE;
        if (deniesPlayer(actor, action, victim)
                && (objectives == null || action != Action.PVE_DAMAGE
                    || !objectives.allowsMobCombat(event.getDamager(), event.getEntity())))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        if (policy.denies(Action.EXPLOSION, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (policy.denies(Action.ENTITY_SPAWN, event.getLocation())
                && (objectives == null || !objectives.allowsInternalSpawn(event.getLocation())))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (policy.denies(Action.ENTITY_BLOCK_CHANGE, event.getBlock().getLocation())) event.setCancelled(true);
    }
}
