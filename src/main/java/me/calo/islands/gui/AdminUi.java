package me.calo.islands.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread UI actions, owner-bound holders, single-use clicks and expiring chat input. */
public final class AdminUi implements Listener {
    public static final String BACK = "§eVolver", CLOSE = "§cCerrar", PREVIOUS = "§eAnterior", NEXT = "§eSiguiente";
    public static final int PAGE_SIZE = 21;
    public static final int[] CONTENT = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private final JavaPlugin plugin;
    private final String permission;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public AdminUi(JavaPlugin plugin, String permission) { this.plugin = plugin; this.permission = permission; }

    public static final class Screen implements InventoryHolder {
        private final UUID owner;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private Inventory inventory;
        private boolean consumed;
        public Screen(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
        public boolean claim(UUID viewer, int slot) {
            if (consumed || !owner.equals(viewer) || !actions.containsKey(slot)) return false;
            consumed = true;
            return true;
        }
    }

    public Screen screen(Player player, String title, Consumer<Player> back) {
        Screen screen = new Screen(player.getUniqueId());
        screen.inventory = Bukkit.createInventory(screen, 54, title);
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < 54; slot++) screen.inventory.setItem(slot, filler);
        button(screen, 49, Material.BARRIER, CLOSE, List.of(), Player::closeInventory);
        if (back != null) button(screen, 48, Material.ARROW, BACK, List.of(), back);
        return screen;
    }

    public void button(Screen screen, int slot, Material icon, String name, List<String> lore, Consumer<Player> action) {
        screen.inventory.setItem(slot, item(icon, name, lore));
        if (action != null) screen.actions.put(slot, action);
    }
    public void show(Player player, Screen screen) {
        if (player.isOnline() && player.hasPermission(permission)) player.openInventory(screen.inventory);
    }
    public void pages(Screen screen, int page, boolean more, Consumer<Player> previous, Consumer<Player> next) {
        if (page > 0) button(screen, 45, Material.ARROW, PREVIOUS, List.of(), previous);
        if (more) button(screen, 53, Material.ARROW, NEXT, List.of(), next);
        button(screen, 4, Material.PAPER, "§6Página " + (page + 1), List.of(), null);
    }
    public static ItemStack item(Material icon, String name, List<String> lore) {
        ItemStack item = new ItemStack(icon);
        var meta = item.getItemMeta();
        meta.setDisplayName(name); meta.setLore(lore); meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta); return item;
    }
    public void confirm(Player player, String title, List<String> summary, Consumer<Player> action, Consumer<Player> back) {
        Screen screen = screen(player, title, back);
        button(screen, 13, Material.PAPER, "§6Revisar operación", summary, null);
        button(screen, 30, Material.LIME_CONCRETE, "§aConfirmar una vez", List.of("§7La operación volverá a validar el estado."), action);
        button(screen, 32, Material.RED_CONCRETE, "§cCancelar", List.of(), back);
        show(player, screen);
    }

    public boolean prompt(Player player, String instruction, Predicate<String> valid, Consumer<String> result, Runnable back) {
        Prompt prompt = new Prompt(valid, result, back);
        if (prompts.putIfAbsent(player.getUniqueId(), prompt) != null) {
            player.sendMessage("§eYa tienes una entrada pendiente. Escribe cancelar."); return false;
        }
        player.closeInventory();
        player.sendMessage("§e" + instruction + " §7Escribe cancelar para volver. Expira en 60 segundos.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (prompts.remove(player.getUniqueId(), prompt) && player.isOnline()) {
                player.sendMessage("§eLa entrada expiró."); back.run();
            }
        }, 1200L);
        return true;
    }
    private record Prompt(Predicate<String> valid, Consumer<String> result, Runnable back) { }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.get(player.getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String raw = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (prompts.get(player.getUniqueId()) != prompt) return;
            if (!player.isOnline() || !player.hasPermission(permission)) { prompts.remove(player.getUniqueId(), prompt); return; }
            if (raw.equalsIgnoreCase("cancelar") || raw.equalsIgnoreCase("cancel")) {
                prompts.remove(player.getUniqueId(), prompt); player.sendMessage("§7Entrada cancelada."); prompt.back.run(); return;
            }
            if (raw.length() > 128 || !prompt.valid.test(raw)) {
                player.sendMessage("§cEntrada inválida. Revisa el formato o escribe cancelar."); return;
            }
            if (prompts.remove(player.getUniqueId(), prompt)) prompt.result.accept(raw);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Screen screen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission(permission) || !screen.owner.equals(player.getUniqueId())) { player.closeInventory(); return; }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        if (event.getView().getTopInventory() != screen.inventory || !screen.claim(player.getUniqueId(), slot)) return;
        Consumer<Player> action = screen.actions.get(slot);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission(permission)
                    && player.getOpenInventory().getTopInventory() == screen.inventory) {
                try { action.accept(player); }
                finally { if (player.getOpenInventory().getTopInventory() == screen.inventory) screen.consumed = false; }
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Screen) event.setCancelled(true);
    }
}
