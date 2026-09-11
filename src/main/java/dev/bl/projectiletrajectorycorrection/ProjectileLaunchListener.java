package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Removes the random sideways scatter vanilla applies to thrown projectiles,
 * while preserving the shooter's movement inertia.
 *
 * Vanilla launch velocity = normalize(look + randomNoise) * power + inertia.
 * We reconstruct it as normalize(look) * power + inertia, i.e. we keep the
 * exact same launch speed and the same inertia, but point the shot component
 * precisely along where the player aimed — deleting only the random angular
 * noise.
 *
 * The inertia is obtained from {@link PlayerMovementTracker} (per-tick position
 * delta = the client-reported "known movement" that vanilla adds), because
 * Bukkit's Player.getVelocity() does not report it reliably.
 *
 * Vertical throws (looking straight up/down) are a special case: the player
 * usually wants a perfectly vertical shot, so only the VERTICAL inertia is
 * kept and the horizontal inertia is dropped.
 */
public final class ProjectileLaunchListener implements Listener {

    private static final int FORCE_TICKS = 5;
    private static final float VERTICAL_PITCH_TOLERANCE = 0.05f;

    private final Plugin plugin;
    private final PlayerMovementTracker tracker;

    public ProjectileLaunchListener(Plugin plugin, PlayerMovementTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) return;

        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();          // exact aim, unit length
        if (look.lengthSquared() < 1.0e-9) return;

        Vector vanilla = projectile.getVelocity();
        Vector inertia = tracker.getVelocity(player.getUniqueId());

        // Isolate the shot component (aim*power + random noise) by removing the
        // shooter's inertia, then rebuild it along the exact aim direction with
        // the same magnitude. This deletes the angular noise but keeps power.
        Vector shot = vanilla.clone().subtract(inertia);
        double power = shot.length();
        if (power < 1.0e-9) return;

        boolean vertical =
            Math.abs(Math.abs(eye.getPitch()) - 90.0f) <= VERTICAL_PITCH_TOLERANCE;

        if (vertical) {
            // Perfectly vertical shot: keep only the vertical inertia so the
            // projectile flies straight up/down without sideways drift. This can
            // only ever REDUCE the speed (horizontal inertia removed), so the
            // projectile stays near the player and renders fine even when the
            // velocity is set during the launch event.
            double y = look.getY() * power + inertia.getY();
            projectile.setVelocity(new Vector(0.0, y, 0.0));

            // Re-enforce the straight vertical path for a few ticks (gravity
            // acts along the aim axis, so this is safe).
            for (int t = 1; t <= FORCE_TICKS; t++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!projectile.isValid() || projectile.isDead()) return;
                    Vector cur = projectile.getVelocity();
                    if (cur.getX() != 0.0 || cur.getZ() != 0.0) {
                        projectile.setVelocity(new Vector(0.0, cur.getY(), 0.0));
                    }
                }, t);
            }
            return;
        }

        // Horizontal / diagonal: straight along aim, with full inertia preserved.
        Vector newVel = look.clone().multiply(power).add(inertia);

        // Safety clamp: removing the tiny random noise must not make the
        // projectile faster than vanilla intended (a tracker/vanilla mismatch on
        // the exact jump tick could otherwise inflate the speed).
        double vanillaSpeed = vanilla.length();
        if (newVel.length() > vanillaSpeed && vanillaSpeed > 1.0e-9) {
            newVel = newVel.normalize().multiply(vanillaSpeed);
        }

        // IMPORTANT: do NOT set the velocity during the launch event. A fast
        // forward-moving projectile whose velocity is changed at spawn time
        // sometimes fails to render on the client (the change races with the
        // spawn packet). Instead let the projectile spawn with the vanilla
        // velocity — so the client renders it normally — and apply our
        // correction one tick later, once the entity is fully tracked. The
        // one-tick delay leaves only a negligible (~0.03 block) residual of the
        // original noise.
        final Vector corrected = newVel;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!projectile.isValid() || projectile.isDead()) return;
            projectile.setVelocity(corrected);
        }, 1L);
    }
}
