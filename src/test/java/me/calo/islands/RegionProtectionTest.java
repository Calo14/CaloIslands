package me.calo.islands;

import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.core.ProtectionSettings.Action;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionProtectionPolicy;
import me.calo.islands.domain.RegionService;
import me.calo.islands.listener.RegionProtectionListener;
import me.calo.islands.integration.ExternalProtection;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.block.BlockState;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.block.BlockFace;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class RegionProtectionTest {
    @Test
    void normalPlayerCannotBreakOrPlaceInActiveRegionFromBundledConfig() {
        var config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        ProtectionSettings settings = ProtectionSettings.read(config.getConfigurationSection("protection"));
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location inside = new Location(world, 5, 10, 5);
        when(regions.matchingAt("test_world", 5, 10, 5)).thenReturn(List.of(new Region(
                "active", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 1)));
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(inside);
        Player player = mock(Player.class);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getBlock()).thenReturn(block);
        when(breaking.getPlayer()).thenReturn(player);
        new RegionProtectionListener(new RegionProtectionPolicy(regions, settings)).onBreak(breaking);
        verify(breaking).setCancelled(true);
        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getBlockPlaced()).thenReturn(block);
        when(placing.getPlayer()).thenReturn(player);
        new RegionProtectionListener(new RegionProtectionPolicy(regions, settings)).onPlace(placing);
        verify(placing).setCancelled(true);
        Player admin = mock(Player.class);
        when(admin.hasPermission(RegionProtectionPolicy.BYPASS_PERMISSION)).thenReturn(true);
        BlockBreakEvent adminBreak = mock(BlockBreakEvent.class);
        when(adminBreak.getBlock()).thenReturn(block);
        when(adminBreak.getPlayer()).thenReturn(admin);
        new RegionProtectionListener(new RegionProtectionPolicy(regions, settings)).onBreak(adminBreak);
        verify(adminBreak, never()).setCancelled(true);
    }

    @Test
    void defaultsProtectActiveAndInactiveAndValidateConfiguredActions() {
        var defaults = ProtectionSettings.read(null);
        assertTrue(defaults.inactive().containsAll(java.util.EnumSet.allOf(Action.class)));
        assertTrue(defaults.active().containsAll(java.util.EnumSet.allOf(Action.class)));
        assertFalse(defaults.denyActiveEntry());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("protection.policy-version", 2);
        yaml.set("protection.inactive", List.of("BLOCK_BREAK", "EXPLOSION"));
        yaml.set("protection.active", List.of("CONTAINER"));
        yaml.set("protection.deny-active-entry", true);
        var configured = ProtectionSettings.read(yaml.getConfigurationSection("protection"));
        assertEquals(java.util.Set.of(Action.BLOCK_BREAK, Action.EXPLOSION, Action.REDSTONE, Action.DISPENSE), configured.inactive());
        assertEquals(java.util.Set.of(Action.CONTAINER, Action.REDSTONE, Action.DISPENSE), configured.active());
        assertTrue(configured.denyActiveEntry());
        yaml.set("protection.policy-version", 3);
        assertEquals(java.util.Set.of(Action.CONTAINER), ProtectionSettings.read(
                yaml.getConfigurationSection("protection")).active());
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("protection.active", List.of());
        YamlConfiguration bundledDefaults = new YamlConfiguration();
        bundledDefaults.set("protection.policy-version", 2);
        legacy.setDefaults(bundledDefaults);
        assertTrue(ProtectionSettings.read(legacy.getConfigurationSection("protection"))
                .active().contains(Action.BLOCK_BREAK));
        yaml.set("protection.active", List.of("UNKNOWN"));
        assertThrows(IllegalArgumentException.class,
                () -> ProtectionSettings.read(yaml.getConfigurationSection("protection")));
        yaml.set("protection.active", List.of());
        yaml.set("protection.deny-active-entry", "yes");
        assertThrows(IllegalArgumentException.class,
                () -> ProtectionSettings.read(yaml.getConfigurationSection("protection")));
    }

    @Test
    void blockAndExplosionRulesUseSnapshotWithoutWorldMutationOrUncancel() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Region inactive = new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), false, 1);
        when(regions.matchingAt(eq("test_world"), anyDouble(), anyDouble(), anyDouble())).thenAnswer(call ->
                inactive.bounds().contains(call.getArgument(1), call.getArgument(2), call.getArgument(3))
                        ? List.of(inactive) : List.of());
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(regions, ProtectionSettings.read(null)));
        Block inside = mock(Block.class);
        Block outside = mock(Block.class);
        when(inside.getLocation()).thenReturn(new Location(world, 5, 10, 5));
        when(outside.getLocation()).thenReturn(new Location(world, 50, 10, 50));
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getBlock()).thenReturn(inside);
        listener.onBreak(breaking);
        verify(breaking).setCancelled(true);
        verify(breaking, never()).setCancelled(false);

        BlockExplodeEvent exploding = mock(BlockExplodeEvent.class);
        List<Block> affected = new ArrayList<>(List.of(inside, outside));
        when(exploding.blockList()).thenReturn(affected);
        listener.onBlockExplode(exploding);
        assertEquals(List.of(outside), affected);
        verify(exploding, never()).setCancelled(anyBoolean());
        verify(world, atLeastOnce()).getName();
        verifyNoMoreInteractions(world); // no blocks changed and no storage call
    }

    @Test
    void activeRestrictionsDenyByDefaultAndCanBeConfigured() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        when(regions.matchingAt(eq("test_world"), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(
                new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 2)));
        Location at = new Location(world, 5, 10, 5);
        assertTrue(new RegionProtectionPolicy(regions, ProtectionSettings.read(null)).denies(Action.BLOCK_BREAK, at));
        assertFalse(new RegionProtectionPolicy(regions,
                new ProtectionSettings(java.util.Set.of(), java.util.Set.of()))
                .denies(Action.BLOCK_BREAK, at));
        Player normal = mock(Player.class);
        Player admin = mock(Player.class);
        when(admin.hasPermission(RegionProtectionPolicy.BYPASS_PERMISSION)).thenReturn(true);
        RegionProtectionPolicy policy = new RegionProtectionPolicy(regions, ProtectionSettings.read(null));
        assertTrue(policy.denies(normal, Action.BLOCK_BREAK, at));
        assertFalse(policy.denies(admin, Action.BLOCK_BREAK, at));
    }

    @Test
    void bypassSkipsCaloChecksWhileCacheFailureStillFailsClosed() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location inside = new Location(world, 5, 10, 5);
        Location outside = new Location(world, 50, 10, 50);
        Region region = new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 1);
        when(regions.matchingAt("test_world", 5, 10, 5)).thenReturn(List.of(region));
        when(regions.matchingAt("test_world", 50, 10, 50)).thenReturn(List.of());
        ExternalProtection lands = mock(ExternalProtection.class);
        Player admin = mock(Player.class);
        when(admin.hasPermission(RegionProtectionPolicy.BYPASS_PERMISSION)).thenReturn(true);
        when(lands.denies(admin, Action.BLOCK_BREAK, inside)).thenReturn(true);
        var policy = new RegionProtectionPolicy(regions, ProtectionSettings.read(null), List.of(lands));
        assertFalse(policy.denies(admin, Action.BLOCK_BREAK, inside));
        assertFalse(policy.denies(admin, Action.BLOCK_BREAK, outside));
        verifyNoInteractions(lands);
        Player normal = mock(Player.class);
        var permissiveLocal = new RegionProtectionPolicy(regions,
                new ProtectionSettings(java.util.Set.of(), java.util.Set.of()), List.of(lands));
        when(lands.denies(normal, Action.BLOCK_BREAK, inside)).thenReturn(true);
        assertTrue(permissiveLocal.denies(normal, Action.BLOCK_BREAK, inside));
        when(regions.matchingAt("test_world", 5, 10, 5)).thenThrow(new IllegalStateException("cache unavailable"));
        assertTrue(policy.denies(admin, Action.BLOCK_BREAK, inside));
    }

    @Test
    void bucketsSpawnsAndProjectilePvpUseTheirOwnRules() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Location inside = new Location(world, 5, 10, 5);
        when(regions.matchingAt("test_world", 5, 10, 5)).thenReturn(List.of(
                new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), false, 1)));
        RegionProtectionListener listener = new RegionProtectionListener(
                new RegionProtectionPolicy(regions, ProtectionSettings.read(null)));
        Block clicked = mock(Block.class);
        when(clicked.getLocation()).thenReturn(inside);
        PlayerBucketFillEvent bucket = mock(PlayerBucketFillEvent.class);
        when(bucket.getBlockClicked()).thenReturn(clicked);
        listener.onBucketFill(bucket);
        verify(bucket).setCancelled(true);

        CreatureSpawnEvent spawn = mock(CreatureSpawnEvent.class);
        when(spawn.getLocation()).thenReturn(inside);
        listener.onSpawn(spawn);
        verify(spawn).setCancelled(true);

        Player victim = mock(Player.class);
        Player shooter = mock(Player.class);
        Projectile arrow = mock(Projectile.class);
        when(victim.getLocation()).thenReturn(inside);
        when(arrow.getShooter()).thenReturn(shooter);
        EntityDamageByEntityEvent damage = mock(EntityDamageByEntityEvent.class);
        when(damage.getEntity()).thenReturn(victim);
        when(damage.getDamager()).thenReturn(arrow);
        listener.onDamage(damage);
        verify(damage).setCancelled(true);

        Entity sheep = mock(Entity.class);
        when(sheep.getLocation()).thenReturn(inside);
        PlayerShearEntityEvent shear = mock(PlayerShearEntityEvent.class);
        when(shear.getEntity()).thenReturn(sheep);
        listener.onShear(shear);
        verify(shear).setCancelled(true);

        EntityDamageEvent blast = mock(EntityDamageEvent.class);
        when(blast.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        when(blast.getEntity()).thenReturn(sheep);
        listener.onExplosionDamage(blast);
        verify(blast).setCancelled(true);
    }

    @Test
    void emptyPistonExtensionCannotPushItsHeadIntoProtectedRegion() {
        RegionProtectionPolicy policy = mock(RegionProtectionPolicy.class);
        Location source = mock(Location.class);
        Location headLocation = mock(Location.class);
        Block piston = mock(Block.class);
        Block head = mock(Block.class);
        when(piston.getLocation()).thenReturn(source);
        when(piston.getRelative(BlockFace.EAST)).thenReturn(head);
        when(head.getLocation()).thenReturn(headLocation);
        when(policy.denies(Action.PISTON, headLocation)).thenReturn(true);
        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        when(event.getBlock()).thenReturn(piston);
        when(event.getDirection()).thenReturn(BlockFace.EAST);
        when(event.getBlocks()).thenReturn(List.of());
        new RegionProtectionListener(policy).onPistonExtend(event);
        verify(event).setCancelled(true);
    }

    @Test
    void boundariesWorldAndAmbiguousRegionsFailClosed() {
        RegionService regions = mock(RegionService.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("test_world");
        Region region = new Region("test", "test_world", new Bounds(0, 0, 0, 10, 20, 10), true, 1);
        when(regions.matchingAt(eq("test_world"), anyDouble(), anyDouble(), anyDouble())).thenAnswer(call ->
                region.bounds().contains(call.getArgument(1), call.getArgument(2), call.getArgument(3))
                        ? List.of(region) : List.of());
        RegionProtectionPolicy policy = new RegionProtectionPolicy(regions, ProtectionSettings.read(null));
        assertTrue(policy.denies(Action.BLOCK_BREAK, new Location(world, 0, 0, 0)));
        assertTrue(policy.denies(Action.BLOCK_BREAK, new Location(world, 10, 20, 10)));
        assertFalse(policy.denies(Action.BLOCK_BREAK, new Location(world, 11, 20, 10)));
        assertTrue(policy.denies(Action.BLOCK_BREAK, new Location(null, 5, 10, 5)));
        when(regions.matchingAt("test_world", 5, 10, 5)).thenReturn(List.of(region, region));
        assertTrue(policy.denies(Action.BLOCK_BREAK, new Location(world, 5, 10, 5)));
    }

    @Test
    void fireAndFluidCannotAlterProtectedBlocksAndExternalCancellationIsPreserved() throws Exception {
        RegionProtectionPolicy policy = mock(RegionProtectionPolicy.class);
        RegionProtectionListener listener = new RegionProtectionListener(policy);
        Block source = mock(Block.class);
        Block target = mock(Block.class);
        Location destination = mock(Location.class);
        when(source.isLiquid()).thenReturn(true);
        when(target.getLocation()).thenReturn(destination);
        when(policy.denies(Action.FLUID_FLOW, destination)).thenReturn(true);
        BlockFromToEvent flow = mock(BlockFromToEvent.class);
        when(flow.getBlock()).thenReturn(source);
        when(flow.getToBlock()).thenReturn(target);
        listener.onFluidFlow(flow);
        verify(flow).setCancelled(true);
        BlockIgniteEvent ignite = mock(BlockIgniteEvent.class);
        when(ignite.getBlock()).thenReturn(target);
        when(policy.denies(isNull(), eq(Action.FIRE), eq(destination))).thenReturn(true);
        listener.onIgnite(ignite);
        verify(ignite).setCancelled(true);
        EventHandler handler = RegionProtectionListener.class.getMethod("onBreak", BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(handler.ignoreCancelled());
        verify(policy, never()).denies(Action.BLOCK_BREAK, destination);
    }

    @Test
    void alreadyOpenContainerAndHopperTransfersStayProtected() {
        RegionProtectionPolicy policy = mock(RegionProtectionPolicy.class);
        RegionProtectionListener listener = new RegionProtectionListener(policy);
        Location inside = mock(Location.class);
        Location outside = mock(Location.class);
        Player player = mock(Player.class);
        Inventory protectedInventory = mock(Inventory.class);
        Inventory outsideInventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(protectedInventory.getLocation()).thenReturn(inside);
        when(outsideInventory.getLocation()).thenReturn(outside);
        when(view.getTopInventory()).thenReturn(protectedInventory);
        when(policy.denies(player, Action.CONTAINER, inside)).thenReturn(true);
        when(policy.denies(Action.CONTAINER, inside)).thenReturn(true);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(view);
        listener.onInventoryClick(click);
        verify(click).setCancelled(true);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getView()).thenReturn(view);
        listener.onInventoryDrag(drag);
        verify(drag).setCancelled(true);
        InventoryMoveItemEvent hopper = mock(InventoryMoveItemEvent.class);
        when(hopper.getSource()).thenReturn(outsideInventory);
        when(hopper.getDestination()).thenReturn(protectedInventory);
        listener.onInventoryMove(hopper);
        verify(hopper).setCancelled(true);
        verify(protectedInventory, never()).setItem(anyInt(), any());
    }

    @Test
    void multiPlacePlacedEntitiesAndRedstoneCannotChangeProtectedArea() {
        RegionProtectionPolicy policy = mock(RegionProtectionPolicy.class);
        RegionProtectionListener listener = new RegionProtectionListener(policy);
        Location inside = mock(Location.class);
        Player player = mock(Player.class);
        BlockState state = mock(BlockState.class);
        when(state.getLocation()).thenReturn(inside);
        when(policy.denies(player, Action.BLOCK_PLACE, inside)).thenReturn(true);
        BlockMultiPlaceEvent multi = mock(BlockMultiPlaceEvent.class);
        when(multi.getPlayer()).thenReturn(player);
        when(multi.getReplacedBlockStates()).thenReturn(List.of(state));
        listener.onMultiPlace(multi);
        verify(multi).setCancelled(true);

        org.bukkit.entity.Hanging entity = mock(org.bukkit.entity.Hanging.class);
        when(entity.getLocation()).thenReturn(inside);
        when(policy.denies(player, Action.ENTITY_INTERACT, inside)).thenReturn(true);
        HangingPlaceEvent hanging = mock(HangingPlaceEvent.class);
        when(hanging.getPlayer()).thenReturn(player);
        when(hanging.getEntity()).thenReturn(entity);
        listener.onHangingPlace(hanging);
        verify(hanging).setCancelled(true);
        EntityPlaceEvent placing = mock(EntityPlaceEvent.class);
        when(placing.getPlayer()).thenReturn(player);
        when(placing.getEntity()).thenReturn(entity);
        listener.onEntityPlace(placing);
        verify(placing).setCancelled(true);

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(inside);
        when(policy.denies(Action.REDSTONE, inside)).thenReturn(true);
        BlockRedstoneEvent redstone = mock(BlockRedstoneEvent.class);
        when(redstone.getBlock()).thenReturn(block);
        when(redstone.getOldCurrent()).thenReturn(0);
        listener.onRedstone(redstone);
        verify(redstone).setNewCurrent(0);
    }

    @Test
    void dispenserAtBoundaryCannotActIntoProtectedRegion() {
        RegionProtectionPolicy policy = mock(RegionProtectionPolicy.class);
        Block source = mock(Block.class);
        Block target = mock(Block.class);
        Directional facing = mock(Directional.class);
        Location outside = mock(Location.class);
        Location inside = mock(Location.class);
        when(source.getLocation()).thenReturn(outside);
        when(source.getBlockData()).thenReturn(facing);
        when(facing.getFacing()).thenReturn(BlockFace.EAST);
        when(source.getRelative(BlockFace.EAST)).thenReturn(target);
        when(target.getLocation()).thenReturn(inside);
        when(policy.denies(Action.DISPENSE, inside)).thenReturn(true);
        BlockDispenseEvent dispense = mock(BlockDispenseEvent.class);
        when(dispense.getBlock()).thenReturn(source);
        new RegionProtectionListener(policy).onDispense(dispense);
        verify(dispense).setCancelled(true);
    }
}
