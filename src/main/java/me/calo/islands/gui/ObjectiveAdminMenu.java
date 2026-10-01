package me.calo.islands.gui;

import me.calo.islands.content.*;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Staff forms for the existing objective runtime. Drafts never affect live runs until saved. */
public final class ObjectiveAdminMenu {
    private final AdminUi ui;
    private final ObjectiveCatalog catalog;
    private final ObjectiveActivityService runtime;
    private final RegionService regions;
    private final Consumer<Player> back;
    private final Map<UUID, Draft> drafts = new HashMap<>();

    public ObjectiveAdminMenu(AdminUi ui, ObjectiveCatalog catalog, ObjectiveActivityService runtime,
                              RegionService regions, Consumer<Player> back) {
        this.ui = ui; this.catalog = catalog; this.runtime = runtime; this.regions = regions; this.back = back;
    }
    public void quit(UUID player) { drafts.remove(player); }
    private void action(Player player, Checked action) {
        try { action.run(); }
        catch (SQLException failure) { player.sendMessage("§cMariaDB no confirmó la operación."); list(player, 0); }
        catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage("§c" + MenuText.error(failure.getMessage())); list(player, 0);
        }
    }
    @FunctionalInterface private interface Checked { void run() throws SQLException; }
    private void button(AdminUi.Screen screen, int slot, Material icon, String name, List<String> lore, Consumer<Player> click) {
        ui.button(screen, slot, icon, "§6" + name, lore, click == null ? null : p -> action(p, () -> click.accept(p)));
    }
    public void list(Player player, int requested) {
        List<ManagedObjective> all = catalog.list();
        int page = Math.max(0, Math.min(requested, Math.max(0, (all.size()-1)/AdminUi.PAGE_SIZE)));
        var screen = ui.screen(player, "§8Actividades", back);
        for (int i = page * AdminUi.PAGE_SIZE; i < Math.min(all.size(), (page+1)*AdminUi.PAGE_SIZE); i++) {
            ManagedObjective value = all.get(i);
            button(screen, AdminUi.CONTENT[i % AdminUi.PAGE_SIZE], value.enabled() ? Material.COMPASS : Material.GRAY_DYE,
                    value.settings().name(), List.of("§7Tipo: §f" + mode(value.settings().mode()),
                            "§7Región: §f" + MenuText.label(value.settings().regionId()),
                            "§7Estado: " + (value.enabled() ? "§aActiva" : "§cDesactivada"),
                            "§7Clic para ver progreso y administrar"), p -> detail(p, value.id()));
        }
        if (all.isEmpty()) ui.button(screen, 22, Material.GRAY_DYE, "§7Sin actividades", List.of("§7Crea una desde este menú."), null);
        button(screen, 40, Material.LIME_CONCRETE, "Crear actividad", List.of("§7Elige un tipo y completa los objetivos."), this::types);
        ui.pages(screen, page, (page+1)*AdminUi.PAGE_SIZE < all.size(),
                p -> list(p, page-1), p -> list(p, page+1));
        ui.show(player, screen);
    }
    private void detail(Player player, String id) {
        ManagedObjective value = catalog.require(id); ObjectiveSettings s = value.settings();
        var screen = ui.screen(player, "§8Actividad · " + s.name(), p -> list(p, 0));
        List<String> info = new ArrayList<>(List.of("§7Tipo: §f" + mode(s.mode()),
                "§7Región: §f" + MenuText.label(s.regionId()),
                "§7Inicio: §f" + point(value.start()),
                "§7Objetivo: §f" + s.goal(), "§7Duración: §f" + s.timeoutSeconds() + " s",
                "§7Estado: " + (value.enabled() ? "§aActiva" : "§cDesactivada"),
                "§7Recompensa: §eWAITING_POLICY"));
        try {
            var run = runtime.current(id);
            if (run.isPresent()) {
                List<ActivityMember> members = runtime.members(run.get().runId());
                info.add("§7Ejecución: §aEn curso");
                info.add("§7Progreso: §f" + run.get().progress() + "/" + s.goal());
                info.add("§7Participantes: §f" + members.stream().filter(ActivityMember::active).count());
                info.add("§7Contribución grupal: §f" + members.stream().mapToLong(ActivityMember::contribution).sum());
                button(screen, 24, Material.PLAYER_HEAD, "Participantes y contribución", info,
                        p -> members(p, id, 0));
                button(screen, 32, Material.BARRIER, "Cancelar ejecución", List.of("§7Libera participantes y entidades."),
                        p -> ui.confirm(p, "§8Cancelar actividad", info,
                                a -> action(a, () -> { catalog.cancel(a, id); detail(a, id); }), a -> detail(a, id)));
            } else info.add("§7Ejecución: §eEn espera");
        } catch (SQLException failure) { throw new IllegalStateException("No se pudo consultar la ejecución."); }
        ui.button(screen, 4, Material.PAPER, "§6" + s.name(), info, null);
        button(screen, 10, Material.WRITABLE_BOOK, "Editar", List.of("§7Solo cuando no hay ejecución activa."), p -> {
            drafts.put(p.getUniqueId(), new Draft(value)); edit(p);
        });
        button(screen, 12, value.enabled() ? Material.REDSTONE_TORCH : Material.TORCH,
                value.enabled() ? "Desactivar" : "Activar", List.of("§7Guarda el estado en MariaDB."),
                p -> action(p, () -> { catalog.setEnabled(p, id, value.revision(), !value.enabled()); detail(p, id); }));
        button(screen, 14, Material.TNT, "Eliminar", List.of("§7Requiere confirmación."),
                p -> ui.confirm(p, "§8Eliminar actividad", info, a -> action(a, () -> {
                    catalog.delete(a, id, value.revision()); list(a, 0);
                }), a -> detail(a, id)));
        ui.show(player, screen);
    }
    private void members(Player player, String id, int requested) {
        try {
            var run = runtime.current(id).orElseThrow(() -> new IllegalStateException("La ejecución terminó."));
            List<ActivityMember> all = runtime.members(run.runId()).stream()
                    .sorted(Comparator.comparingLong(ActivityMember::contribution).reversed()).toList();
            int page = Math.max(0, Math.min(requested, Math.max(0, (all.size()-1)/AdminUi.PAGE_SIZE)));
            var screen = ui.screen(player, "§8Participantes", p -> detail(p, id));
            for (int i = page*AdminUi.PAGE_SIZE; i < Math.min(all.size(),(page+1)*AdminUi.PAGE_SIZE); i++) {
                ActivityMember member = all.get(i);
                String name = Bukkit.getOfflinePlayer(member.playerId()).getName();
                ui.button(screen, AdminUi.CONTENT[i%AdminUi.PAGE_SIZE], Material.PLAYER_HEAD,
                        "§6" + (name == null ? "Participante" : name),
                        List.of("§7Contribución: §f" + member.contribution(),
                                member.active() ? "§aEn actividad" : "§eDesconectado"), null);
            }
            ui.pages(screen, page, (page+1)*AdminUi.PAGE_SIZE<all.size(),
                    p -> members(p,id,page-1), p -> members(p,id,page+1)); ui.show(player, screen);
        } catch (SQLException failure) { throw new IllegalStateException("No se pudieron consultar participantes."); }
    }
    private void types(Player player) {
        var screen = ui.screen(player, "§8Tipo de actividad", p -> list(p,0));
        ObjectiveSettings.Mode[] values = ObjectiveSettings.Mode.values();
        for (int i=0;i<values.length;i++) {
            ObjectiveSettings.Mode selected = values[i];
            button(screen, AdminUi.CONTENT[i], Material.MAP, mode(selected), List.of("§7Configura inicio, objetivo y duración."), p -> {
                Draft draft = new Draft(selected); drafts.put(p.getUniqueId(), draft); edit(p);
            });
        }
        ui.show(player, screen);
    }
    private void edit(Player player) {
        Draft d = drafts.get(player.getUniqueId());
        if (d == null) { list(player,0); return; }
        var screen = ui.screen(player, "§8Editar actividad", p -> {
            drafts.remove(p.getUniqueId()); if (d.existing == null) list(p,0); else detail(p,d.id);
        });
        ui.button(screen, 4, Material.PAPER, "§6" + (d.name.isBlank() ? "Nueva actividad" : d.name),
                List.of("§7Tipo: §f"+mode(d.mode), "§7Región: §f"+(d.region==null ? "Pendiente" : MenuText.label(d.region)),
                        "§7Inicio: §f"+(d.start==null ? "Pendiente" : point(d.start)),
                        "§7Objetivo: §f"+(d.goal==0 ? "Pendiente" : d.goal),
                        "§7Duración: §f"+(d.timeout==0 ? "Pendiente" : d.timeout+" s"),
                        "§7Recompensa: §eWAITING_POLICY"), null);
        if (d.existing == null) button(screen,10,Material.NAME_TAG,"Identificador",List.of("§7"+d.id),p -> input(p,d,"ID: letras minúsculas, números y _.",
                raw -> raw.matches("[a-z][a-z0-9_]{1,63}"),raw -> d.id=raw));
        button(screen,11,Material.OAK_SIGN,"Nombre",List.of("§7"+d.name),p -> input(p,d,"Nombre visible (1–64 caracteres).",
                raw -> !raw.isBlank() && raw.length()<=64 && raw.chars().noneMatch(Character::isISOControl),raw -> d.name=raw));
        button(screen,12,Material.MAP,"Tipo: "+mode(d.mode),List.of("§7Selecciona uno de los seis objetivos."),p -> modePicker(p,d));
        button(screen,13,Material.GRASS_BLOCK,"Región",List.of("§7"+(d.region==null?"Seleccionar":MenuText.label(d.region))),p -> regions(p,d,0));
        button(screen,14,Material.COMPASS,"Inicio: posición actual",List.of("§7Guarda tu posición y orientación."),p -> {
            d.start=destination(p.getLocation()); edit(p);
        });
        button(screen,15,Material.ENDER_PEARL,"Inicio: punto de región",List.of("§7Usa el punto guardado de la región."),p -> {
            if (d.region==null) throw new IllegalArgumentException("Elige una región primero.");
            d.start=regions.region(d.region).orElseThrow().destination();
            if (d.start==null) throw new IllegalArgumentException("La región no tiene punto guardado."); edit(p);
        });
        numeric(screen,16,Material.TARGET,"Radio (1–64)",d.radius,p -> input(p,d,"Radio en bloques (mayor que 0, máximo 64).",
                raw -> decimal(raw,0,64),raw -> d.radius=Double.parseDouble(raw)));
        if (d.mode!=ObjectiveSettings.Mode.ESCORT)
            numeric(screen,19,Material.EMERALD,"Objetivo (1–10000)",d.goal,p -> input(p,d,"Cantidad objetivo (1–10000).",
                    raw -> integer(raw,1,10000),raw -> d.goal=Integer.parseInt(raw)));
        else ui.button(screen,19,Material.EMERALD,"§6Objetivo de ruta",List.of("§7Calculado: "+d.escortGoal()),null);
        numeric(screen,20,Material.CLOCK,"Duración en segundos",d.timeout,p -> input(p,d,"Duración (1–86400 segundos).",
                raw -> integer(raw,1,86400),raw -> d.timeout=Integer.parseInt(raw)));
        numeric(screen,21,Material.PLAYER_HEAD,"Máximo participantes",d.participants,p -> input(p,d,"Participantes (1–32).",
                raw -> integer(raw,1,32),raw -> d.participants=Integer.parseInt(raw)));
        numeric(screen,22,Material.REDSTONE,"Límite por jugador",d.perPlayer,p -> input(p,d,"Contribución máxima por jugador (1–100000).",
                raw -> integer(raw,1,100000),raw -> d.perPlayer=Long.parseLong(raw)));
        switch (d.mode) {
            case CAPTURE_CONTROL -> numeric(screen,23,Material.ORANGE_BANNER,"Umbral de captura",d.captureAt,
                    p -> input(p,d,"Puntos para pasar de captura a control.",raw -> integer(raw,1,9999),
                            raw -> d.captureAt=Integer.parseInt(raw)));
            case STRUCTURE, COLLECTION -> button(screen,23,Material.STONE,"Bloque objetivo",List.of("§7"+(d.block==null?"Pendiente":d.block.name())),
                    p -> input(p,d,"Material de bloque de Paper.",raw -> {
                        Material material=Material.matchMaterial(raw); return material!=null && material.isBlock() && material!=Material.AIR;
                    },raw -> d.block=Material.matchMaterial(raw)));
            case MOB_EVENT -> button(screen,23,Material.ZOMBIE_HEAD,"Mob normal",List.of("§7"+(d.mob==null?"Pendiente":d.mob.name())),
                    p -> input(p,d,"Tipo de mob normal de Paper.",raw -> {
                        try { EntityType type=EntityType.valueOf(raw.toUpperCase(Locale.ROOT)); return ObjectiveCatalog.ordinaryMob(type); }
                        catch (IllegalArgumentException invalid) { return false; }
                    },raw -> d.mob=EntityType.valueOf(raw.toUpperCase(Locale.ROOT))));
            case ESCORT -> {
                numeric(screen,23,Material.RAIL,"Bloques por paso",d.step,
                        p -> input(p,d,"Distancia por segundo (mayor que 0, máximo 16).",
                                raw -> decimal(raw,0,16),raw -> d.step=Double.parseDouble(raw)));
                button(screen,24,Material.COMPASS,"Añadir punto de ruta",List.of("§7Puntos: "+d.route.size(),"§7Usa tu posición actual."),p -> {
                    if (d.route.size()>=32) throw new IllegalArgumentException("La ruta admite máximo 32 puntos.");
                    Location at=p.getLocation(); d.route.add(new ObjectiveSettings.Point(at.getX(),at.getY(),at.getZ())); edit(p);
                });
                button(screen,25,Material.SPONGE,"Quitar último punto",List.of("§7Puntos: "+d.route.size()),p -> {
                    if (!d.route.isEmpty()) d.route.removeLast(); edit(p);
                });
            }
            default -> { }
        }
        button(screen,30,Material.LIME_CONCRETE,"Guardar",List.of("§7Valida todos los campos y pide confirmación."),p -> {
            Draft snapshot=d.copy(); ObjectiveSettings settings=snapshot.build();
            if (snapshot.start==null) throw new IllegalArgumentException("Falta el punto de inicio.");
            ui.confirm(p,"§8Guardar actividad",List.of("Nombre: "+settings.name(),"Región: "+MenuText.label(settings.regionId()),
                    "Inicio: "+point(snapshot.start),"Objetivo: "+settings.goal(),"Duración: "+settings.timeoutSeconds()+" s",
                    "Recompensa: WAITING_POLICY"),a -> action(a,() -> {
                if (snapshot.existing==null) catalog.create(a,settings,snapshot.start);
                else catalog.edit(a,snapshot.id,snapshot.existing.revision(),settings,snapshot.start);
                drafts.remove(a.getUniqueId()); detail(a,snapshot.id);
            }),a -> edit(a));
        });
        ui.show(player,screen);
    }
    private void modePicker(Player player,Draft d) {
        var screen=ui.screen(player,"§8Elegir tipo",this::edit);
        ObjectiveSettings.Mode[] values=ObjectiveSettings.Mode.values();
        for(int i=0;i<values.length;i++) {
            ObjectiveSettings.Mode selected=values[i];
            button(screen,AdminUi.CONTENT[i],Material.MAP,mode(selected),List.of(),p -> {
                d.mode=selected; d.captureAt=0; d.block=null; d.mob=null; d.route.clear(); d.step=1; edit(p);
            });
        }
        ui.show(player,screen);
    }
    private void regions(Player player,Draft d,int requested) {
        List<Region> all=regions.regions().stream().sorted(Comparator.comparing(Region::id)).toList();
        int page=Math.max(0,Math.min(requested,Math.max(0,(all.size()-1)/AdminUi.PAGE_SIZE)));
        var screen=ui.screen(player,"§8Elegir región",this::edit);
        for(int i=page*AdminUi.PAGE_SIZE;i<Math.min(all.size(),(page+1)*AdminUi.PAGE_SIZE);i++) {
            Region region=all.get(i);
            button(screen,AdminUi.CONTENT[i%AdminUi.PAGE_SIZE],Material.GRASS_BLOCK,MenuText.label(region.id()),
                    List.of("§7Mundo: "+region.world(),"§7Punto: "+(region.destination()==null?"Sin configurar":point(region.destination()))),p -> {
                d.region=region.id(); d.start=null; d.route.clear(); edit(p);
            });
        }
        ui.pages(screen,page,(page+1)*AdminUi.PAGE_SIZE<all.size(),p -> regions(p,d,page-1),p -> regions(p,d,page+1));
        ui.show(player,screen);
    }
    private void input(Player player,Draft draft,String instruction,Predicate<String> valid,Consumer<String> accept) {
        ui.prompt(player,instruction,valid,raw -> action(player,() -> { accept.accept(raw); edit(player); }),() -> edit(player));
    }
    private void numeric(AdminUi.Screen screen,int slot,Material icon,String label,Number value,Consumer<Player> action) {
        button(screen,slot,icon,label,List.of("§7Actual: "+value),action);
    }
    private static boolean integer(String raw,int min,int max) {
        try { int value=Integer.parseInt(raw); return value>=min&&value<=max; }
        catch(NumberFormatException invalid) { return false; }
    }
    private static boolean decimal(String raw,double minExclusive,double max) {
        try { double value=Double.parseDouble(raw); return Double.isFinite(value)&&value>minExclusive&&value<=max; }
        catch(NumberFormatException invalid) { return false; }
    }
    private static Destination destination(Location location) {
        return new Destination(location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),
                location.getYaw(),location.getPitch());
    }
    private static String point(Destination d) {
        return d.world()+" · "+String.format(Locale.US,"%.0f, %.0f, %.0f",d.x(),d.y(),d.z());
    }
    private static String mode(ObjectiveSettings.Mode mode) {
        return switch(mode) {
            case DEFENSE -> "Defensa"; case CAPTURE_CONTROL -> "Captura y control";
            case STRUCTURE -> "Activación de estructuras"; case ESCORT -> "Escolta";
            case COLLECTION -> "Recolección"; case MOB_EVENT -> "Mobs normales";
        };
    }
    private static final class Draft {
        final ManagedObjective existing;
        String id="",name="",region;
        Destination start;
        ObjectiveSettings.Mode mode;
        double radius=8,step=1;
        int goal,timeout,participants=1,captureAt;
        long perPlayer=100;
        Material block; EntityType mob;
        final List<ObjectiveSettings.Point> route=new ArrayList<>();
        Draft(ObjectiveSettings.Mode mode) { this.existing=null; this.mode=mode; }
        Draft(ManagedObjective value) {
            existing=value; ObjectiveSettings s=value.settings(); id=s.id();name=s.name();region=s.regionId();
            start=value.start(); mode=s.mode();radius=s.radius();step=s.stepBlocks();goal=s.goal();
            timeout=s.timeoutSeconds();participants=s.maxParticipants();captureAt=s.captureAt();
            perPlayer=s.perPlayerLimit();block=s.blockMaterial();mob=s.mobType();route.addAll(s.route());
        }
        Draft copy() {
            Draft next=new Draft(mode);
            next.id=id;next.name=name;next.region=region;next.start=start;next.radius=radius;next.step=step;
            next.goal=goal;next.timeout=timeout;next.participants=participants;next.captureAt=captureAt;
            next.perPlayer=perPlayer;next.block=block;next.mob=mob;next.route.addAll(route);
            // Existing revision is retained by a separate constructor below.
            return existing==null?next:new Draft(this);
        }
        private Draft(Draft source) {
            existing=source.existing;id=source.id;name=source.name;region=source.region;start=source.start;
            mode=source.mode;radius=source.radius;step=source.step;goal=source.goal;timeout=source.timeout;
            participants=source.participants;captureAt=source.captureAt;perPlayer=source.perPlayer;
            block=source.block;mob=source.mob;route.addAll(source.route);
        }
        int escortGoal() {
            if(route.size()<2||step<=0) return 0;
            double length=0;
            for(int i=1;i<route.size();i++) {
                var a=route.get(i-1);var b=route.get(i);
                length+=Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
            }
            return (int)Math.ceil(length/step);
        }
        ObjectiveSettings build() {
            if(start==null||region==null||id.isBlank()||name.isBlank()||timeout==0)
                throw new IllegalArgumentException("Completa ID, nombre, región, inicio y duración.");
            return new ObjectiveSettings(id,name,mode,region,radius,mode==ObjectiveSettings.Mode.ESCORT?escortGoal():goal,
                    captureAt,participants,perPlayer,timeout,block,mob,route,mode==ObjectiveSettings.Mode.ESCORT?step:0);
        }
    }
}
