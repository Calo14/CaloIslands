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
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class Messages {
    private static final Pattern TOKEN = Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_-]*}");
    private static final Set<String> REQUIRED = Set.of(
            "preview-context", "preview-world-mismatch", "destination-terrain-unchecked", "destination-status-invalid",
            "destination-world-unavailable", "destination-status-valid", "server-thread-required", "teleport-cancelled",
            "region-no-point", "destination-invalid", "point-saved", "point-cleared", "teleport-success", "help-destination",
            "preview-status-selection", "preview-status-active", "preview-status-inactive", "preview-status-invalid", "point-unset",
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

    /** Reports all missing, unknown and incompatible custom translation keys without changing the file. */
    public static List<String> audit(File file) {
        YamlConfiguration custom = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled;
        try (InputStream stream = Messages.class.getResourceAsStream("/messages.yml")) {
            if (stream == null) throw new IllegalStateException("Bundled messages.yml is missing");
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read bundled messages.yml", failure);
        }
        List<String> issues = new ArrayList<>();
        for (String key : new TreeSet<>(bundled.getKeys(true))) {
            if (bundled.isConfigurationSection(key)) continue;
            if (!custom.contains(key)) {
                issues.add("messages.yml:" + key + ": falta la traducción; se usará la incluida.");
                continue;
            }
            Object expected = bundled.get(key), actual = custom.get(key);
            if (expected instanceof String source) {
                if (!(actual instanceof String translated) || translated.isBlank()) {
                    issues.add("messages.yml:" + key + ": texto vacío o tipo inválido.");
                } else if (malformedTokens(translated) || !tokens(source).equals(tokens(translated))) {
                    issues.add("messages.yml:" + key + ": placeholders distintos de la traducción incluida.");
                }
            } else if (expected instanceof List<?> sourceLines) {
                if (!(actual instanceof List<?> lines) || lines.isEmpty()
                        || lines.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
                    issues.add("messages.yml:" + key + ": lista vacía o tipo inválido.");
                } else if (lines.stream().anyMatch(value -> malformedTokens((String) value))
                        || !listTokens(sourceLines).equals(listTokens(lines))) {
                    issues.add("messages.yml:" + key + ": placeholders distintos de la traducción incluida.");
                }
            }
        }
        for (String key : new TreeSet<>(custom.getKeys(true))) {
            if (!custom.isConfigurationSection(key) && !bundled.contains(key))
                issues.add("messages.yml:" + key + ": clave desconocida.");
        }
        return List.copyOf(issues);
    }

    private static Set<String> tokens(String value) {
        java.util.HashSet<String> found = new java.util.HashSet<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) found.add(matcher.group());
        return Set.copyOf(found);
    }

    private static Set<String> listTokens(List<?> lines) {
        java.util.HashSet<String> found = new java.util.HashSet<>();
        for (Object line : lines) found.addAll(tokens((String) line));
        return Set.copyOf(found);
    }

    private static boolean malformedTokens(String value) {
        String withoutTokens = TOKEN.matcher(value).replaceAll("");
        return withoutTokens.indexOf('{') >= 0 || withoutTokens.indexOf('}') >= 0;
    }

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
