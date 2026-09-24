package me.calo.islands.integration;

import org.bukkit.plugin.PluginManager;
import java.util.function.Supplier;

/** Resolves an optional Bukkit plugin without loading its adapter while absent or disabled. */
public final class OptionalIntegration {
    private OptionalIntegration() { }

    public enum Status { ABSENT, DISABLED, LOADED, INCOMPATIBLE }

    public record Result<T>(Status status, T adapter, String version, String failure) { }

    public static <T> Result<T> load(PluginManager manager, String name, Supplier<T> factory) {
        var plugin = manager.getPlugin(name);
        if (plugin == null) return new Result<>(Status.ABSENT, null, "", "");
        String version = plugin.getDescription().getVersion();
        if (!plugin.isEnabled()) return new Result<>(Status.DISABLED, null, version, "");
        try {
            return new Result<>(Status.LOADED, factory.get(), version, "");
        } catch (RuntimeException | LinkageError failure) {
            return new Result<>(Status.INCOMPATIBLE, null, version, failure.getClass().getSimpleName());
        }
    }
}
