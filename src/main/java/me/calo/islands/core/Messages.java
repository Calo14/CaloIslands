package me.calo.islands.core;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.List;

public final class Messages {
    private static final Set<String> REQUIRED = Set.of(
            "no-permission", "error", "storage-error", "region-list", "city-list", "region-info", "city-info",
            "region-created", "region-resized", "region-state", "region-deleted", "city-updated",
            "city-region-missing", "city-world-inconsistent",
            "city-info-v2", "city-updated-v2", "city-state-active", "city-state-inactive",
            "city-deleted", "here-region", "here-empty", "player-only", "wrong-arguments",
            "unknown-region", "unknown-city", "world-unloaded", "height-invalid", "entry-denied", "help-region", "help-region-edit",
            "help-city", "help-here", "wand-name", "wand-given", "wand-inventory-full",
            "selection-first", "selection-second", "selection-empty", "selection-status",
            "selection-incomplete", "selection-world-mismatch", "selection-region-world-mismatch",
            "selection-cleared", "city-world-mismatch", "help-selection",
            "selection-unset", "selection-worlds-mixed", "preview-on", "preview-off",
            "preview-actionbar", "mode-set", "mode-invalid", "worldedit-wand",
            "worldedit-preview", "worldedit-selection-empty", "worldedit-selection-incomplete",
            "help-worldedit-selection", "worldedit-non-cuboid", "integration-error");
    private final Map<String, String> values;
    private final List<String> wandLore;

    public Messages(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = Messages.class.getResourceAsStream("/messages.yml")) {
            if (bundled == null) throw new IllegalStateException("Bundled messages.yml is missing");
            yaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read bundled messages.yml", failure);
        }
        java.util.Map<String, String> loaded = new java.util.HashMap<>();
        for (String key : REQUIRED) {
            String value = yaml.getString(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing messages.yml key: " + key);
            loaded.put(key, value);
        }
        values = Map.copyOf(loaded);
        List<String> lore = yaml.getStringList("wand-lore");
        if (lore.isEmpty() || lore.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Missing messages.yml wand-lore");
        }
        wandLore = lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList();
    }

    public String text(String key, Object... replacements) {
        String value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown message: " + key);
        if (replacements.length % 2 != 0) throw new IllegalArgumentException("Message replacement pairs required");
        for (int i = 0; i < replacements.length; i += 2) {
            value = value.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public void send(CommandSender recipient, String key, Object... replacements) {
        recipient.sendMessage(text(key, replacements));
    }

    public List<String> lines(String key) {
        if (!key.equals("wand-lore")) throw new IllegalArgumentException("Unknown message list: " + key);
        return wandLore;
    }
}
