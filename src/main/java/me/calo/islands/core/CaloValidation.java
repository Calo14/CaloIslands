package me.calo.islands.core;

import me.calo.islands.content.ActivityRewardRules;
import me.calo.islands.content.ObjectiveSettings;
import me.calo.islands.content.ObjectiveCatalog;
import me.calo.islands.content.PointDefenseSettings;
import me.calo.islands.data.DatabaseConfig;
import me.calo.islands.domain.RegionService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Read-only administrator validation; reports paths and causes without database secrets. */
public final class CaloValidation {
    private CaloValidation() { }
    public static List<String> inspect(JavaPlugin plugin, RegionService regions) {
        return inspect(plugin, regions, null);
    }
    public static List<String> inspect(JavaPlugin plugin, RegionService regions, ObjectiveCatalog catalog) {
        List<String> issues = new ArrayList<>();
        File config = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (!config.isFile() || config.length() > 1_048_576)
                throw new IllegalArgumentException("config.yml ausente o demasiado grande");
            yaml.load(config);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException failure) {
            issues.add("ERROR config.yml: YAML ausente o inválido.");
            return List.copyOf(issues);
        }
        if (yaml.getInt("schema-version", -1) != 2)
            issues.add("ERROR config.yml: schema-version incompatible.");
        try { DatabaseConfig.read(yaml.getConfigurationSection("database")); }
        catch (IllegalArgumentException failure) { issues.add("ERROR config.yml: database inválida."); }
        try { ProtectionSettings.read(yaml.getConfigurationSection("protection")); }
        catch (IllegalArgumentException failure) { issues.add("ERROR config.yml: protection inválida."); }
        try { PreviewSettings.read(yaml.getConfigurationSection("preview")); }
        catch (IllegalArgumentException failure) { issues.add("ERROR config.yml: preview inválida."); }
        PointDefenseSettings defense = null;
        try { defense = PointDefenseSettings.read(yaml.getConfigurationSection("activity.defense")); }
        catch (IllegalArgumentException failure) { issues.add("ERROR config.yml: activity.defense inválida."); }
        var ids = new HashSet<String>();
        if (defense != null) {
            ids.add(defense.id());
            if (regions.region(defense.regionId()).filter(r -> r.destination() != null).isEmpty())
                issues.add("ERROR config.yml: activity.defense requiere región y punto guardado.");
        }
        try {
            var objectives = ObjectiveSettings.read(yaml.getConfigurationSection("activity.objectives"));
            if (defense != null && objectives.containsKey(defense.id()))
                issues.add("ERROR config.yml: activity.defense y activity.objectives comparten ID.");
            ids.addAll(objectives.keySet());
            for (var objective : objectives.values()) {
                var region = regions.region(objective.regionId());
                if (region.isEmpty() || region.get().destination() == null
                        || !region.get().bounds().contains(region.get().destination().x(),
                        region.get().destination().y(), region.get().destination().z()))
                    issues.add("ERROR config.yml: activity.objectives." + objective.id()
                            + " requiere región y punto guardado.");
                else if (objective.mode() == ObjectiveSettings.Mode.ESCORT) {
                    var point = region.get().destination();
                    var first = objective.route().getFirst();
                    boolean inside = objective.route().stream().allMatch(route ->
                            region.get().bounds().contains(route.x(), route.y(), route.z()));
                    double startDistance = Math.pow(first.x() - point.x(), 2)
                            + Math.pow(first.y() - point.y(), 2) + Math.pow(first.z() - point.z(), 2);
                    if (!inside || startDistance > objective.radius() * objective.radius())
                        issues.add("ERROR config.yml: activity.objectives." + objective.id()
                                + " tiene ruta fuera de la región o del punto de inicio.");
                }
            }
        } catch (IllegalArgumentException failure) {
            issues.add("ERROR config.yml: activity.objectives inválida.");
        }
        if (catalog != null) for (var value : catalog.list()) {
            ids.add(value.id());
            var region = regions.region(value.settings().regionId());
            var start = value.start();
            if (region.isEmpty() || !region.get().world().equals(start.world())
                    || !region.get().bounds().contains(start.x(), start.y(), start.z())) {
                issues.add("ERROR MariaDB: actividad " + value.id() + " tiene inicio fuera de su región.");
                continue;
            }
            if (value.settings().mode() == ObjectiveSettings.Mode.ESCORT) {
                var first = value.settings().route().getFirst();
                boolean inside = value.settings().route().stream().allMatch(point ->
                        region.get().bounds().contains(point.x(), point.y(), point.z()));
                double distance = Math.pow(first.x()-start.x(),2)+Math.pow(first.y()-start.y(),2)
                        +Math.pow(first.z()-start.z(),2);
                if (!inside || distance > value.settings().radius()*value.settings().radius())
                    issues.add("ERROR MariaDB: actividad " + value.id() + " tiene ruta fuera de su región.");
            }
        }
        try { ActivityRewardRules.read(yaml.getConfigurationSection("activity.reward-policy"), ids); }
        catch (IllegalArgumentException failure) { issues.add("ERROR config.yml: activity.reward-policy inválida."); }
        File messages = new File(plugin.getDataFolder(), "messages.yml");
        if (!messages.isFile() || messages.length() > 1_048_576)
            issues.add("ERROR messages.yml: archivo ausente o demasiado grande.");
        else issues.addAll(Messages.audit(messages));
        return List.copyOf(issues);
    }
}
