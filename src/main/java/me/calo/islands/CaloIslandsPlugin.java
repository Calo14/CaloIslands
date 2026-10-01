package me.calo.islands;

import me.calo.islands.command.RegionCommand;
import me.calo.islands.core.SelectionSource;
import me.calo.islands.integration.WorldEditSelectionSource;
import me.calo.islands.integration.ExternalProtection;
import me.calo.islands.integration.WorldGuardProtection;
import me.calo.islands.integration.LandsProtection;
import me.calo.islands.integration.OptionalIntegration;
import me.calo.islands.data.DatabaseConfig;
import me.calo.islands.data.DatabasePool;
import me.calo.islands.data.RegionStore;
import me.calo.islands.data.SchemaMigrator;
import me.calo.islands.domain.RegionService;
import me.calo.islands.domain.RegionSelectionService;
import me.calo.islands.listener.RegionAccessListener;
import me.calo.islands.listener.RegionSelectionListener;
import me.calo.islands.listener.SelectionSessionListener;
import me.calo.islands.core.Messages;
import me.calo.islands.core.SelectionTool;
import me.calo.islands.core.PreviewSettings;
import me.calo.islands.core.RegionPreviewService;
import me.calo.islands.core.ProtectionSettings;
import me.calo.islands.domain.RegionProtectionPolicy;
import me.calo.islands.listener.RegionProtectionListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class CaloIslandsPlugin extends JavaPlugin {
    private DatabasePool pool;
    private BukkitTask previewTask;
    private BukkitTask signalTask;
    private RegionPreviewService previewService;
    private me.calo.islands.core.ActivityService activities;
    private me.calo.islands.content.PointDefenseService pointDefense;
    private me.calo.islands.content.ObjectiveActivityService objectives;
    private me.calo.islands.content.ObjectiveCatalog objectiveCatalog;
    private me.calo.islands.content.ActivityRewardBridge rewardBridge;
    /** Future content providers bind cleanup before starting runs; no default gameplay is registered. */
    public me.calo.islands.core.ActivityService activities() {
        if (activities == null || !isEnabled()) throw new IllegalStateException("CaloIslands is unavailable");
        return activities;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) saveResource("messages.yml", false);
        if (getConfig().getInt("schema-version") != 2) {
            getLogger().severe("Unsupported config schema version; CaloIslands disabled");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            DatabaseConfig database = DatabaseConfig.read(getConfig().getConfigurationSection("database"));
            ProtectionSettings protection = ProtectionSettings.read(getConfig().getConfigurationSection("protection"));
            PreviewSettings previewSettings = PreviewSettings.read(getConfig().getConfigurationSection("preview"));
            File messageFile = new File(getDataFolder(), "messages.yml");
            Messages.audit(messageFile).forEach(getLogger()::warning);
            Messages messages = new Messages(messageFile);
            SelectionTool tool = new SelectionTool(this, getConfig().getString("wand.material", "WOODEN_AXE"), messages);
            pool = new DatabasePool(database);
            new SchemaMigrator(pool.dataSource()).migrate();
            RegionStore store = new RegionStore(pool.dataSource());
            RegionService regions = new RegionService(store, name -> {
                var world = Bukkit.getWorld(name);
                return world == null ? java.util.Optional.empty() : java.util.Optional.of(
                        new RegionService.WorldHeight(world.getMinHeight(), world.getMaxHeight()));
            });
            RegionSelectionService selections = new RegionSelectionService();
            var worldEdit = OptionalIntegration.load(Bukkit.getPluginManager(),
                    "WorldEdit", WorldEditSelectionSource::new);
            logIntegration("WorldEdit", worldEdit);
            SelectionSource externalSelection = worldEdit.adapter();
            RegionPreviewService preview = new RegionPreviewService(selections, previewSettings, messages, externalSelection);
            previewService = preview;
            preview.setRegions(regions::regions);
            RegionCommand command = new RegionCommand(regions, selections, tool, messages, externalSelection);
            command.setValidator(() -> me.calo.islands.core.CaloValidation.inspect(this, regions));
            getCommand("caloislands").setExecutor(command);
            getCommand("caloislands").setTabCompleter(command);
            List<ExternalProtection> authorities = new ArrayList<>();
            loadProtection("WorldGuard", WorldGuardProtection::new, authorities);
            loadProtection("Lands", () -> new LandsProtection(this), authorities);
            var ui = new me.calo.islands.gui.AdminUi(this, "caloislands.admin");
            List<ExternalProtection> teleportAuthorities = new ArrayList<>(authorities);
            for (String name : List.of("Lands", "WorldGuard")) {
                if (getServer().getPluginManager().getPlugin(name) != null && !getServer().getPluginManager().isPluginEnabled(name))
                    teleportAuthorities.add((player, action, location) -> true);
            }
            var teleport = new me.calo.islands.core.AdminTeleportService(regions, teleportAuthorities, messages, protection.denyActiveEntry());
            activities = new me.calo.islands.core.ActivityService(new me.calo.islands.data.ActivityStore(pool.dataSource()),
                    regions, Bukkit::isPrimaryThread, (player, definition) -> teleport.canAccess(player, definition.entry()),
                    signal -> Bukkit.getPluginManager().callEvent(new me.calo.islands.content.ActivitySignalEvent(signal)));
            var defenseSettings = me.calo.islands.content.PointDefenseSettings.read(
                    getConfig().getConfigurationSection("activity.defense"));
            if (defenseSettings != null) {
                pointDefense = new me.calo.islands.content.PointDefenseService(
                        this, activities, regions, defenseSettings);
                pointDefense.start();
            }
            var objectiveSettings = me.calo.islands.content.ObjectiveSettings.read(
                    getConfig().getConfigurationSection("activity.objectives"));
            if (defenseSettings != null && objectiveSettings.containsKey(defenseSettings.id()))
                throw new IllegalArgumentException("activity.defense and activity.objectives share an id");
            objectiveCatalog = new me.calo.islands.content.ObjectiveCatalog(
                    new me.calo.islands.data.ObjectiveDefinitionStore(pool.dataSource()), regions,
                    teleport::canAccess, objectiveSettings,
                    defenseSettings == null ? java.util.Set.of() : java.util.Set.of(defenseSettings.id()));
            if (defenseSettings != null && objectiveCatalog.list().stream().anyMatch(d -> d.id().equals(defenseSettings.id())))
                throw new IllegalArgumentException("activity.defense and managed objectives share an id");
            objectives = new me.calo.islands.content.ObjectiveActivityService(this, activities,
                    regions, objectiveCatalog.enabledSettings(), objectiveCatalog.enabledStarts(),
                    (player, action, location) -> {
                        try {
                            return teleportAuthorities.stream()
                                    .noneMatch(authority -> authority.deniesEntry(player, location)
                                            || authority.denies(player, action, location));
                        } catch (RuntimeException | LinkageError unavailable) { return false; }
                    });
            objectives.start();
            objectiveCatalog.attach(objectives);
            command.setValidator(() -> me.calo.islands.core.CaloValidation.inspect(this, regions, objectiveCatalog));
            java.util.Set<String> rewardContent = new java.util.HashSet<>(objectiveSettings.keySet());
            objectiveCatalog.list().forEach(value -> rewardContent.add(value.id()));
            if (defenseSettings != null) rewardContent.add(defenseSettings.id());
            var rewardRules = me.calo.islands.content.ActivityRewardRules.read(
                    getConfig().getConfigurationSection("activity.reward-policy"), rewardContent);
            rewardBridge = new me.calo.islands.content.ActivityRewardBridge(this, activities,
                    rewardRules, me.calo.islands.content.ActivityRewardBridge.goldenClient(this));
            signalTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try { activities.dispatchPendingSignals(100); rewardBridge.tick(); }
                catch (Exception failure) { getLogger().warning("Activity signal retry pending: "
                        + failure.getClass().getSimpleName()); }
            }, 1L, 20L);
            getCommand("caloactivity").setExecutor(new me.calo.islands.command.PointDefenseCommand(
                    pointDefense, objectives, getLogger()));
            var menu = new me.calo.islands.gui.CaloAdminMenu(this, ui, regions, selections, externalSelection,
                    preview, command, teleport, store);
            menu.setObjectiveService(objectives);
            menu.setObjectiveCatalog(objectiveCatalog);
            command.setMenu(menu::open);
            command.setServices(teleport, preview);
            Bukkit.getPluginManager().registerEvents(ui, this);
            Bukkit.getPluginManager().registerEvents(menu, this);
            Bukkit.getPluginManager().registerEvents(new RegionAccessListener(regions, messages, protection), this);
            Bukkit.getPluginManager().registerEvents(new RegionProtectionListener(
                    new RegionProtectionPolicy(regions, protection, authorities), objectives), this);
            Bukkit.getPluginManager().registerEvents(new SelectionSessionListener(selections, externalSelection), this);
            if (externalSelection == null)
                Bukkit.getPluginManager().registerEvents(new RegionSelectionListener(selections, tool, messages), this);
            previewTask = Bukkit.getScheduler().runTaskTimer(this,
                        () -> preview.renderFrame(Bukkit.getOnlinePlayers()), 1L, previewSettings.intervalTicks());
            long pending = regions.regions().stream().filter(r -> r.active() && !regions.operational(r)).count();
            getLogger().info("MariaDB region schema v" + SchemaMigrator.VERSION + " ready; " + regions.regions().size() + " regions, "
                    + regions.cities().size() + " cities, " + pending + " awaiting a loaded world");
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid CaloIslands configuration: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        } catch (Exception e) {
            getLogger().severe("Cannot initialize CaloIslands region store: " + e.getClass().getSimpleName());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void reloadConfig() {
        if (previewService != null) previewService.clearAll();
        super.reloadConfig();
        if (previewService != null) {
            PreviewSettings settings = PreviewSettings.read(getConfig().getConfigurationSection("preview"));
            previewService.updateSettings(settings);
            if (previewTask != null) previewTask.cancel();
            previewTask = Bukkit.getScheduler().runTaskTimer(this,
                    () -> previewService.renderFrame(Bukkit.getOnlinePlayers()), 1L, settings.intervalTicks());
        }
    }

    @Override
    public void onDisable() {
        if (signalTask != null) { signalTask.cancel(); signalTask = null; }
        if (rewardBridge != null) { rewardBridge.stop(); rewardBridge = null; }
        if (previewService != null) previewService.clearAll();
        if (pointDefense != null) { pointDefense.stop(); pointDefense = null; }
        if (objectives != null) { objectives.stop(); objectives = null; }
        if (activities != null) {
            try { activities.shutdown(); }
            catch (Exception failure) { getLogger().warning("Activity cleanup needs recovery: " + failure.getClass().getSimpleName()); }
            activities = null;
        }
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    private void loadProtection(String pluginName, Supplier<ExternalProtection> factory,
                                List<ExternalProtection> adapters) {
        var result = OptionalIntegration.load(Bukkit.getPluginManager(), pluginName, factory);
        logIntegration(pluginName, result);
        if (result.status() == OptionalIntegration.Status.LOADED) adapters.add(result.adapter());
        if (result.status() == OptionalIntegration.Status.INCOMPATIBLE)
            adapters.add((player, action, location) -> true);
    }

    private void logIntegration(String name, OptionalIntegration.Result<?> result) {
        boolean selector = name.equals("WorldEdit");
        switch (result.status()) {
            case ABSENT -> getLogger().warning(name + " absent; "
                    + (selector ? "built-in wand selected." : "local region protection remains active."));
            case DISABLED -> getLogger().warning(name + " installed but disabled (" + result.version() + "); "
                    + (selector ? "built-in wand selected." : "local region protection remains active."));
            case LOADED -> getLogger().info(name + " API adapter loaded (" + result.version() + "); "
                    + (selector ? "WorldEdit selects regions." : "local region protection remains active."));
            case INCOMPATIBLE -> getLogger().warning(name + " API incompatible (" + result.version()
                    + ", " + result.failure() + "); " + (selector ? "built-in wand selected."
                    : "protected CaloIslands actions fail closed."));
        }
    }
}
