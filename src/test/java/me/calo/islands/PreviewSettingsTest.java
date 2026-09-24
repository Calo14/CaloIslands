package me.calo.islands;

import me.calo.islands.core.PreviewSettings;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PreviewSettingsTest {
    @Test
    void validatesDataFreeParticlesAndBoundedRenderingOptions() {
        assertEquals(192, PreviewSettings.read(null).maxParticlesPerFrame());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("preview.edge-particle", "END_ROD");
        yaml.set("preview.corner-particle", "FLAME");
        yaml.set("preview.interval-ticks", 10);
        yaml.set("preview.max-particles-per-frame", 192);
        PreviewSettings settings = PreviewSettings.read(yaml.getConfigurationSection("preview"));
        assertEquals(Particle.END_ROD, settings.edgeParticle());
        assertEquals(192, settings.maxParticlesPerFrame());
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewSettings(Particle.END_ROD, Particle.FLAME, 10, 10));
        yaml.set("preview.max-particles-per-frame", 10);
        assertThrows(IllegalArgumentException.class,
                () -> PreviewSettings.read(yaml.getConfigurationSection("preview")));
        yaml.set("preview.max-particles-per-frame", 192);
        yaml.set("preview.edge-particle", "not_a_particle");
        assertThrows(IllegalArgumentException.class,
                () -> PreviewSettings.read(yaml.getConfigurationSection("preview")));
        yaml.set("preview.edge-particle", "DUST");
        assertThrows(IllegalArgumentException.class,
                () -> PreviewSettings.read(yaml.getConfigurationSection("preview")));
    }
}
