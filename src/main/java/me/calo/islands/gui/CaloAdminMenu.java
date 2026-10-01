package me.calo.islands.gui;

import me.calo.islands.command.RegionCommand;
import me.calo.islands.core.*;
import me.calo.islands.data.RegionStore;
import me.calo.islands.data.SchemaMigrator;
import me.calo.islands.domain.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CaloAdminMenu implements Listener {
    private final JavaPlugin plugin;
    private final AdminUi ui;
    private final RegionService regions;
    private final RegionSelectionService selections;
    private final SelectionSource source;
    private final RegionPreviewService previews;
    private final RegionCommand command;
    private final AdminTeleportService teleport;
    private final RegionStore store;
    private me.calo.islands.content.ObjectiveActivityService objectives;
    private ObjectiveAdminMenu activityMenu;
    private final Map<UUID, Integer> regionPages = new HashMap<>(), cityPages = new HashMap<>();
    private final Map<UUID, String> cityFilters = new HashMap<>();
    public CaloAdminMenu(JavaPlugin plugin, AdminUi ui, RegionService regions, RegionSelectionService selections,
                         SelectionSource source, RegionPreviewService previews, RegionCommand command,
                         AdminTeleportService teleport, RegionStore store) {
        this.plugin = plugin; this.ui = ui; this.regions = regions; this.selections = selections;
        this.source = source; this.previews = previews; this.command = command; this.teleport = teleport; this.store = store;
    }
    public void setObjectiveService(me.calo.islands.content.ObjectiveActivityService objectives) {
        this.objectives = objectives;
    }
    public void setObjectiveCatalog(me.calo.islands.content.ObjectiveCatalog catalog) {
        this.activityMenu = new ObjectiveAdminMenu(ui, catalog, objectives, regions, this::open);
    }
    public void open(Player player) {
        if (!player.hasPermission("caloislands.admin")) { player.sendMessage("§cNo tienes permiso."); return; }
        var screen = ui.screen(player, "§8CaloIslands · Administración", null);
        button(screen, 10, Material.GRASS_BLOCK, "Regiones", List.of("Consultar, editar, previsualizar o visitar", "Administración: caloislands.admin"), p -> regionList(p, 0));
        button(screen, 12, Material.BELL, "Ciudades", List.of("Consultar, mover, eliminar o visitar", "Administración: caloislands.admin"), p -> cityList(p, null, 0));
        button(screen, 14, Material.WOODEN_AXE, "Selección", List.of("WorldEdit o wand · modos exacto y altura completa"), this::selection);
        button(screen, 16, Material.ENDER_PEARL, "Visitar región", List.of("Elige una región con punto seguro disponible"), p -> regionList(p, 0));
        button(screen, 28, Material.COMPASS, "Visitar ciudad", List.of("Elige un destino guardado y validado"), p -> cityList(p, null, 0));
        button(screen, 30, Material.ENDER_EYE, "Previsualización", List.of("Límites visibles solamente para ti"), this::preview);
        button(screen, 32, Material.REDSTONE, "Estado de MariaDB", List.of("Conexión y esquema · sin secretos"), this::database);
        button(screen, 34, Material.BOOK, "Ayuda", List.of("Acciones, permisos y selección"), p -> help(p, 0));
        button(screen, 26, Material.CLOCK, "Actividades", List.of("Crear, editar y consultar progreso"), p -> {
            if (activityMenu == null) throw new IllegalStateException("Las actividades no están disponibles.");
            activityMenu.list(p, 0);
        });
        ui.show(player, screen);
    }
    private void button(AdminUi.Screen screen, int slot, Material icon, String label, List<String> lore, Consumer<Player> action) {
        ui.button(screen, slot, icon, "§6" + label, lore.stream().map(s -> "§7" + s).toList(), p -> safe(p, () -> action.accept(p)));
    }
    private void safe(Player player, Runnable action) {
        try { action.run(); }
        catch (SQLExceptionWrapper failure) { storageError(player); }
        catch (IllegalArgumentException | IllegalStateException failure) { player.sendMessage("§c" + MenuText.error(failure.getMessage())); open(player); }
        catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("Calo GUI failure: " + failure.getClass().getSimpleName());
            player.sendMessage("§cNo se pudo completar la acción. Revisa el estado de las integraciones."); open(player);
        }
    }
    private void storageError(Player player) { player.sendMessage("§cError de MariaDB. No se confirmó la operación; consulta el estado antes de repetir."); open(player); }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private static final class SQLExceptionWrapper extends RuntimeException { SQLExceptionWrapper(SQLException cause) { super(cause); } }
    private void mutate(Player player, String operation, SqlAction action, Consumer<Player> after) {
        try {
            action.run();
            previews.clearAll();
            plugin.getLogger().info("Calo admin=" + player.getUniqueId() + " operation=" + operation + " result=APPLIED");
            player.sendMessage("§aOperación confirmada: " + operation); after.accept(player);
        } catch (SQLException failure) { throw new SQLExceptionWrapper(failure); }
    }
    public void regionList(Player player, int requested) {
        List<Region> all = regions.regions().stream().sorted(Comparator.comparing(Region::id)).toList();
        int page = page(requested, all.size());
        regionPages.put(player.getUniqueId(), page);
        var screen = ui.screen(player, "§8Regiones", this::open);
        for (int i = page * AdminUi.PAGE_SIZE; i < Math.min(all.size(), (page + 1) * AdminUi.PAGE_SIZE); i++) {
            Region region = all.get(i);
            button(screen, AdminUi.CONTENT[i % AdminUi.PAGE_SIZE], region.active() ? Material.LIME_TERRACOTTA : Material.RED_TERRACOTTA,
                    MenuText.label(region.id()), regionInfo(region), p -> region(p, region.id()));
        }
        if (all.isEmpty()) ui.button(screen, 22, Material.GRAY_DYE, "§7Sin regiones", List.of("§7Usa Selección actual para crear una."), null);
        ui.pages(screen, page, (page + 1) * AdminUi.PAGE_SIZE < all.size(), p -> regionList(p, page - 1), p -> regionList(p, page + 1));
        ui.show(player, screen);
    }
    private List<String> regionInfo(Region r) {
        Bounds b = r.bounds();
        boolean operational = regions.operational(r);
        return List.of("§7Mundo: §f" + r.world(), "§7Límites: §f" + b.minX() + "," + b.minY() + "," + b.minZ()
                + " → " + b.maxX() + "," + b.maxY() + "," + b.maxZ(),
                "§7Dimensiones: §f" + ((long)b.maxX()-b.minX()+1) + " × " + ((long)b.maxY()-b.minY()+1) + " × " + ((long)b.maxZ()-b.minZ()+1),
                "§7Estado: " + MenuText.state(r.active()),
                "§7Región operativa: " + (operational ? "§aSí" : "§cNo · región inactiva o mundo no disponible"),
                "§7Altura: §f" + ((long)b.maxY()-b.minY()+1) + " bloques",
                r.destination() == null ? "§7Punto de llegada: §eSin configurar" : "§7Punto de llegada: §f" + r.destination().world() + " " + r.destination().x() + ", " + r.destination().y() + ", " + r.destination().z(),
                destinationStatus(r.destination()));
    }
    private String destinationStatus(Destination destination) {
        if (destination == null) return "§e" + command.messages().text("point-unset");
        var state = teleport.inspectDestination(destination);
        String key = state == null || state == AdminTeleportService.DestinationStatus.TERRAIN_UNCHECKED ? "destination-terrain-unchecked"
                : state == AdminTeleportService.DestinationStatus.INVALID ? "destination-status-invalid"
                : state == AdminTeleportService.DestinationStatus.WORLD_UNAVAILABLE ? "destination-world-unavailable" : "destination-status-valid";
        return command.messages().text(key);
    }
    private boolean invalidDestination(Destination destination) {
        if (destination == null) return true;
        var state = teleport.inspectDestination(destination);
        return state == AdminTeleportService.DestinationStatus.INVALID || state == AdminTeleportService.DestinationStatus.WORLD_UNAVAILABLE;
    }
    private Region requireRegion(String id) { return regions.region(id).orElseThrow(() -> new IllegalArgumentException("La región ya no existe.")); }
    private void unchanged(Region expected) {
        if (!requireRegion(expected.id()).equals(expected)) throw new IllegalStateException("La región cambió. Revisa los detalles y confirma de nuevo.");
    }
    private void region(Player player, String id) {
        region(player, id, p -> regionList(p, regionPages.getOrDefault(p.getUniqueId(), 0)));
    }
    private void region(Player player, String id, Consumer<Player> back) {
        Region r = requireRegion(id);
        var screen = ui.screen(player, "§8Región · " + MenuText.label(id), back);
        ui.button(screen, 4, r.active() ? Material.LIME_TERRACOTTA : Material.RED_TERRACOTTA, "§6" + MenuText.label(id), regionInfo(r), null);
        if (invalidDestination(r.destination()))
            ui.button(screen, 10, Material.GRAY_DYE, "§7Ir a región", List.of(destinationStatus(r.destination())), null);
        else button(screen, 10, Material.ENDER_PEARL, "Ir a región", List.of("Punto guardado · comprueba seguridad y protecciones"), p -> teleport.region(p, id));
        button(screen, 31, Material.COMPASS, "Establecer punto actual", List.of("Guarda tu posición y orientación", "Las esquinas permanecen iguales"), p -> {
            command.onCommand(p, plugin.getCommand("caloislands"), "calo", new String[]{"region", "setpoint", id}); region(p, id, back);
        });
        if (r.destination() == null) ui.button(screen, 32, Material.GRAY_DYE, "§7Eliminar punto", List.of("§7Sin configurar"), null);
        else button(screen, 32, Material.SPONGE, "Eliminar punto", List.of("Conserva el área de la región"), p -> {
            command.onCommand(p, plugin.getCommand("caloislands"), "calo", new String[]{"region", "clearpoint", id}); region(p, id, back);
        });
        button(screen, 12, r.active() ? Material.REDSTONE_TORCH : Material.TORCH, r.active() ? "Desactivar" : "Activar",
                List.of("Cambiar estado persistido"), p -> ui.confirm(p, "§8Confirmar estado", regionInfo(r), a -> safe(a, () -> {
                    unchanged(r); mutate(a, "region-state " + id, () -> regions.setActive(id, !r.active()), x -> region(x, id, back));
                }), a -> region(a, id, back)));
        button(screen, 14, Material.ENDER_EYE, "Previsualizar límites", List.of("Vista personal · no cambia tu selección"), p -> {
            if (!p.getLocation().getWorld().getName().equals(requireRegion(id).world()))
                throw new IllegalArgumentException(command.messages().text("preview-world-mismatch"));
            p.closeInventory(); previews.region(p.getUniqueId(), requireRegion(id)); setPreview(p, true); command.messages().send(p, "preview-on");
        });
        button(screen, 16, Material.BELL, "Ciudades de la región", List.of("Consulta la relación y disponibilidad de cada destino"), p -> cityList(p, id, 0));
        button(screen, 28, Material.WOODEN_AXE, "Redimensionar", List.of("Usa tu selección actual", "Primero desactiva la región"), p -> {
            var selected = complete(p); Bounds bounds = bounds(p, selected);
            ui.confirm(p, "§8Confirmar dimensiones", List.of("Región: " + id, "Mundo: " + selected.world(), "Nuevos límites: " + bounds), a -> safe(a, () -> {
                unchanged(r);
                if (!Objects.equals(current(a), selected) || !bounds(a, selected).equals(bounds)) throw new IllegalStateException("La selección cambió.");
                command.onCommand(a, plugin.getCommand("caloislands"), "calo", new String[]{"region", "resize", id}); region(a, id, back);
            }), a -> region(a, id, back));
        });
        button(screen, 30, Material.OAK_SIGN, "Crear ciudad aquí", List.of("Usa tu ubicación al pulsar", "Debe estar dentro de esta región"), p -> createCity(p, r));
        button(screen, 34, Material.TNT, "Eliminar región", List.of("Desactiva y elimina sus ciudades primero"), p -> ui.confirm(p,
                "§8Eliminar región", List.of("Región: " + id, "Mundo: " + r.world(), "Eliminación permanente"), a -> safe(a, () -> {
                    unchanged(r); mutate(a, "region-delete " + id, () -> regions.deleteRegion(id), x -> regionList(x, 0));
                }), a -> region(a, id, back)));
        ui.show(player, screen);
    }
    private void cityList(Player player, String regionId, int requested) {
        load(player, () -> regions.cities(), all -> {
            List<City> cities = all.stream().filter(c -> regionId == null || c.regionId().equals(regionId)).sorted(Comparator.comparing(City::id)).toList();
            int page = page(requested, cities.size());
            cityPages.put(player.getUniqueId(), page); cityFilters.put(player.getUniqueId(), regionId);
            var screen = ui.screen(player, regionId == null ? "§8Ciudades" : "§8Ciudades · " + MenuText.label(regionId), regionId == null ? this::open : p -> region(p, regionId));
            for (int i = page * AdminUi.PAGE_SIZE; i < Math.min(cities.size(), (page + 1) * AdminUi.PAGE_SIZE); i++) {
                City city = cities.get(i);
                Region related = requireRegion(city.regionId());
                button(screen, AdminUi.CONTENT[i % AdminUi.PAGE_SIZE], related.active() ? Material.BELL : Material.GRAY_DYE,
                        MenuText.label(city.id()), cityInfo(city), p -> city(p, city));
            }
            if (cities.isEmpty()) ui.button(screen, 22, Material.GRAY_DYE, "§7Sin ciudades", List.of("§7Crea una desde los detalles de una región."), null);
            ui.pages(screen, page, (page + 1) * AdminUi.PAGE_SIZE < cities.size(), p -> cityList(p, regionId, page - 1), p -> cityList(p, regionId, page + 1));
            ui.show(player, screen);
        });
    }
    private List<String> cityInfo(City c) {
        Region r = requireRegion(c.regionId());
        boolean available = regions.operational(r) && c.world().equals(r.world())
                && r.bounds().contains(c.x(), c.y(), c.z()) && !invalidDestination(c.destination());
        return List.of("§7Región: §f" + MenuText.label(c.regionId()), "§7Mundo: §f" + c.world(),
                "§7Estado de región: " + MenuText.state(r.active()),
                "§7Disponible: " + (available ? "§aSí · destino guardado" : "§cNo · revisa región y mundo"),
                "§7Destino: §f" + c.x() + ", " + c.y() + ", " + c.z(),
                destinationStatus(c.destination()), "§8Los permisos y las protecciones se validan al viajar.");
    }
    private void unchanged(City c) throws SQLException {
        if (!regions.city(c.id()).filter(c::equals).isPresent()) throw new IllegalStateException("La ciudad cambió. Consulta la lista de nuevo.");
    }
    private void city(Player player, City c) {
        Region related = requireRegion(c.regionId());
        var screen = ui.screen(player, "§8Ciudad · " + MenuText.label(c.id()), this::returnCities);
        ui.button(screen, 4, related.active() ? Material.BELL : Material.GRAY_DYE, "§6" + MenuText.label(c.id()), cityInfo(c), null);
        if (invalidDestination(c.destination())) ui.button(screen, 10, Material.GRAY_DYE, "§7Visitar ciudad", List.of(destinationStatus(c.destination())), null);
        else button(screen, 10, Material.ENDER_PEARL, "Visitar ciudad", List.of("Destino guardado · comprueba seguridad y protecciones"), p -> {
            try { teleport.city(p, c.id()); } catch (SQLException failure) { throw new SQLExceptionWrapper(failure); }
        });
        button(screen, 28, Material.MAP, "Región · " + MenuText.label(c.regionId()), regionInfo(related), p -> region(p, c.regionId(), a -> city(a, c)));
        if (!invalidDestination(related.destination())) button(screen, 30, Material.ENDER_PEARL, "Ir a región", List.of("Punto de llegada de la región padre"), p -> teleport.region(p, c.regionId()));
        else ui.button(screen, 30, Material.GRAY_DYE, "§7Ir a región", List.of(destinationStatus(related.destination())), null);
        button(screen, 13, Material.COMPASS, "Mover ciudad aquí", List.of("Guarda tu ubicación al pulsar"), p -> {
            Location at = p.getLocation().clone();
            ui.confirm(p, "§8Mover ciudad", List.of("Ciudad: " + c.id(), "Región: " + c.regionId(), location(at)), a -> safe(a, () ->
                mutate(a, "city-move " + c.id(), () -> {
                    unchanged(c); sameWorld(at, requireRegion(c.regionId()));
                    regions.moveCity(c.id(), c.regionId(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
                }, this::returnCities)), a -> city(a, c));
        });
        button(screen, 16, Material.TNT, "Eliminar ciudad", List.of("Eliminación permanente"), p -> ui.confirm(p, "§8Eliminar ciudad", cityInfo(c),
                a -> safe(a, () -> mutate(a, "city-delete " + c.id(), () -> { unchanged(c); regions.deleteCity(c.id()); }, this::returnCities)), a -> city(a, c)));
        ui.show(player, screen);
    }
    private void createCity(Player player, Region r) {
        Location at = player.getLocation().clone(); sameWorld(at, r);
        ui.prompt(player, "Nombre de ciudad: 2–64 caracteres, minúsculas, números y _.", CaloAdminMenu::validId, id -> safe(player, () ->
                ui.confirm(player, "§8Crear ciudad", List.of("Ciudad: " + id, "Región: " + r.id(), location(at)), a -> safe(a, () ->
                    mutate(a, "city-create " + id, () -> { unchanged(r); regions.createCity(id, r.id(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()); }, x -> cityList(x, r.id(), 0))), a -> region(a, r.id()))), () -> region(player, r.id()));
    }
    private void returnCities(Player p) { cityList(p, cityFilters.get(p.getUniqueId()), cityPages.getOrDefault(p.getUniqueId(), 0)); }
    private static boolean validId(String id) { return id.matches("[a-z][a-z0-9_]{1,63}"); }
    private static String location(Location at) { return "Ubicación: " + at.getWorld().getName() + " " + at.getX() + "," + at.getY() + "," + at.getZ(); }
    private static void sameWorld(Location at, Region r) {
        if (!at.getWorld().getName().equals(r.world())) throw new IllegalArgumentException("Debes estar en el mundo de la región.");
    }
    private RegionSelectionService.Selection current(Player p) { return source == null ? selections.get(p.getUniqueId()).orElse(null) : source.selection(p); }
    private RegionSelectionService.Selection complete(Player p) {
        var selection = current(p);
        if (selection == null || !selection.sameWorld()) throw new IllegalArgumentException("Completa una selección de dos posiciones en el mismo mundo.");
        return selection;
    }
    private Bounds bounds(Player p, RegionSelectionService.Selection s) {
        World world = Bukkit.getWorld(s.world());
        if (world == null) throw new IllegalArgumentException("El mundo de la selección no está cargado.");
        return s.effectiveBounds(selections.mode(p.getUniqueId()), world.getMinHeight(), world.getMaxHeight());
    }
    private List<String> selectionInfo(Player p) {
        var s = current(p);
        List<String> lines = new ArrayList<>(List.of("Origen: " + (source == null ? "wand propia" : "WorldEdit"),
                "Modo: " + selections.mode(p.getUniqueId()).name().toLowerCase(Locale.ROOT),
                "Posición 1: " + (s == null || s.first() == null ? "pendiente" : s.first().world() + " " + s.first()),
                "Posición 2: " + (s == null || s.second() == null ? "pendiente" : s.second().world() + " " + s.second())));
        if (s != null && s.sameWorld()) {
            Bounds b = bounds(p, s); lines.add("Dimensiones: " + s.dimensions(b)); lines.add("Altura: " + b.minY() + " a " + b.maxY()); lines.add("Selección válida");
        } else lines.add("Selección incompleta o mundos distintos");
        return lines;
    }
    private void selection(Player player) {
        var screen = ui.screen(player, "§8Calo · Selección", this::open);
        ui.button(screen, 4, Material.MAP, "§6Selección actual", selectionInfo(player).stream().map(s -> "§7" + s).toList(), null);
        button(screen, 10, Material.GRASS_BLOCK, "Fullheight", List.of("Toda la altura del mundo"), p -> { selections.setMode(p.getUniqueId(), RegionSelectionService.Mode.FULLHEIGHT); selection(p); });
        button(screen, 12, Material.STONE, "Exact", List.of("Altura delimitada por ambas posiciones"), p -> { selections.setMode(p.getUniqueId(), RegionSelectionService.Mode.EXACT); selection(p); });
        button(screen, 14, Material.SPONGE, "Limpiar selección", List.of("También limpia la selección de WorldEdit"), p -> {
            command.onCommand(p, plugin.getCommand("caloislands"), "calo", new String[]{"region", "clear"}); previews.clear(p.getUniqueId()); selection(p);
        });
        button(screen, 16, Material.ENDER_EYE, "Preview", List.of("Ver límites sin modificar la selección"), this::preview);
        button(screen, 28, Material.OAK_SIGN, "Crear región", List.of("Pide nombre y confirmación"), p -> {
            var selected = complete(p); Bounds area = bounds(p, selected);
            ui.prompt(p, "Nombre de región: 2–64 caracteres, minúsculas, números y _.", CaloAdminMenu::validId, id -> safe(p, () ->
                ui.confirm(p, "§8Crear región", List.of("Región: " + id, "Mundo: " + selected.world(), "Límites: " + area, "Se crea desactivada"), a -> safe(a, () -> {
                    if (!Objects.equals(current(a), selected) || !bounds(a, selected).equals(area)) throw new IllegalStateException("La selección cambió.");
                    command.onCommand(a, plugin.getCommand("caloislands"), "calo", new String[]{"region", "create", id}); regionList(a, 0);
                }), this::selection)), () -> selection(p));
        });
        button(screen, 30, Material.WOODEN_AXE, "Obtener wand", List.of(source == null ? "/calo region wand" : "Usa //wand de WorldEdit"), p -> {
            command.onCommand(p, plugin.getCommand("caloislands"), "calo", new String[]{"region", "wand"}); selection(p);
        });
        ui.show(player, screen);
    }
    private void setPreview(Player p, boolean enabled) { if (selections.previewEnabled(p.getUniqueId()) != enabled) selections.togglePreview(p.getUniqueId()); }
    private void preview(Player player) {
        var screen = ui.screen(player, "§8Calo · Preview", this::open);
        ui.button(screen, 4, Material.ENDER_EYE, selections.previewEnabled(player.getUniqueId()) ? "§aPreview activa" : "§cPreview desactivada", selectionInfo(player), null);
        button(screen, 10, Material.LIME_DYE, "Activar selección", List.of("Partículas personales · también con WorldEdit"), p -> {
            complete(p); p.closeInventory(); previews.startSelection(p.getUniqueId()); setPreview(p, true); command.messages().send(p, "preview-on");
        });
        button(screen, 13, Material.RED_DYE, "Desactivar", List.of("Conserva tu selección"), p -> { previews.stop(p.getUniqueId()); command.messages().send(p, "preview-off"); preview(p); });
        button(screen, 16, Material.SPONGE, "Limpiar preview", List.of("Conserva ambas posiciones"), p -> { previews.stop(p.getUniqueId()); command.messages().send(p, "preview-off"); preview(p); });
        button(screen, 28, Material.MAP, "Información de selección", List.of(), this::selection);
        ui.show(player, screen);
    }
    @FunctionalInterface private interface SqlQuery<T> { T get() throws SQLException; }
    private <T> void load(Player player, SqlQuery<T> query, Consumer<T> display) {
        var loading = ui.screen(player, "§8Calo · Consultando", this::open); ui.show(player, loading);
        CompletableFuture.supplyAsync(() -> { try { return query.get(); } catch (SQLException e) { throw new SQLExceptionWrapper(e); } })
                .whenComplete((value, failure) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline() || !player.hasPermission("caloislands.admin") || player.getOpenInventory().getTopInventory() != loading.getInventory()) return;
                        if (failure != null) { storageError(player); return; } safe(player, () -> display.accept(value));
                    });
                });
    }
    private void database(Player p) {
        load(p, store::schemaVersion, version -> {
            var screen = ui.screen(p, "§8Calo · MariaDB", this::open);
            ui.button(screen, 13, Material.LIME_DYE, "§aConexión disponible", List.of("§7Esquema: " + version, "§7Esperado: " + SchemaMigrator.VERSION,
                    version == SchemaMigrator.VERSION ? "§aMigraciones listas" : "§cRevisar migraciones", "§7Regiones cargadas: " + regions.regions().size()), null);
            ui.show(p, screen);
        });
    }
    private void help(Player p, int requested) {
        List<String[]> topics = List.of(
                new String[]{"Región", "Un volumen persistido de un mundo.", "Agrupa ciudades y aplica protección local."},
                new String[]{"Ciudad", "Un punto guardado dentro de una región.", "Se puede mover desde sus detalles."},
                new String[]{"Fullheight / exact", "Fullheight usa toda la altura del mundo.", "Exact respeta la altura de la selección."},
                new String[]{"WorldEdit", "Usa //wand y marca dos esquinas.", "Calo lee tu selección cuboide actual."},
                new String[]{"Wand propia", "Sin WorldEdit: /calo region wand.", "Click izquierdo: pos1; derecho: pos2."},
                new String[]{"Activar / desactivar", "Activa: operativa si el mundo está cargado.", "Desactivada: nuevas entradas bloqueadas.", "Desactiva antes de redimensionar o eliminar."},
                new String[]{"Protección y bypass", "Calo protege acciones según su configuración.", "caloislands.protection.bypass permite acciones locales.", "No omite entrada ni Lands/WorldGuard."},
                new String[]{"Ir a región / ciudad", "Región: busca un centro con suelo y espacio.", "Ciudad: valida el punto que quedó guardado.", "Respeta entrada local y protección externa."},
                new String[]{"Preview", "Las partículas son visibles solamente para ti.", "Limpiar preview conserva la selección."});
        int page = page(requested, topics.size());
        var screen = ui.screen(p, "§8Calo · Ayuda", this::open);
        for (int i = page * AdminUi.PAGE_SIZE; i < Math.min(topics.size(), (page + 1) * AdminUi.PAGE_SIZE); i++) {
            String[] topic = topics.get(i); ui.button(screen, AdminUi.CONTENT[i % AdminUi.PAGE_SIZE], Material.BOOK, "§6" + topic[0], Arrays.asList(topic).subList(1, topic.length), null);
        }
        ui.pages(screen, page, (page + 1) * AdminUi.PAGE_SIZE < topics.size(), a -> help(a, page - 1), a -> help(a, page + 1)); ui.show(p, screen);
    }
    private static int page(int requested, int count) { return Math.max(0, Math.min(requested, Math.max(0, (count - 1) / AdminUi.PAGE_SIZE))); }
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof AdminUi.Screen) previews.stop(e.getPlayer().getUniqueId());
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId(); previews.stop(id); regionPages.remove(id); cityPages.remove(id); cityFilters.remove(id);
        if (activityMenu != null) activityMenu.quit(id);
    }
}
