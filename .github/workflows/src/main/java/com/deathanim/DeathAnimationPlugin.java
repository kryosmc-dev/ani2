package com.deathanim;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class DeathAnimationPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Map<UUID, DeathAnimation> running = new HashMap<>();
    private final Set<UUID> finishing = new HashSet<>();
    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final Set<DamageCause> ignored = EnumSet.noneOf(DamageCause.class);
    private final Random random = new Random();

    private boolean enabledAnim;
    private boolean requireGround;
    private boolean skipTotem;
    private boolean blockCommands;
    private boolean styleByPerm;
    private boolean randomStyle;
    private double viewDistance;
    private String defaultStyle;
    private Set<String> worlds = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("deathanim");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        // Restaura a todos los jugadores que estén a mitad de animación.
        for (DeathAnimation a : new ArrayList<>(running.values())) {
            a.finish(a.kills());
        }
        running.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        enabledAnim = c.getBoolean("enabled", true);
        requireGround = c.getBoolean("require-ground", true);
        skipTotem = c.getBoolean("skip-if-totem", true);
        blockCommands = c.getBoolean("block-commands", true);
        styleByPerm = c.getBoolean("style-by-permission", true);
        randomStyle = c.getBoolean("random-style", false);
        viewDistance = Math.max(8, c.getDouble("view-distance", 48));
        defaultStyle = c.getString("default-style", "portal").toLowerCase(Locale.ROOT);
        worlds = new HashSet<>(c.getStringList("worlds"));

        ignored.clear();
        for (String n : c.getStringList("ignored-causes")) {
            try {
                ignored.add(DamageCause.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Causa de daño desconocida: " + n);
            }
        }

        styles.clear();
        ConfigurationSection sec = c.getConfigurationSection("styles");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                String k = key.toLowerCase(Locale.ROOT);
                styles.put(k, new Style(k, s, getLogger()));
            }
        }
        if (styles.isEmpty()) {
            styles.put("portal", new Style("portal", null, getLogger()));
        }
    }

    double viewDistance() {
        return viewDistance;
    }

    void forget(UUID id) {
        running.remove(id);
    }

    void setFinishing(UUID id, boolean value) {
        if (value) finishing.add(id);
        else finishing.remove(id);
    }

    private Style pickStyle(Player p) {
        if (styleByPerm) {
            for (Style s : styles.values()) {
                if (p.hasPermission("deathanim.style." + s.name)) return s;
            }
        }
        if (randomStyle) {
            List<Style> all = new ArrayList<>(styles.values());
            return all.get(random.nextInt(all.size()));
        }
        Style def = styles.get(defaultStyle);
        return def != null ? def : styles.values().iterator().next();
    }

    private boolean start(Player p, Style style, boolean kill) {
        if (running.containsKey(p.getUniqueId())) return false;
        DeathAnimation a = new DeathAnimation(this, style, p, kill);
        running.put(p.getUniqueId(), a);
        a.begin();
        return true;
    }

    private static boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private static boolean standingOnSolid(Player p) {
        return p.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType().isSolid();
    }

    // ------------------------------------------------------------------
    //  Eventos
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // Durante la animación el jugador es inmune.
        if (running.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (finishing.contains(p.getUniqueId())) return;
        if (!enabledAnim) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (ignored.contains(e.getCause())) return;
        if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return;
        if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) return;
        if (!p.hasPermission("deathanim.use") || p.hasPermission("deathanim.bypass")) return;
        if (skipTotem && hasTotem(p)) return;
        if (requireGround && !standingOnSolid(p)) return;

        e.setCancelled(true);
        start(p, pickStyle(p), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (running.containsKey(e.getDamager().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (running.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (blockCommands && running.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        DeathAnimation a = running.get(e.getPlayer().getUniqueId());
        // Si se desconecta a mitad de animación, muere igual (no puede escapar).
        if (a != null) a.finish(a.kills());
    }

    // ------------------------------------------------------------------
    //  Comando /deathanim
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Uso: /" + label + " <reload|styles|test [jugador] [estilo]>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage("Configuración recargada.");
            }
            case "styles" -> sender.sendMessage("Estilos: " + String.join(", ", styles.keySet()));
            case "test" -> {
                Player target = args.length > 1
                        ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage("Jugador no encontrado.");
                    return true;
                }
                Style st = args.length > 2 ? styles.get(args[2].toLowerCase(Locale.ROOT)) : pickStyle(target);
                if (st == null) {
                    sender.sendMessage("Ese estilo no existe. Usa /" + label + " styles");
                    return true;
                }
                // En modo prueba el jugador NO muere al final.
                if (!start(target, st, false)) {
                    sender.sendMessage("Ese jugador ya está en una animación.");
                }
            }
            default -> sender.sendMessage("Uso: /" + label + " <reload|styles|test [jugador] [estilo]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "styles", "test")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("test")) {
            for (String s : styles.keySet()) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
