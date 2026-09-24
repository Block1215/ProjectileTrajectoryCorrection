package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Makes a player's throw go exactly where it was aimed.
 *
 * Thrown items, tridents and bows go through vanilla's shootFromRotation:
 *     velocity = (normalize(aim) + randomNoise) * power
 *                + (known.x, onGround ? 0 : known.y, known.z)
 * We subtract the exact inertia (see {@link ShooterInertia}) and rebuild the
 * shot along the aim, removing the scatter on every axis. The horizontal and
 * vertical inertia are then left out or put back per /ptc horizontal and
 * /ptc vertical (default: horizontal removed, vertical kept as vanilla), the
 * same rule in every state (walking, jumping, falling, gliding).
 *
 * A wind charge is additionally spawned where the player was a few ticks
 * earlier (/ptc rewind), with the current aim - always, or only while gliding
 * on elytra (/ptc rewind elytraonly).
 *
 * Crossbows are different: vanilla fires them WITHOUT any shooter inertia, at
 * the aim rotated about the player's up axis (multishot spreads the side
 * arrows). Subtracting inertia there would drag the arrow backwards while
 * flying, so crossbow shots only get their scatter removed.
 */
public final class ProjectileLaunchListener implements Listener {

    /** Multishot's default side angle, and how close a shot must be to snap. */
    private static final double[] CROSSBOW_ANGLES = {0.0, 10.0, -10.0};
    private static final double CROSSBOW_SNAP_DEG = 3.0;

    /** Potions and experience bottles are thrown 20 degrees above the aim. */
    private static final float LOBBED_PITCH_OFFSET = -20.0f;

    private final Plugin plugin;
    private final PlayerMovementTracker tracker;
    private final Set<UUID> crossbowShots = new HashSet<>();

    public ProjectileLaunchListener(Plugin plugin, PlayerMovementTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    // Fired right before the projectile is spawned (and ProjectileLaunchEvent).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.CROSSBOW) return;
        UUID id = event.getProjectile().getUniqueId();
        crossbowShots.add(id);
        Bukkit.getScheduler().runTask(plugin, () -> crossbowShots.remove(id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) return;

        Vector v = projectile.getVelocity();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0e-9) return;

        if (crossbowShots.remove(projectile.getUniqueId())) {
            straightenCrossbowShot(projectile, v, eye, look);
            return;
        }

        // The direction vanilla actually throws in.
        Vector aim = look;
        if (projectile instanceof ThrownPotion || projectile instanceof ThrownExpBottle) {
            aim = new Location(null, 0, 0, 0, eye.getYaw(),
                eye.getPitch() + LOBBED_PITCH_OFFSET).getDirection();
        }

        // Exactly what vanilla added; estimate only if the server can't tell us.
        Vector applied = ShooterInertia.of(player);
        boolean exact = applied != null;
        if (applied == null) {
            Vector est = tracker.getVelocity(player.getUniqueId());
            applied = new Vector(est.getX(), player.isOnGround() ? 0.0 : est.getY(), est.getZ());
        }

        // Raw shot = (aim + noise) * power. Power = the part along the aim.
        Vector shot = v.clone().subtract(applied);
        double power = shot.dot(aim);
        if (power <= 0.0) power = shot.length();

        // Scatter is always gone. Each inertia axis is removed only if enabled
        // (/ptc horizontal, /ptc vertical); otherwise vanilla's value goes back in.
        Vector newVel = aim.clone().multiply(power);
        if (!Settings.removeHorizontal()) {
            newVel.setX(newVel.getX() + applied.getX());
            newVel.setZ(newVel.getZ() + applied.getZ());
        }
        if (!Settings.removeVertical()) {
            newVel.setY(newVel.getY() + applied.getY());
        }
        projectile.setVelocity(newVel);

        int rewound = 0;
        if (projectile instanceof WindCharge
                && (!Settings.rewindElytraOnly() || player.isGliding())) {
            rewound = rewindSpawn(player, projectile);
        }

        if (PtcDebug.on(player)) {
            debugThrow(player, projectile, eye, v, applied, exact, newVel);
            if (rewound > 0) {
                PtcDebug.send(player, " spawn rewound " + rewound + " ticks");
            }
        }
    }

    /**
     * Wind charge: move the spawn point to where the player was a few
     * ticks ago (/ptc rewind), keeping the current aim. The charge keeps the
     * same offset from the player that vanilla gave it.
     *
     * @return ticks rewound, or 0 if nothing was changed
     */
    private int rewindSpawn(Player player, Projectile projectile) {
        int ticks = Settings.rewindTicks();
        if (ticks <= 0) return 0;
        Location past = tracker.getLocationTicksAgo(player.getUniqueId(), ticks);
        if (past == null || past.getWorld() != player.getWorld()) return 0;

        Location now = player.getLocation();
        Location spawn = projectile.getLocation();
        Location target = spawn.clone().add(
            past.getX() - now.getX(), past.getY() - now.getY(), past.getZ() - now.getZ());
        return moveUnspawned(projectile, target) ? ticks : 0;
    }

    /** Moves a projectile that is still being launched (not yet in the world). */
    private static boolean moveUnspawned(Projectile projectile, Location target) {
        try {
            Object handle = projectile.getClass().getMethod("getHandle").invoke(projectile);
            handle.getClass().getMethod("setPos", double.class, double.class, double.class)
                .invoke(handle, target.getX(), target.getY(), target.getZ());
            return true;
        } catch (Throwable ignored) {
            return projectile.teleport(target);
        }
    }

    private static void debugThrow(Player player, Projectile projectile, Location eye,
                                   Vector vanilla, Vector applied, boolean exact, Vector newVel) {
        PtcDebug.send(player, projectile.getType().name()
            + " pitch=" + PtcDebug.f(eye.getPitch()) + " yaw=" + PtcDebug.f(eye.getYaw())
            + " gliding=" + player.isGliding() + " onGround=" + player.isOnGround());
        PtcDebug.send(player, " vanilla=" + PtcDebug.v(vanilla)
            + " inertia=" + PtcDebug.v(applied) + (exact ? " [exact]" : " [ESTIMATE]"));
        PtcDebug.send(player, " result=" + PtcDebug.v(newVel)
            + " horizSpeed=" + PtcDebug.f(Math.hypot(newVel.getX(), newVel.getZ())));

        if (projectile instanceof org.bukkit.entity.WindCharge) {
            Location spawn = projectile.getLocation();
            org.bukkit.entity.EnderPearl nearest = null;
            double nd = Double.MAX_VALUE;
            for (org.bukkit.entity.EnderPearl ep
                    : spawn.getWorld().getEntitiesByClass(org.bukkit.entity.EnderPearl.class)) {
                if (!ep.isValid() || ep.getShooter() != player) continue;
                Location l = ep.getLocation();
                double d = Math.hypot(l.getX() - spawn.getX(), l.getZ() - spawn.getZ());
                if (d < nd) {
                    nd = d;
                    nearest = ep;
                }
            }
            if (nearest != null) {
                PtcDebug.send(player, " charge spawned horiz " + PtcDebug.f(nd)
                    + " from pearl (hit reach " + PtcDebug.f(Settings.reach()) + "), pearl vel=" + PtcDebug.v(nearest.getVelocity())
                    + " pearl age=" + nearest.getTicksLived() + "t");
            }
        }
    }

    private void straightenCrossbowShot(Projectile projectile, Vector v, Location eye, Vector look) {
        // Vanilla: getUpVector = view vector at (xRot - 90, yRot).
        Vector up = new Location(null, 0, 0, 0, eye.getYaw(), eye.getPitch() - 90.0f).getDirection();
        Vector side = up.getCrossProduct(look);

        // The intended direction is the aim rotated about `up`, so it has no
        // component along `up`: whatever is there is pure scatter.
        double a = Math.atan2(v.dot(side), v.dot(look));
        double deg = Math.toDegrees(a);
        for (double target : CROSSBOW_ANGLES) {
            if (Math.abs(deg - target) <= CROSSBOW_SNAP_DEG) {
                a = Math.toRadians(target);
                break;
            }
        }
        Vector dir = look.clone().multiply(Math.cos(a)).add(side.clone().multiply(Math.sin(a)));
        double power = v.dot(dir);
        if (power <= 0.0) return;
        projectile.setVelocity(dir.multiply(power));
    }
}
