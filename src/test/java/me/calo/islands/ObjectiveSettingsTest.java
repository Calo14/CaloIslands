package me.calo.islands;

import me.calo.islands.content.ObjectiveSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjectiveSettingsTest {
    private static YamlConfiguration config() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                activity:
                  objectives:
                    defense:
                      enabled: true
                      name: Defensa del faro
                      mode: DEFENSE
                      region-id: coast
                      radius-blocks: 8
                      goal: 30
                      max-participants: 3
                      max-contribution-per-player: 30
                      timeout-seconds: 120
                    capture:
                      enabled: true
                      name: Control del patio
                      mode: CAPTURE_CONTROL
                      region-id: coast
                      radius-blocks: 8
                      goal: 30
                      capture-at: 10
                      max-participants: 3
                      max-contribution-per-player: 30
                      timeout-seconds: 120
                    structure:
                      enabled: true
                      name: Altares
                      mode: STRUCTURE
                      region-id: coast
                      radius-blocks: 8
                      goal: 3
                      block-material: LEVER
                      max-participants: 3
                      max-contribution-per-player: 3
                      timeout-seconds: 120
                    escort:
                      enabled: true
                      name: Caravana
                      mode: ESCORT
                      region-id: coast
                      radius-blocks: 8
                      route: ['0,64,0', '10,64,0']
                      step-blocks: 1
                      max-participants: 3
                      max-contribution-per-player: 10
                      timeout-seconds: 120
                    collection:
                      enabled: true
                      name: Mina
                      mode: COLLECTION
                      region-id: coast
                      radius-blocks: 8
                      goal: 5
                      block-material: STONE
                      max-participants: 3
                      max-contribution-per-player: 5
                      timeout-seconds: 120
                    mobs:
                      enabled: true
                      name: Incursión
                      mode: MOB_EVENT
                      region-id: coast
                      radius-blocks: 8
                      goal: 5
                      mob-type: ZOMBIE
                      max-participants: 3
                      max-contribution-per-player: 5
                      timeout-seconds: 120
                """);
        return yaml;
    }
    @Test void allSixModesHaveValidatedPlayableSettings() throws Exception {
        var settings = ObjectiveSettings.read(config().getConfigurationSection("activity.objectives"));
        assertEquals(6, settings.size());
        assertEquals(10, settings.get("escort").goal());
        assertEquals(10, settings.get("capture").captureAt());
        assertEquals("ZOMBIE", settings.get("mobs").mobType().name());
    }
    @Test void missingLimitsAndUnfinishableSettingsFail() throws Exception {
        var first = config();
        first.set("activity.objectives.defense.max-contribution-per-player", 1);
        first.set("activity.objectives.defense.max-participants", 1);
        assertThrows(IllegalArgumentException.class,
                () -> ObjectiveSettings.read(first.getConfigurationSection("activity.objectives")));
        var invalid = config();
        invalid.set("activity.objectives.capture.capture-at", 30);
        assertThrows(IllegalArgumentException.class,
                () -> ObjectiveSettings.read(invalid.getConfigurationSection("activity.objectives")));
    }
    @Test void malformedEnabledFlagDoesNotSilentlyDisableObjective() throws Exception {
        var invalid = config();
        invalid.set("activity.objectives.defense.enabled", "yes");
        assertThrows(IllegalArgumentException.class,
                () -> ObjectiveSettings.read(invalid.getConfigurationSection("activity.objectives")));
    }
}
