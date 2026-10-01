package me.calo.islands;

import me.calo.islands.core.CaloValidation;
import me.calo.islands.domain.Bounds;
import me.calo.islands.domain.Destination;
import me.calo.islands.domain.Region;
import me.calo.islands.domain.RegionService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaloValidationTest {
    @TempDir Path directory;

    @Test void validatorReadsRealFilesAndDetectsDuplicateActivityIds() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.copy(Path.of("src/main/resources/config.yml"), config);
        Files.copy(Path.of("src/main/resources/messages.yml"), directory.resolve("messages.yml"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config.toFile());
        yaml.set("database.username", "test_user");
        yaml.set("database.password", "test_password");
        yaml.save(config.toFile());
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        RegionService regions = mock(RegionService.class);
        assertTrue(CaloValidation.inspect(plugin, regions).isEmpty());

        yaml.set("activity.defense.enabled", true);
        yaml.set("activity.defense.id", "defense_event");
        yaml.set("activity.defense.region-id", "coast");
        yaml.set("activity.defense.duration-seconds", 10);
        yaml.set("activity.defense.radius-blocks", 8);
        yaml.set("activity.objectives.defense_event.enabled", true);
        yaml.set("activity.objectives.defense_event.name", "Defensa del faro");
        yaml.set("activity.objectives.defense_event.mode", "DEFENSE");
        yaml.set("activity.objectives.defense_event.region-id", "coast");
        yaml.set("activity.objectives.defense_event.radius-blocks", 8);
        yaml.set("activity.objectives.defense_event.goal", 10);
        yaml.set("activity.objectives.defense_event.max-participants", 1);
        yaml.set("activity.objectives.defense_event.max-contribution-per-player", 10);
        yaml.set("activity.objectives.defense_event.timeout-seconds", 20);
        yaml.save(config.toFile());
        Region region = new Region("coast", "world", new Bounds(0, -64, 0, 20, 319, 20),
                true, 1, new Destination("world", 5, 64, 5, 0, 0));
        when(regions.region("coast")).thenReturn(Optional.of(region));
        assertTrue(CaloValidation.inspect(plugin, regions).stream()
                .anyMatch(issue -> issue.contains("comparten ID")));

        yaml.set("activity.defense.enabled", false);
        yaml.set("activity.objectives.defense_event.enabled", false);
        yaml.set("activity.objectives.escort.enabled", true);
        yaml.set("activity.objectives.escort.name", "Caravana");
        yaml.set("activity.objectives.escort.mode", "ESCORT");
        yaml.set("activity.objectives.escort.region-id", "coast");
        yaml.set("activity.objectives.escort.radius-blocks", 8);
        yaml.set("activity.objectives.escort.max-participants", 1);
        yaml.set("activity.objectives.escort.max-contribution-per-player", 50);
        yaml.set("activity.objectives.escort.timeout-seconds", 120);
        yaml.set("activity.objectives.escort.step-blocks", 1);
        yaml.set("activity.objectives.escort.route", java.util.List.of("5,64,5", "50,64,5"));
        yaml.save(config.toFile());
        assertTrue(CaloValidation.inspect(plugin, regions).stream()
                .anyMatch(issue -> issue.contains("ruta fuera de la región")));
    }
}
