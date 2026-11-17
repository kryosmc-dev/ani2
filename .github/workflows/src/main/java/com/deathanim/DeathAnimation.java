package com.deathanim;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Animación de vórtice 3D para un jugador que va a morir.
 *
 * Todo es visual: bloques falsos enviados por paquete (sendBlockChange).
 * No usa partículas, sonidos ni entidades, así que Bedrock (Geyser) lo ve igual.
 *
 * Fases:
 *  - El embudo se abre hacia abajo y la espiral gira.
 *  - El jugador cae por el embudo mirando hacia abajo (cámara) y deja de verse.
 *  - El embudo se estrecha y se cierra; el jugador muere en su sitio original.
 */
final class DeathAnimation {

    private record Pos(int x, int y, int z) {}

    private record Cell(int x, int z, int k, double dist, double fraction, boolean skip, boolean realAir) {}

    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final double WALL = 1.5;   // grosor de la pared del embudo
    private static final double RIM = 1.0;    // ancho del marco en la superficie
    private static final double PROFILE = 1.4; // curvatura del embudo

    private final DeathAnimationPlugin plugin;
    private final Style style;
    private final Player player;
    private final UUID id;
    private final World world;
    private final boolean kill;

    private final Location start;
    private final Location center;
    private final int floorY;
    private final int depth;

    private final List<Cell> cells = new ArrayList<>();

    private final boolean wasGravity;
    private final boolean wasInvulnerable;
    private final boolean wasInvisible;

    private Map<Pos, BlockData> sent = new HashMap<>();
    private Set<UUID> viewers = new HashSet<>();

    private BukkitTask task;
    private int tick = 0;
    private boolean done = false;

    DeathAnimation(DeathAnimationPlugin plugin, Style style, Player player, boolean kill) {
        this.plugin = plugin;
        this.style = style;
        this.player = player;
        this.id = player.getUniqueId();
        this.world = player.getWorld();
        this.kill = kill;

        this.start = player.getLocation().clone();
        this.floorY = start.clone().subtract(0, 0.2, 0).getBlockY();
        this.center = new Location(world, start.getBlockX() + 0.5, start.getY(), start.getBlockZ() + 0.5);
        this.depth = Math.max(2, Math.min((int) Math.round(style.depth), floorY - world.getMinHeight() - 1));

        this.wasGravity = player.hasGravity();
        this.wasInvulnerable = player.isInvulnerable();
        this.wasInvisible = player.isInvisible();

        buildCells();
    }

    boolean kills() {
        return kill;
    }

    private double profileRadius(int k) {
        return style.holeRadius
                + (style.radius - style.holeRadius) * Math.pow(1.0 - k / (double) depth, PROFILE);
    }

    private void buildCells() {
        double maxR = style.radius + WALL + RIM;
        int r = (int) Math.ceil(maxR);
        int bx = start.getBlockX();
        int bz = start.getBlockZ();
        int minY = world.getMinHeight();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > maxR) continue;

                int x = bx + dx;
                int z = bz + dz;
                boolean loaded = world.isChunkLoaded(x >> 4, z >> 4);
                double fraction = (Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI);

                for (int k = 0; k <= depth; k++) {
                    int y = floorY - k;
                    if (y <= minY) break;

                    double limit = profileRadius(k) + WALL + (k == 0 ? RIM : 0);
                    if (dist > limit) continue;

                    boolean skip = true;
                    boolean air = false;
                    if (loaded) {
                        Block b = world.getBlockAt(x, y, z);
                        skip = b.getState(false) instanceof TileState; // no tocar cofres, carteles, etc.
                        air = b.getType().isAir();
                    }
                    cells.add(new Cell(x, z, k, dist, fraction, skip, air));
                }
            }
        }
    }

    void begin() {
        player.leaveVehicle();
        player.setGliding(false);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setInvulnerable(true);
        player.setGravity(false);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    private static double smooth(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private void tick() {
        if (done) return;
        if (!player.isOnline() || player.isDead()) {
            finish(false);
            return;
        }

        int open = style.openTicks;
        int sinkStart = style.sinkStartTick;
        int sink = style.sinkTicks;
        int close = style.closeTicks;
        int mainEnd = Math.max(open, sinkStart + sink);

        if (tick >= mainEnd + close) {
            finish(kill);
            return;
        }

        // Escala del vórtice: se abre, se mantiene, y al final se estrecha (sin perder profundidad).
        double sRadius;
        double sDepth;
        if (tick < open) {
            sRadius = smooth((tick + 1) / (double) open);
            sDepth = sRadius;
        } else if (tick < mainEnd) {
            sRadius = 1.0;
            sDepth = 1.0;
        } else {
            sDepth = 1.0;
            sRadius = smooth(1.0 - (tick - mainEnd + 1) / (double) close);
        }

        movePlayer(sinkStart, sink);

        // --- Bloques que debe ver la gente en este tick ---
        Map<Pos, BlockData> desired = new HashMap<>();
        int n = style.palette.size();
        double rot = tick * style.spinSpeed;
        int kCap = (int) Math.floor(sDepth * depth + 1e-9);

        for (Cell c : cells) {
            if (c.skip() || c.k() > kCap) continue;

            double ri = sRadius * profileRadius(c.k());
            double edge = ri + WALL;
            double d = c.dist();
            Pos p = new Pos(c.x(), floorY - c.k(), c.z());

            // Marco oscuro alrededor de la boca del embudo (solo en la superficie).
            if (c.k() == 0 && d >= edge) {
                if (d < edge + RIM && !c.realAir()) desired.put(p, style.rimBlock);
                continue;
            }

            if (c.k() == kCap) {
                // Capa más profunda: suelo brillante del vórtice.
                if (d < edge) desired.put(p, style.coreBlock);
            } else if (d < ri) {
                // Interior del embudo: se "excava" visualmente.
                if (!c.realAir()) desired.put(p, AIR);
            } else if (d < edge) {
                // Pared con la espiral de colores.
                desired.put(p, swirl(c.fraction(), c.k(), rot, n));
            }
        }

        // Pilares que orbitan alrededor del portal.
        if (style.pillars > 0 && sRadius > 0.05) {
            int h = (int) Math.round(style.pillarHeight * sRadius);
            double pr = sRadius * style.radius + WALL + RIM + 0.7;
            for (int i = 0; i < style.pillars; i++) {
                double a = i * (2 * Math.PI / style.pillars) + tick * style.pillarSpin;
                int x = (int) Math.floor(center.getX() + Math.cos(a) * pr);
                int z = (int) Math.floor(center.getZ() + Math.sin(a) * pr);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int j = 1; j <= h; j++) {
                    int y = floorY + j;
                    if (!world.getBlockData(x, y, z).getMaterial().isAir()) break;
                    BlockData data = (j == h)
                            ? style.palette.get(Math.floorMod(tick / 3 + i, n))
                            : style.pillarBlock;
                    desired.put(new Pos(x, y, z), data);
                }
            }
        }

        sync(desired);
        tick++;
    }

    private BlockData swirl(double fraction, int k, double rot, int n) {
        double v = fraction * style.arms + k * style.twist - rot;
        double frac = v - Math.floor(v);
        return style.palette.get(Math.min(n - 1, (int) (frac * n)));
    }

    /** Mueve al jugador: lo centra, lo hunde por el embudo y gira la cámara hacia abajo. */
    private void movePlayer(int sinkStart, int sink) {
        Location to = start.clone();

        if (tick < sinkStart) {
            double t = smooth((tick + 1) / (double) Math.max(1, sinkStart));
            to.setX(lerp(start.getX(), center.getX(), t));
            to.setZ(lerp(start.getZ(), center.getZ(), t));
        } else {
            to.setX(center.getX());
            to.setZ(center.getZ());
        }

        double u = 0;
        if (tick >= sinkStart) {
            u = Math.min(1.0, (tick - sinkStart + 1) / (double) sink);
        }
        double eased = smooth(u);

        double finalY = floorY - depth + 1.6;
        double maxDrop = Math.max(0.5, start.getY() - finalY);
        double drop = maxDrop * eased;
        to.setY(start.getY() - drop);

        // Cámara cinemática: gira y baja la mirada hacia el fondo del vórtice.
        to.setYaw((float) (start.getYaw() + style.yawSpin * Math.max(0, tick - sinkStart)));
        double pitch = lerp(start.getPitch(), style.lookPitch, eased);
        to.setPitch((float) Math.max(-90.0, Math.min(90.0, pitch)));

        // Al entrar de lleno en el portal, el personaje deja de verse.
        if (drop >= style.hideDepth) {
            player.setInvisible(true);
        }

        player.teleport(to);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0);
    }

    /** Envía a cada espectador solo lo que cambió respecto al tick anterior. */
    private void sync(Map<Pos, BlockData> desired) {
        Set<UUID> now = new HashSet<>();
        double maxSq = plugin.viewDistance() * plugin.viewDistance();

        for (Player v : world.getPlayers()) {
            if (v.getLocation().distanceSquared(center) > maxSq) continue;
            now.add(v.getUniqueId());
            boolean fresh = !viewers.contains(v.getUniqueId());

            for (Map.Entry<Pos, BlockData> e : desired.entrySet()) {
                if (fresh || !e.getValue().equals(sent.get(e.getKey()))) {
                    v.sendBlockChange(loc(e.getKey()), e.getValue());
                }
            }
            if (!fresh) {
                for (Pos p : sent.keySet()) {
                    if (!desired.containsKey(p)) {
                        v.sendBlockChange(loc(p), real(p));
                    }
                }
            }
        }

        // Quienes se alejaron: devolverles los bloques reales.
        for (UUID vid : viewers) {
            if (now.contains(vid)) continue;
            Player v = Bukkit.getPlayer(vid);
            if (v != null && v.isOnline() && v.getWorld().equals(world)) {
                for (Pos p : sent.keySet()) v.sendBlockChange(loc(p), real(p));
            }
        }

        viewers = now;
        sent = new HashMap<>(desired);
    }

    private Location loc(Pos p) {
        return new Location(world, p.x(), p.y(), p.z());
    }

    private BlockData real(Pos p) {
        return world.getBlockData(p.x(), p.y(), p.z());
    }

    /** Termina la animación: restaura bloques y estado, y mata al jugador si corresponde. */
    void finish(boolean doKill) {
        if (done) return;
        done = true;
        if (task != null) task.cancel();

        for (UUID vid : viewers) {
            Player v = Bukkit.getPlayer(vid);
            if (v != null && v.isOnline() && v.getWorld().equals(world)) {
                for (Pos p : sent.keySet()) v.sendBlockChange(loc(p), real(p));
            }
        }

        plugin.forget(id);

        if (player.isOnline()) {
            player.teleport(start);
            player.setGravity(wasGravity);
            player.setInvulnerable(wasInvulnerable);
            player.setInvisible(wasInvisible);
            player.setFallDistance(0);

            if (doKill && !player.isDead()) {
                plugin.setFinishing(id, true);
                try {
                    player.setHealth(0);
                } finally {
                    plugin.setFinishing(id, false);
                }
            }
        }
    }
}
