package me.calo.islands.gui;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class AdminUiTest {
    private static final String PERMISSION = "caloislands.admin";
    static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Player player = mock(Player.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final InventoryView view = mock(InventoryView.class);
        final AtomicReference<Inventory> top = new AtomicReference<>();
        final java.util.concurrent.LinkedBlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();
        final List<Runnable> expiry = new ArrayList<>();
        final Map<Inventory, String> titles = new HashMap<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<ItemStack> items = mockConstruction(ItemStack.class, (item, context) -> {
            ItemMeta meta = mock(ItemMeta.class); AtomicReference<String> name = new AtomicReference<>();
            doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(meta).setDisplayName(anyString());
            when(meta.getDisplayName()).thenAnswer(call -> name.get()); when(item.getItemMeta()).thenReturn(meta);
        });
        final AdminUi ui = new AdminUi(plugin, PERMISSION);
        Fixture() {
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
            when(player.hasPermission(PERMISSION)).thenReturn(true); when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenAnswer(call -> top.get());
            doAnswer(call -> { top.set(call.getArgument(0)); return view; }).when(player).openInventory(any(Inventory.class));
            doAnswer(call -> { top.set(mock(Inventory.class)); return null; }).when(player).closeInventory();
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            org.bukkit.Server server = mock(org.bukkit.Server.class); when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler); when(plugin.isEnabled()).thenReturn(true);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString())).thenAnswer(call -> {
                Inventory inventory = mock(Inventory.class); when(inventory.getHolder()).thenReturn(call.getArgument(0));
                Map<Integer, ItemStack> slots = new HashMap<>();
                doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), any());
                when(inventory.getItem(anyInt())).thenAnswer(c -> slots.get(c.getArgument(0)));
                titles.put(inventory, call.getArgument(2)); return inventory;
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { tasks.add(call.getArgument(1)); return mock(BukkitTask.class); });
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1200L))).thenAnswer(call -> { expiry.add(call.getArgument(1)); return mock(BukkitTask.class); });
        }
        AdminUi.Screen screen(AtomicInteger actions) {
            var screen = ui.screen(player, "Test", null);
            ui.button(screen, 10, Material.PAPER, "Button", List.of(), p -> { actions.incrementAndGet(); ui.show(p, ui.screen(p, "Next", null)); });
            ui.show(player, screen); return screen;
        }
        InventoryClickEvent click(int slot, ClickType type) {
            InventoryClickEvent e = mock(InventoryClickEvent.class); when(e.getView()).thenReturn(view);
            when(e.getWhoClicked()).thenReturn(player); when(e.getRawSlot()).thenReturn(slot); when(e.getClick()).thenReturn(type); return e;
        }
        void drain() { Runnable task; while ((task = tasks.poll()) != null) task.run(); }
        String title() { return titles.get(top.get()); }
        void awaitQuery() throws Exception {
            while (title().contains("Consultando")) {
                Runnable task = tasks.poll(2, java.util.concurrent.TimeUnit.SECONDS); assertNotNull(task, "Query never scheduled its response"); task.run();
            }
        }
        void chat(String raw) {
            AsyncPlayerChatEvent e = mock(AsyncPlayerChatEvent.class); when(e.getPlayer()).thenReturn(player); when(e.getMessage()).thenReturn(raw);
            ui.chat(e); verify(e).setCancelled(true); drain();
        }
        @Override public void close() { items.close(); bukkit.close(); }
    }
    @Test void doubleClickQueuesExactlyOneActionAndOldScreenCannotBeReused() {
        try (var f = new Fixture()) {
            var count = new AtomicInteger(); var screen = f.screen(count); var event = f.click(10, ClickType.LEFT);
            f.ui.click(event); f.ui.click(event); assertEquals(1, f.tasks.size()); f.drain(); assertEquals(1, count.get());
            assertFalse(screen.claim(f.player.getUniqueId(), 10)); verify(event, times(2)).setCancelled(true);
        }
    }
    @Test void permissionRevocationAndForeignOwnerPreventActions() {
        try (var f = new Fixture()) {
            var count = new AtomicInteger(); var screen = f.screen(count);
            assertFalse(screen.claim(UUID.randomUUID(), 10));
            when(f.player.hasPermission(PERMISSION)).thenReturn(false); f.ui.click(f.click(10, ClickType.LEFT)); f.drain();
            assertEquals(0, count.get()); verify(f.player).closeInventory();
        }
    }
    @Test void shiftHotbarDoubleClickAndBottomInventoryCannotExecuteButtons() {
        try (var f = new Fixture()) {
            var count = new AtomicInteger(); f.screen(count);
            for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.SWAP_OFFHAND)) {
                var e = f.click(10, type); f.ui.click(e); verify(e).setCancelled(true);
            }
            f.ui.click(f.click(64, ClickType.LEFT)); f.drain(); assertEquals(0, count.get());
            InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(f.view); f.ui.drag(drag); verify(drag).setCancelled(true);
        }
    }
    @Test void closingOrChangingInventoryBeforeScheduledClickPreventsMutation() {
        try (var f = new Fixture()) {
            var count = new AtomicInteger(); f.screen(count); f.ui.click(f.click(10, ClickType.LEFT));
            f.top.set(mock(Inventory.class)); f.drain(); assertEquals(0, count.get());
        }
    }
    @Test void chatRejectsConcurrentSearchValidatesThenConsumesOnce() {
        try (var f = new Fixture()) {
            var received = new ArrayList<String>();
            assertTrue(f.ui.prompt(f.player, "Name", s -> s.matches("[a-z]{3,16}"), received::add, () -> {}));
            assertFalse(f.ui.prompt(f.player, "Other", s -> true, received::add, () -> {})); assertEquals(1, f.expiry.size());
            f.chat("bad command!"); assertTrue(received.isEmpty()); f.chat("alice"); assertEquals(List.of("alice"), received);
            f.expiry.getFirst().run(); assertEquals(List.of("alice"), received);
        }
    }
    @Test void cancellationAndExpiryReturnWithoutUsingLateInput() {
        try (var f = new Fixture()) {
            var results = new AtomicInteger(); var backs = new AtomicInteger();
            f.ui.prompt(f.player, "Name", s -> true, s -> results.incrementAndGet(), backs::incrementAndGet);
            f.chat("cancelar"); f.expiry.getFirst().run(); assertEquals(1, backs.get()); assertEquals(0, results.get());
            f.ui.prompt(f.player, "Name", s -> true, s -> results.incrementAndGet(), backs::incrementAndGet);
            f.expiry.get(1).run(); assertEquals(2, backs.get()); assertEquals(0, results.get());
        }
    }
    @Test void queuedChatCannotCompleteAfterPermissionWasRevoked() {
        try (var f = new Fixture()) {
            var results = new AtomicInteger(); f.ui.prompt(f.player, "Name", s -> true, s -> results.incrementAndGet(), () -> {});
            when(f.player.hasPermission(PERMISSION)).thenReturn(false); f.chat("alice"); assertEquals(0, results.get());
        }
    }
}
