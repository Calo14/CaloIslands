package me.calo.islands;
import me.calo.islands.core.PreviewSettings;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class PreviewSettingsTest {
    @Test void dustSettingsHaveHardLimitsAndMigrateLegacyParticleChoices() {
        assertEquals(960, PreviewSettings.read(null).maxParticlesPerFrame());
        YamlConfiguration yaml=new YamlConfiguration();
        yaml.set("preview.edge-particle","END_ROD"); yaml.set("preview.corner-particle","FLAME");
        yaml.set("preview.max-particles-per-frame",192);
        assertEquals(Particle.DUST,PreviewSettings.read(yaml.getConfigurationSection("preview")).edgeParticle());
        for(int budget:new int[]{10,1201}) {
            yaml.set("preview.max-particles-per-frame",budget);
            assertThrows(IllegalArgumentException.class,()->PreviewSettings.read(yaml.getConfigurationSection("preview")));
        }
        yaml.set("preview.max-particles-per-frame",960); yaml.set("preview.max-point-spacing",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->PreviewSettings.read(yaml.getConfigurationSection("preview")));
        yaml.set("preview.max-point-spacing",2); yaml.set("preview.colors.active","invalid");
        assertThrows(IllegalArgumentException.class,()->PreviewSettings.read(yaml.getConfigurationSection("preview")));
    }
}
