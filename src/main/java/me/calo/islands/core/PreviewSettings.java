package me.calo.islands.core;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
/** Hard per-player frame limit; legacy particle keys are accepted but rendering always uses DUST. */
public record PreviewSettings(Particle edgeParticle, Particle cornerParticle, int intervalTicks,
        int maxParticlesPerFrame, double maxPointSpacing, double gridSpacing,
        Color selectionColor, Color activeColor, Color inactiveColor, Color invalidColor) {
    public PreviewSettings(Particle edgeParticle, Particle cornerParticle, int intervalTicks, int budget) {
        this(edgeParticle, cornerParticle, intervalTicks, budget, 2, 16, Color.AQUA, Color.LIME, Color.ORANGE, Color.RED);
    }
    public PreviewSettings {
        if (edgeParticle == null || cornerParticle == null || intervalTicks < 5 || intervalTicks > 40
                || maxParticlesPerFrame < 96 || maxParticlesPerFrame > 1200
                || !Double.isFinite(maxPointSpacing) || maxPointSpacing < .5 || maxPointSpacing > 32
                || !Double.isFinite(gridSpacing) || gridSpacing < 4 || gridSpacing > 256
                || selectionColor == null || activeColor == null || inactiveColor == null || invalidColor == null)
            throw new IllegalArgumentException("Invalid preview settings");
    }
    public static PreviewSettings read(ConfigurationSection s) {
        if (s == null) return new PreviewSettings(Particle.DUST, Particle.DUST, 10, 960);
        return new PreviewSettings(Particle.DUST, Particle.DUST, s.getInt("interval-ticks",10),
                s.getInt("max-particles-per-frame",960), s.getDouble("max-point-spacing",2), s.getDouble("grid-spacing",16),
                color(s,"selection",0x00FFFF), color(s,"active",0x00FF00), color(s,"inactive",0xFFA500), color(s,"invalid",0xFF0000));
    }
    private static Color color(ConfigurationSection s, String key, int fallback) {
        String value = s.getString("colors."+key, String.format("#%06X",fallback));
        if (!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid preview color: "+key);
        return Color.fromRGB(Integer.parseInt(value.substring(1),16));
    }
}
