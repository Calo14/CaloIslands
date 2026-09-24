package me.calo.islands.core;

import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** Validated local rendering limits; no storage or world mutation settings. */
public record PreviewSettings(Particle edgeParticle, Particle cornerParticle,
                              int intervalTicks, int maxParticlesPerFrame) {
    public PreviewSettings {
        if (edgeParticle == null || cornerParticle == null
                || edgeParticle.getDataType() != Void.class || cornerParticle.getDataType() != Void.class) {
            throw new IllegalArgumentException("Preview particles must not require data");
        }
        if (intervalTicks < 5 || intervalTicks > 40 || maxParticlesPerFrame < 30
                || maxParticlesPerFrame > 240) {
            throw new IllegalArgumentException("Preview interval or particle budget is outside safe bounds");
        }
    }

    public static PreviewSettings read(ConfigurationSection section) {
        if (section == null) return new PreviewSettings(Particle.END_ROD, Particle.FLAME, 10, 192);
        Particle edge = particle(section.getString("edge-particle", "END_ROD"));
        Particle corner = particle(section.getString("corner-particle", "FLAME"));
        int interval = section.getInt("interval-ticks", 10);
        int budget = section.getInt("max-particles-per-frame", 192);
        if (interval < 5 || interval > 40) {
            throw new IllegalArgumentException("preview.interval-ticks must be 5-40");
        }
        if (budget < 30 || budget > 240) {
            throw new IllegalArgumentException("preview.max-particles-per-frame must be 30-240");
        }
        return new PreviewSettings(edge, corner, interval, budget);
    }

    private static Particle particle(String name) {
        final Particle result;
        try {
            result = Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalArgumentException("Unknown preview particle: " + name);
        }
        if (result.getDataType() != Void.class) {
            throw new IllegalArgumentException("Preview particle requires unsupported data: " + name);
        }
        return result;
    }
}
