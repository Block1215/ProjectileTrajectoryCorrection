package dev.bl.projectiletrajectorycorrection;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * All in-game tunables, stored in config.yml and editable with /ptc (op only).
 *
 *   /ptc                         show every setting
 *   /ptc horizontal on|off       remove the shooter's horizontal inertia
 *   /ptc vertical on|off         remove the shooter's vertical inertia
 *   /ptc rewind on|off           wind charge: throw from an earlier position
 *   /ptc rewind elytraonly on|off   ...only while gliding on elytra
 *   /ptc rewind <ticks>          ...how many ticks back
 *   /ptc hitbox <blocks>         pearl/charge hit size (cube diameter)
 *   /ptc reload                  re-read config.yml
 *
 * Values are read every throw / every tick, hence volatile statics rather than
 * config lookups.
 */
final class Settings implements TabExecutor {

    /** Half of the pearl's own 0.25-block box: the hit margin is added outside it. */
    private static final double PEARL_HALF = 0.125;

    static final double HITBOX_MIN = 0.25;
    static final double HITBOX_MAX = 10.0;
    static final int REWIND_MAX = 20;

    private static volatile double hitDiameter = 2.0;
    private static volatile boolean removeHorizontal = true;
    private static volatile boolean removeVertical = false;
    private static volatile boolean rewindEnabled = true;
    private static volatile int rewindTicks = 3;
    private static volatile boolean rewindElytraOnly = false;

    private final Plugin plugin;

    Settings(Plugin plugin) {
        this.plugin = plugin;
    }

    static double margin() {
        return Math.max(0.0, hitDiameter / 2.0 - PEARL_HALF);
    }

    static double reach() {
        return hitDiameter / 2.0;
    }

    static boolean removeHorizontal() {
        return removeHorizontal;
    }

    static boolean removeVertical() {
        return removeVertical;
    }

    static boolean rewindElytraOnly() {
        return rewindElytraOnly;
    }

    /** Ticks to rewind a wind charge's spawn point, or 0 when off. */
    static int rewindTicks() {
        return rewindEnabled ? rewindTicks : 0;
    }

    static void load(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        var c = plugin.getConfig();
        hitDiameter = clamp(c.getDouble("hit-diameter", 2.0), HITBOX_MIN, HITBOX_MAX);
        removeHorizontal = c.getBoolean("remove-horizontal-inertia", true);
        removeVertical = c.getBoolean("remove-vertical-inertia", false);
        rewindEnabled = c.getBoolean("elytra-wind-charge-rewind.enabled", true);
        rewindTicks = (int) clamp(c.getInt("elytra-wind-charge-rewind.ticks", 3), 0, REWIND_MAX);
        rewindElytraOnly = c.getBoolean("elytra-wind-charge-rewind.elytra-only", false);
    }

    private void save(String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            status(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String val = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;

        switch (sub) {
            case "horizontal" -> {
                Boolean b = parseToggle(sender, val);
                if (b == null) return true;
                removeHorizontal = b;
                save("remove-horizontal-inertia", b);
                msg(sender, "horizontal inertia removal: " + onOff(b)
                    + (b ? " (throws ignore your sideways movement)" : " (vanilla sideways inertia)"));
            }
            case "vertical" -> {
                Boolean b = parseToggle(sender, val);
                if (b == null) return true;
                removeVertical = b;
                save("remove-vertical-inertia", b);
                msg(sender, "vertical inertia removal: " + onOff(b)
                    + (b ? " (throws ignore your vertical movement)" : " (vanilla vertical inertia)"));
            }
            case "rewind" -> {
                if (val == null) {
                    msg(sender, "usage: /ptc rewind <on|off|0-" + REWIND_MAX + "|elytraonly on|off>");
                    return true;
                }
                if (val.equals("elytraonly")) {
                    Boolean b = parseToggle(sender, args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null);
                    if (b == null) return true;
                    rewindElytraOnly = b;
                    save("elytra-wind-charge-rewind.elytra-only", b);
                    msg(sender, "wind charge rewind: " + (b ? "only while gliding on elytra" : "always"));
                    return true;
                }
                if (val.equals("on") || val.equals("off")) {
                    boolean b = val.equals("on");
                    rewindEnabled = b;
                    save("elytra-wind-charge-rewind.enabled", b);
                    msg(sender, "elytra wind charge rewind: " + onOff(b)
                        + (b ? " (" + rewindTicks + " ticks)" : ""));
                    return true;
                }
                int t;
                try {
                    t = Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    msg(sender, "not a number: " + args[1]);
                    return true;
                }
                if (t < 0 || t > REWIND_MAX) {
                    msg(sender, "ticks must be between 0 and " + REWIND_MAX);
                    return true;
                }
                rewindTicks = t;
                save("elytra-wind-charge-rewind.ticks", t);
                msg(sender, "elytra wind charge rewind: " + t + " ticks"
                    + (rewindEnabled ? "" : " (currently OFF - /ptc rewind on to use it)"));
            }
            case "hitbox" -> {
                if (val == null) {
                    msg(sender, "pearl/charge hit size: " + fmt(hitDiameter) + " blocks across (reach "
                        + fmt(reach()) + " from the pearl's centre)");
                    msg(sender, "usage: /ptc hitbox <" + fmt(HITBOX_MIN) + "-" + fmt(HITBOX_MAX) + ">");
                    return true;
                }
                double d;
                try {
                    d = Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    msg(sender, "not a number: " + args[1]);
                    return true;
                }
                if (!(d >= HITBOX_MIN) || d > HITBOX_MAX) {
                    msg(sender, "must be between " + fmt(HITBOX_MIN) + " and " + fmt(HITBOX_MAX));
                    return true;
                }
                hitDiameter = d;
                save("hit-diameter", d);
                msg(sender, "pearl/charge hit size: " + fmt(d) + " blocks across (reach " + fmt(reach()) + ")");
            }
            case "reload" -> {
                load(plugin);
                msg(sender, "config reloaded.");
                status(sender);
            }
            default -> msg(sender, "unknown option. /ptc [horizontal|vertical|rewind|hitbox|reload]");
        }
        return true;
    }

    private void status(CommandSender s) {
        msg(s, "horizontal inertia removal: " + onOff(removeHorizontal) + "   /ptc horizontal on|off");
        msg(s, "vertical inertia removal: " + onOff(removeVertical) + "   /ptc vertical on|off");
        msg(s, "elytra wind charge rewind: " + onOff(rewindEnabled) + ", " + rewindTicks
            + " ticks, " + (rewindElytraOnly ? "elytra only" : "always")
            + "   /ptc rewind on|off|<ticks>|elytraonly on|off");
        msg(s, "pearl/charge hit size: " + fmt(hitDiameter) + " blocks   /ptc hitbox <blocks>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> opts;
        if (args.length == 1) {
            opts = List.of("horizontal", "vertical", "rewind", "hitbox", "reload");
        } else if (args.length == 2) {
            opts = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "horizontal", "vertical" -> List.of("on", "off");
                case "rewind" -> List.of("on", "off", "elytraonly", "1", "2", "3", "4", "5");
                case "hitbox" -> List.of("1", "1.5", "2");
                default -> List.of();
            };
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rewind")
                && args[1].equalsIgnoreCase("elytraonly")) {
            opts = List.of("on", "off");
        } else {
            return Collections.emptyList();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix)) out.add(o);
        return out;
    }

    private static Boolean parseToggle(CommandSender sender, String val) {
        if ("on".equals(val)) return true;
        if ("off".equals(val)) return false;
        msg(sender, "specify on or off");
        return null;
    }

    private static void msg(CommandSender s, String m) {
        s.sendMessage("[PTC] " + m);
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
