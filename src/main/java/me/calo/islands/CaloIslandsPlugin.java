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
            Messages messages = new Messages(new File(getDataFolder(), "messages.yml"));
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
            RegionCommand command = new RegionCommand(regions, selections, tool, messages, externalSelection);
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
            var menu = new me.calo.islands.gui.CaloAdminMenu(this, ui, regions, selections, externalSelection,
                    preview, command, new me.calo.islands.core.AdminTeleportService(regions, teleportAuthorities), store);
            command.setMenu(menu::open);
            Bukkit.getPluginManager().registerEvents(ui, this);
            Bukkit.getPluginManager().registerEvents(menu, this);
            Bukkit.getPluginManager().registerEvents(new RegionAccessListener(regions, messages, protection), this);
            Bukkit.getPluginManager().registerEvents(new RegionProtectionListener(
                    new RegionProtectionPolicy(regions, protection, authorities)), this);
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
    public void onDisable() {
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
