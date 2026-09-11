package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Each server tick, scan for Wind Charges that are about to "tunnel through"
 * an Ender Pearl (vanilla swept-AABB collision misses high relative velocity
 * pairs). On detection we:
 *   1. Teleport the Ender Pearl's owner to the swept collision point — this
 *      matches user expectation that the projectile combo TPs the player.
 *   2. Reproduce vanilla EP TP side effects (portal particles + sound + 5.0
 *      fall damage) so survival use feels correct.
 *   3. Play a Wind Charge burst at the collision point and remove both
 *      projectiles.
 */
public final class WindChargePearlCollisionTask extends BukkitRunnable {

    private static final double HIT_RADIUS = 0.6;
    private static final double HIT_RADIUS_SQ = HIT_RADIUS * HIT_RADIUS;

    private final Plugin plugin;
    private final Set<UUID> consumed = new HashSet<>();

    public WindChargePearlCollisionTask(Plugin plugin) {
        this.plugin = plugin;
    }

    public static void start(Plugin plugin) {
        new WindChargePearlCollisionTask(plugin).runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public void run() {
        consumed.clear();
        for (World world : Bukkit.getWorlds()) {
            Collection<WindCharge> windCharges = world.getEntitiesByClass(WindCharge.class);
            if (windCharges.isEmpty()) continue;
            Collection<EnderPearl> pearls = world.getEntitiesByClass(EnderPearl.class);
            if (pearls.isEmpty()) continue;

            for (WindCharge wc : windCharges) {
                if (consumed.contains(wc.getUniqueId())) continue;
                if (!wc.isValid() || wc.isDead()) continue;
                Vector wcVel = wc.getVelocity();
                if (wcVel.lengthSquared() < 1e-6) continue;

                Vector wcPos = wc.getLocation().toVector();
                Vector wcNext = wcPos.clone().add(wcVel);

                for (EnderPearl ep : pearls) {
                    if (!ep.isValid() || ep.isDead()) continue;

                    Vector epPos = ep.getLocation().toVector();
                    Vector epVel = ep.getVelocity();
                    Vector epNext = epPos.clone().add(epVel);

                    double minDistSq = minSegmentDistSq(wcPos, wcNext, epPos, epNext);
                    if (minDistSq > HIT_RADIUS_SQ) continue;

                    double tStar = closestApproachT(wcPos, wcNext, epPos, epNext);
                    Vector wcAt = wcPos.clone().add(wcVel.clone().multiply(tStar));
                    Vector epAt = epPos.clone().add(epVel.clone().multiply(tStar));
                    Location hitLoc = new Location(world,
                        (wcAt.getX() + epAt.getX()) * 0.5,
                        (wcAt.getY() + epAt.getY()) * 0.5,
                        (wcAt.getZ() + epAt.getZ()) * 0.5);

                    teleportOwner(ep, hitLoc);
                    playEffects(hitLoc);
                    ep.remove();
                    wc.remove();
                    consumed.add(wc.getUniqueId());
                    break;
                }
            }
        }
    }

    private void teleportOwner(EnderPearl ep, Location hitLoc) {
        ProjectileSource shooter = ep.getShooter();
        if (!(shooter instanceof Player player)) return;
        if (!player.isOnline()) return;

        Location dest = hitLoc.clone();
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());

        // Dismount any vehicle first — vanilla EP TP does the same.
        Entity vehicle = player.getVehicle();
        if (vehicle != null) player.leaveVehicle();

        player.teleport(dest);
        player.setFallDistance(0.0f);

        // Vanilla applies 5.0 fall-style damage on EP TP (unless creative).
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            player.damage(5.0, ep);
        }

        // Vanilla TP sound on the player's new position.
        World w = dest.getWorld();
        if (w != null) {
            w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    private void playEffects(Location hitLoc) {
        World world = hitLoc.getWorld();
        if (world == null) return;
        // Wind Charge burst visual + sound at the collision point.
        world.spawnParticle(Particle.GUST, hitLoc, 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, hitLoc, 1, 0.0, 0.0, 0.0, 0.0);
        world.playSound(hitLoc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        // Portal particles for the Ender Pearl side, like the pearl's own onHit.
        world.spawnParticle(Particle.PORTAL, hitLoc, 32, 0.5, 0.5, 0.5, 0.1);
    }

    private static double minSegmentDistSq(Vector a1, Vector a2, Vector b1, Vector b2) {
        Vector dPos = a1.clone().subtract(b1);
        Vector dVel = a2.clone().subtract(a1).subtract(b2.clone().subtract(b1));
        double dvSq = dVel.lengthSquared();
        if (dvSq < 1e-9) return dPos.lengthSquared();
        double t = -dPos.dot(dVel) / dvSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        Vector closest = dPos.clone().add(dVel.multiply(t));
        return closest.lengthSquared();
    }

    private static double closestApproachT(Vector a1, Vector a2, Vector b1, Vector b2) {
        Vector dPos = a1.clone().subtract(b1);
        Vector dVel = a2.clone().subtract(a1).subtract(b2.clone().subtract(b1));
        double dvSq = dVel.lengthSquared();
        if (dvSq < 1e-9) return 0.0;
        double t = -dPos.dot(dVel) / dvSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        return t;
    }
}
