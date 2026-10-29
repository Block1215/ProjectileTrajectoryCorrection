package dev.bl.projectiletrajectorycorrection;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Switches off vanilla's own "ender pearl hits wind charge" test.
 *
 * Vanilla lets a pearl hit a wind charge with the charge's box inflated by a
 * full block. Thrown back to back the two start out overlapping, so the pearl
 * "hits" the charge the moment it is thrown and teleports its owner right
 * where they stand - and the charge flies on and bursts on the teleported
 * player. Pearl/charge hits are handled by {@link WindChargePearlCollisionTask}
 * instead.
 */
final class PearlHitListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPearlHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(event.getHitEntity() instanceof WindCharge)) return;
        event.setCancelled(true);
        restoreMove(pearl);
    }

    /**
     * Vanilla already stopped the pearl at the hit point for this tick; put it
     * where it would have gone had the charge not been there.
     */
    private static void restoreMove(EnderPearl pearl) {
        try {
            Object handle = pearl.getClass().getMethod("getHandle").invoke(pearl);
            Object old = handle.getClass().getMethod("oldPosition").invoke(handle);
            double ox = (double) old.getClass().getMethod("x").invoke(old);
            double oy = (double) old.getClass().getMethod("y").invoke(old);
            double oz = (double) old.getClass().getMethod("z").invoke(old);

            World world = pearl.getWorld();
            Vector vel = pearl.getVelocity();
            Vector to = new Vector(ox, oy, oz).add(vel);
            double len = vel.length();
            if (len > 1e-9) {
                RayTraceResult block = world.rayTraceBlocks(
                    new Location(world, ox, oy, oz), vel.clone().normalize(), len,
                    FluidCollisionMode.NEVER, true);
                if (block != null) to = block.getHitPosition();
            }
            handle.getClass().getMethod("setPos", double.class, double.class, double.class)
                .invoke(handle, to.getX(), to.getY(), to.getZ());
        } catch (Throwable ignored) {
            // Leave the pearl where vanilla stopped it; it carries on next tick.
        }
    }
}
