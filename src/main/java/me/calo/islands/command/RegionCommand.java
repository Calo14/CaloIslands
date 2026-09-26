package me.calo.islands.command;

import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.City;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import me.calo.islands.domain.RegionSelectionService;
import me.calo.islands.core.Messages;
import me.calo.islands.core.SelectionTool;
import me.calo.islands.core.SelectionSource;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class RegionCommand implements CommandExecutor, TabCompleter {
    private final RegionService regions;
    private final RegionSelectionService selections;
    private final SelectionTool tool;
    private final Messages messages;
    private final SelectionSource externalSelection;
    private java.util.function.Consumer<Player> menu;
    public void setMenu(java.util.function.Consumer<Player> menu) { this.menu = menu; }

    public RegionCommand(RegionService regions, RegionSelectionService selections,
                         SelectionTool tool, Messages messages) {
        this(regions, selections, tool, messages, null);
    }

    public RegionCommand(RegionService regions, RegionSelectionService selections,
                         SelectionTool tool, Messages messages, SelectionSource externalSelection) {
        this.regions = regions;
        this.selections = selections;
        this.tool = tool;
        this.messages = messages;
        this.externalSelection = externalSelection;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("caloislands.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
            switch (args[0].toLowerCase()) {
                case "menu" -> {
                    require(args, 1);
                    if (menu == null) throw new IllegalStateException("Menú no disponible.");
                    menu.accept(player(sender));
                }
                case "region" -> region(sender, args);
                case "city" -> city(sender, args);
                case "here" -> here(sender);
                default -> help(sender);
            }
        } catch (SelectionSource.UnsupportedShape e) {
            messages.send(sender, "worldedit-non-cuboid");
        } catch (IllegalArgumentException | IllegalStateException e) {
            messages.send(sender, "error", "reason", e.getMessage());
        } catch (SQLException e) {
            Bukkit.getLogger().warning("CaloIslands admin storage failure: SQLState " + e.getSQLState());
            messages.send(sender, "storage-error");
        } catch (RuntimeException | LinkageError e) {
            Bukkit.getLogger().warning("CaloIslands admin integration failure: " + e.getClass().getSimpleName());
            messages.send(sender, "integration-error");
        }
        return true;
    }

    private void region(CommandSender sender, String[] a) throws SQLException {
        if (a.length < 2) { help(sender); return; }
        switch (a[1].toLowerCase()) {
            case "list" -> {
                List<Region> all = regions.regions();
                messages.send(sender, "region-list", "count", all.size(), "ids",
                        String.join(", ", all.stream().map(Region::id).toList()));
            }
            case "info" -> {
                require(a, 3);
                Region r = regions.region(a[2]).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-region")));
                messages.send(sender, "region-info", "id", r.id(), "world", r.world(), "bounds", r.bounds(),
                        "active", r.active(), "operational", regions.operational(r), "version", r.version());
            }
            case "create" -> {
                require(a, 3);
                Player player = player(sender);
                RegionSelectionService.Selection selection = completeSelection(player);
                Bounds area = selectedBounds(player, selection);
                validateWorldBounds(selection.world(), area);
                Region created = regions.createRegion(a[2], selection.world(), area);
                clearSelection(player);
                messages.send(sender, "region-created", "id", created.id());
            }
            case "resize" -> {
                require(a, 3);
                Player player = player(sender);
                RegionSelectionService.Selection selection = completeSelection(player);
                Region current = regions.region(a[2]).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-region")));
                if (!current.world().equals(selection.world())) {
                    throw new IllegalArgumentException(messages.text("selection-region-world-mismatch"));
                }
                Bounds area = selectedBounds(player, selection);
                validateWorldBounds(current.world(), area);
                Region resized = regions.resizeRegion(a[2], area);
                clearSelection(player);
                messages.send(sender, "region-resized", "id", resized.id());
            }
            case "wand" -> {
                require(a, 2);
                Player player = player(sender);
                if (externalSelection != null) { messages.send(player, "worldedit-wand"); return; }
                int slot = player.getInventory().firstEmpty();
                if (slot < 0) { messages.send(sender, "wand-inventory-full"); return; }
                player.getInventory().setItem(slot, tool.create());
                messages.send(sender, "wand-given");
            }
            case "selection" -> {
                require(a, 2);
                showSelection(player(sender));
            }
            case "mode" -> {
                require(a, 3);
                Player player = player(sender);
                RegionSelectionService.Mode mode = switch (a[2].toLowerCase()) {
                    case "fullheight" -> RegionSelectionService.Mode.FULLHEIGHT;
                    case "exact" -> RegionSelectionService.Mode.EXACT;
                    default -> throw new IllegalArgumentException(messages.text("mode-invalid"));
                };
                selections.setMode(player.getUniqueId(), mode);
                messages.send(player, "mode-set", "mode", mode.name().toLowerCase());
            }
            case "preview" -> {
                require(a, 2);
                Player player = player(sender);
                if (externalSelection != null) { messages.send(player, "worldedit-preview"); return; }
                boolean enabled = selections.togglePreview(player.getUniqueId());
                messages.send(player, enabled ? "preview-on" : "preview-off");
            }
            case "clear" -> {
                require(a, 2);
                clearSelection(player(sender));
                messages.send(sender, "selection-cleared");
            }
            case "activate", "deactivate" -> {
                require(a, 3);
                Region r = regions.setActive(a[2], a[1].equalsIgnoreCase("activate"));
                messages.send(sender, "region-state", "id", r.id(), "active", r.active(), "version", r.version());
            }
            case "delete" -> {
                require(a, 3);
                regions.deleteRegion(a[2]);
                messages.send(sender, "region-deleted", "id", a[2]);
            }
            default -> help(sender);
        }
    }

    private void city(CommandSender sender, String[] a) throws SQLException {
        if (a.length < 2) { help(sender); return; }
        switch (a[1].toLowerCase()) {
            case "list" -> {
                List<City> all = regions.cities();
                messages.send(sender, "city-list", "count", all.size(), "ids",
                        String.join(", ", all.stream().map(city -> city.id() + "@" + city.regionId()
                                + " (" + city.world() + ")").toList()));
            }
            case "info" -> {
                require(a, 3);
                City c = regions.city(a[2]).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-city")));
                Region r = regions.region(c.regionId()).orElseThrow(
                        () -> new IllegalStateException(messages.text("city-region-missing")));
                if (!c.world().equals(r.world())) throw new IllegalStateException(messages.text("city-world-inconsistent"));
                messages.send(sender, "city-info-v2", "id", c.id(), "region", c.regionId(), "world", c.world(),
                        "x", c.x(), "y", c.y(), "z", c.z(), "status", messages.text(
                                r.active() ? "city-state-active" : "city-state-inactive"),
                        "operational", regions.operational(r), "version", c.version());
            }
            case "create" -> {
                require(a, 4);
                Location location = player(sender).getLocation();
                Region region = regions.region(a[3]).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-region")));
                requireSameWorld(location, region);
                City c = regions.createCity(a[2], a[3], location.getX(), location.getY(), location.getZ());
                messages.send(sender, "city-updated-v2", "id", c.id(), "region", c.regionId(), "world", c.world(),
                        "x", c.x(), "y", c.y(), "z", c.z(), "version", c.version());
            }
            case "move" -> {
                require(a, 3);
                Location location = player(sender).getLocation();
                City old = regions.city(a[2]).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-city")));
                Region region = regions.region(old.regionId()).orElseThrow(() -> new IllegalArgumentException(messages.text("unknown-region")));
                requireSameWorld(location, region);
                City c = regions.moveCity(a[2], old.regionId(), location.getX(), location.getY(), location.getZ());
                messages.send(sender, "city-updated-v2", "id", c.id(), "region", c.regionId(), "world", c.world(),
                        "x", c.x(), "y", c.y(), "z", c.z(), "version", c.version());
            }
            case "delete" -> {
                require(a, 3);
                regions.deleteCity(a[2]);
                messages.send(sender, "city-deleted", "id", a[2]);
            }
            default -> help(sender);
        }
    }

    private void here(CommandSender sender) throws SQLException {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException(messages.text("player-only"));
        Location at = player.getLocation();
        regions.at(at.getWorld().getName(), at.getX(), at.getY(), at.getZ())
                .ifPresentOrElse(r -> messages.send(sender, "here-region", "id", r.id(), "active", r.active()),
                        () -> messages.send(sender, "here-empty"));
    }

    private void showSelection(Player player) {
        RegionSelectionService.Mode mode = selections.mode(player.getUniqueId());
        RegionSelectionService.Selection selection = currentSelection(player);
        if (selection == null) { messages.send(player, externalSelection == null
                ? "selection-empty" : "worldedit-selection-empty", "mode", mode.name().toLowerCase()); return; }
        String first = selection.first() == null ? messages.text("selection-unset")
                : selection.first().world() + " " + selection.first();
        String second = selection.second() == null ? messages.text("selection-unset")
                : selection.second().world() + " " + selection.second();
        String world = selection.sameWorld() ? selection.world()
                : selection.complete() ? messages.text("selection-worlds-mixed")
                : selection.first() != null ? selection.first().world() : selection.second().world();
        String dimensions = messages.text("selection-unset");
        if (selection.sameWorld()) {
            World loaded = Bukkit.getWorld(selection.world());
            if (loaded != null) dimensions = selection.dimensions(selection.effectiveBounds(
                    mode, loaded.getMinHeight(), loaded.getMaxHeight()));
        }
        messages.send(player, "selection-status", "world", world, "pos1", first,
                "pos2", second, "dimensions", dimensions, "mode", mode.name().toLowerCase());
    }

    private RegionSelectionService.Selection completeSelection(Player player) {
        RegionSelectionService.Selection selection = currentSelection(player);
        if (selection == null) throw new IllegalArgumentException(messages.text(
                externalSelection == null ? "selection-incomplete" : "worldedit-selection-incomplete"));
        if (!selection.complete()) throw new IllegalArgumentException(messages.text("selection-incomplete"));
        if (!selection.sameWorld()) throw new IllegalArgumentException(messages.text("selection-world-mismatch"));
        return selection;
    }

    private RegionSelectionService.Selection currentSelection(Player player) {
        return externalSelection == null ? selections.get(player.getUniqueId()).orElse(null)
                : externalSelection.selection(player);
    }

    private void clearSelection(Player player) {
        if (externalSelection != null) externalSelection.clear(player);
        selections.clear(player.getUniqueId());
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException(messages.text("player-only"));
    }

    private void requireSameWorld(Location location, Region region) {
        if (!location.getWorld().getName().equals(region.world())) {
            throw new IllegalArgumentException(messages.text("city-world-mismatch"));
        }
    }
    private void validateWorldBounds(String name, Bounds bounds) {
        World world = Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException(messages.text("world-unloaded", "world", name));
        if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException(messages.text("height-invalid"));
        }
    }
    private Bounds selectedBounds(Player player, RegionSelectionService.Selection selection) {
        World world = Bukkit.getWorld(selection.world());
        if (world == null) throw new IllegalArgumentException(messages.text("world-unloaded", "world", selection.world()));
        return selection.effectiveBounds(selections.mode(player.getUniqueId()),
                world.getMinHeight(), world.getMaxHeight());
    }
    private void require(String[] args, int length) {
        if (args.length != length) throw new IllegalArgumentException(messages.text("wrong-arguments"));
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§e/calo menu §7— panel central de administración");
        messages.send(sender, "help-region");
        messages.send(sender, "help-region-edit");
        messages.send(sender, externalSelection == null ? "help-selection" : "help-worldedit-selection");
        messages.send(sender, "help-city");
        messages.send(sender, "help-here");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("caloislands.admin")) return List.of();
        if (args.length == 1) return matching(List.of("region", "city", "here", "help", "menu"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("region"))
            return matching(List.of("wand", "selection", "mode", "preview", "clear", "list", "info", "create", "resize", "activate", "deactivate", "delete"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("region") && args[1].equalsIgnoreCase("mode"))
            return matching(List.of("fullheight", "exact"), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("city"))
            return matching(List.of("list", "info", "create", "move", "delete"), args[1]);
        try {
            if (args.length == 3 && args[0].equalsIgnoreCase("region")
                    && List.of("info", "resize", "activate", "deactivate", "delete").contains(args[1].toLowerCase()))
                return matching(regions.regions().stream().map(Region::id).toList(), args[2]);
            if (args.length == 3 && args[0].equalsIgnoreCase("city")
                    && List.of("info", "move", "delete").contains(args[1].toLowerCase()))
                return matching(regions.cities().stream().map(City::id).toList(), args[2]);
            if (args.length == 4 && args[0].equalsIgnoreCase("city") && args[1].equalsIgnoreCase("create"))
                return matching(regions.regions().stream().map(Region::id).toList(), args[3]);
        } catch (SQLException ignored) { return List.of(); }
        return List.of();
    }

    private static List<String> matching(List<String> source, String prefix) {
        List<String> result = new ArrayList<>();
        for (String value : source) if (value.startsWith(prefix.toLowerCase())) result.add(value);
        return result;
    }
}
