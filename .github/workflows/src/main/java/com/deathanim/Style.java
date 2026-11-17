package com.deathanim;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Parámetros de un estilo de animación, leídos de config.yml. */
final class Style {

    final String name;

    // Forma del vórtice
    final double radius;
    final double holeRadius;
    final double depth;

    // Tiempos (en ticks)
    final int openTicks;
    final int sinkStartTick;
    final int sinkTicks;
    final int closeTicks;

    // Cámara / jugador
    final double yawSpin;
    final double lookPitch;
    final double hideDepth;

    // Espiral
    final int arms;
    final double twist;
    final double spinSpeed;
    final List<BlockData> palette = new ArrayList<>();

    // Bloques decorativos
    final BlockData rimBlock;
    final BlockData coreBlock;
    final BlockData pillarBlock;
    final int pillars;
    final double pillarHeight;
    final double pillarSpin;

    Style(String name, ConfigurationSection section, Logger log) {
        ConfigurationSection c = section != null ? section : new MemoryConfiguration();
        this.name = name;

        this.radius = clamp(c.getDouble("radius", 4.0), 2.0, 12.0);
        this.holeRadius = clamp(c.getDouble("hole-radius", 1.0), 0.5, this.radius - 1.0);
        this.depth = clamp(c.getDouble("depth", 7.0), 3.0, 16.0);

        this.openTicks = Math.max(1, c.getInt("open-ticks", 30));
        this.sinkStartTick = Math.max(0, c.getInt("sink-start-tick", 8));
        this.sinkTicks = Math.max(1, c.getInt("sink-ticks", 44));
        this.closeTicks = Math.max(1, c.getInt("close-ticks", 16));

        this.yawSpin = c.getDouble("yaw-spin", 7.0);
        this.lookPitch = clamp(c.getDouble("look-pitch", 65.0), -90.0, 90.0);
        this.hideDepth = clamp(c.getDouble("hide-depth", 3.0), 0.0, this.depth);

        this.arms = Math.max(1, c.getInt("arms", 3));
        this.twist = c.getDouble("twist", 0.5);
        this.spinSpeed = c.getDouble("spin-speed", 0.08);

        for (String n : c.getStringList("palette")) {
            Material m = Material.matchMaterial(n);
            if (m == null || !m.isBlock()) {
                log.warning("[" + name + "] Bloque inválido en la paleta: " + n);
                continue;
            }
            palette.add(m.createBlockData());
        }
        if (palette.isEmpty()) {
            palette.add(Material.BLACK_CONCRETE.createBlockData());
            palette.add(Material.PURPLE_CONCRETE.createBlockData());
            palette.add(Material.MAGENTA_CONCRETE.createBlockData());
        }

        this.rimBlock = block(c, "rim-block", Material.CRYING_OBSIDIAN, log, name);
        this.coreBlock = block(c, "core-block", Material.SEA_LANTERN, log, name);
        this.pillarBlock = block(c, "pillar-block", Material.OBSIDIAN, log, name);
        this.pillars = (int) clamp(c.getInt("pillars", 6), 0, 24);
        this.pillarHeight = clamp(c.getDouble("pillar-height", 4.0), 0.0, 8.0);
        this.pillarSpin = c.getDouble("pillar-spin", 0.06);
    }

    private static BlockData block(ConfigurationSection c, String key, Material def, Logger log, String style) {
        String n = c.getString(key);
        if (n != null) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) return m.createBlockData();
            log.warning("[" + style + "] Bloque inválido en " + key + ": " + n);
        }
        return def.createBlockData();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
