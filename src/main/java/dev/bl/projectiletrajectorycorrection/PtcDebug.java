package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in diagnostics, toggled per player with /ptcdebug. Nothing is printed
 * unless a player has turned it on for themselves.
 *
 * For every wind charge thrown by a debugging player it also tracks the true
 * closest approach to that player's ender pearls (continuous within each tick,
 * not just the sampled positions) and reports it when the charge disappears,
 * split into horizontal and vertical distance.
 */
final class PtcDebug implements CommandExecutor {

    private static final Set<UUID> ENABLED = new HashSet<>();

    static boolean on(ProjectileSource shooter) {
        return shooter instanceof Player p && ENABLED.contains(p.getUniqueId());
    }

    static void send(Player p, String msg) {
        p.sendMessage("[PTC] " + msg);
    }

    static String v(Vector v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.getX(), v.getY(), v.getZ());
    }

    static String f(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (ENABLED.remove(p.getUniqueId())) {
            send(p, "debug OFF");
        } else {
            ENABLED.add(p.getUniqueId());
            send(p, "debug ON");
        }
        return true;
    }

    /** Closest approach record for one wind charge. */
    private static final class Best {
        final UUID owner;
        double dist = Double.MAX_VALUE;
        double horiz, vert;
        int age;
        boolean sawPearl;

        Best(UUID owner) {
            this.owner = owner;
        }
    }

    static void startApproachTracker(Plugin plugin) {
        new BukkitRunnable() {
            private final Map<UUID, Best> best = new HashMap<>();

            @Override
            public void run() {
                if (ENABLED.isEmpty() && best.isEmpty()) return;
                Set<UUID> alive = new HashSet<>();

                for (World world : Bukkit.getWorlds()) {
                    for (WindCharge wc : world.getEntitiesByClass(WindCharge.class)) {
                        if (!wc.isValid()) continue;
                        ProjectileSource src = wc.getShooter();
                        if (!on(src)) continue;
                        Player owner = (Player) src;
                        alive.add(wc.getUniqueId());
                        Best b = best.computeIfAbsent(wc.getUniqueId(), k -> new Best(owner.getUniqueId()));

                        Vector wcPos = wc.getLocation().toVector();
                        Vector wcVel = wc.getVelocity();
                        for (EnderPearl ep : world.getEntitiesByClass(EnderPearl.class)) {
                            if (!ep.isValid() || ep.getShooter() != owner) continue;
                            b.sawPearl = true;
                            // Closest point of the relative motion within this tick.
                            Vector p = wcPos.clone().subtract(ep.getLocation().toVector());
                            Vector rv = wcVel.clone().subtract(ep.getVelocity());
                            double vv = rv.lengthSquared();
                            double t = vv < 1e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, -p.dot(rv) / vv));
                            Vector c = p.clone().add(rv.clone().multiply(t));
                            double d = c.length();
                            if (d < b.dist) {
                                b.dist = d;
                                b.horiz = Math.hypot(c.getX(), c.getZ());
                                b.vert = c.getY();
                                b.age = wc.getTicksLived();
                            }
                        }
                    }
                }

                for (Iterator<Map.Entry<UUID, Best>> it = best.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<UUID, Best> e = it.next();
                    if (alive.contains(e.getKey())) continue;
                    it.remove();
                    Best b = e.getValue();
                    Player p = Bukkit.getPlayer(b.owner);
                    if (p == null || !b.sawPearl) continue;
                    send(p, "charge gone. closest to pearl: horiz=" + f(b.horiz)
                        + " vert=" + f(b.vert) + " total=" + f(b.dist)
                        + " at charge age " + b.age + "t");
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
