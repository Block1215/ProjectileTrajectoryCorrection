package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Vanilla's per-tick raycast can miss when a Wind Charge and an Ender Pearl move
 * at high relative velocity — their swept paths cross but neither ray lands
 * inside the other's bounding box, so the charge tunnels straight through.
 *
 * Each tick, once every entity has moved, the path the charge just travelled is
 * tested against the pearl in the pearl's frame of reference (so the pearl's own
 * motion is accounted for) - the same order vanilla evaluates projectile hits in
 * when the pearl ticks first. Doing it at a fixed point of the tick makes the
 * result independent of which of the two happens to tick first. This is the
 * ONLY pearl/charge hit: vanilla's own pearl-hits-charge test is switched off in
 * {@link PearlHitListener}.
 *
 * The hit volume is a cube centred on the pearl that starts at the pearl's own
 * size and grows at vanilla's projectile hit-margin rate (nothing for 2 ticks,
 * then +0.1 across per tick of pearl flight) up to the /ptc hitbox size.
 * A pearl and charge thrown back to back (instant pearl catch) leave the hand
 * overlapping; the small early volume lets them separate and meet up in the air
 * instead of at the hand. The charge is removed without exploding, so the burst
 * never knocks the teleported player around.
 */
public final class WindChargePearlCollisionTask extends BukkitRunnable {

    /** Server started with -Dptc.testlog=true: log every catch to the console. */
    private static final boolean TEST_LOG = Boolean.getBoolean("ptc.testlog");

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
                // Not moved yet (thrown since the last tick): nothing to test.
                if (wc.getTicksLived() < 1) continue;
                Vector wcVel = wc.getVelocity();
                if (wcVel.lengthSquared() < 1e-6) continue;

                // The path the charge travelled during the last tick (a wind
                // charge moves by exactly its velocity: no drag, no gravity).
                Vector wcNow = wc.getLocation().toVector();
                Vector wcPos = wcNow.clone().subtract(wcVel);
                Vector wcNext = wcNow;

                for (EnderPearl ep : pearls) {
                    if (!ep.isValid() || ep.isDead()) continue;
                    if (ep.getTicksLived() < 1) continue;

                    Vector epPos = ep.getLocation().toVector();
                    Vector epVel = ep.getVelocity();
                    Vector epNext = epPos.clone().add(epVel);

                    // Hit cube centred on the pearl, growing with its flight time.
                    double half = Settings.hitSize(ep.getTicksLived()) / 2.0;
                    Vector centre = ep.getBoundingBox().getCenter().subtract(epPos);
                    BoundingBox relBox = new BoundingBox(
                        centre.getX() - half, centre.getY() - half, centre.getZ() - half,
                        centre.getX() + half, centre.getY() + half, centre.getZ() + half);

                    // Cast the charge's path in the pearl's frame of reference so
                    // the pearl's own motion during the tick is accounted for.
                    Vector relStart = wcPos.clone().subtract(epPos);
                    Vector relEnd = wcNext.clone().subtract(epNext);
                    Vector relPath = relEnd.clone().subtract(relStart);
                    double relLen = relPath.length();

                    double t;
                    if (relBox.contains(relStart)) {
                        t = 0.0;
                    } else {
                        if (relLen < 1e-9) continue;
                        RayTraceResult hit = relBox.rayTrace(
                            relStart, relPath.clone().normalize(), relLen);
                        if (hit == null) continue;
                        t = Math.min(1.0,
                            hit.getHitPosition().clone().subtract(relStart).length() / relLen);
                    }

                    Vector epAt = epPos.clone().add(epVel.clone().multiply(t));
                    Location hitLoc = new Location(world,
                        epAt.getX(), epAt.getY(), epAt.getZ());

                    String report = "charge caught pearl: pearl age " + ep.getTicksLived()
                        + "t, charge age " + wc.getTicksLived() + "t, hit size "
                        + PtcDebug.f(half * 2.0) + String.format(java.util.Locale.ROOT,
                            ", at (%.2f, %.2f, %.2f)", epAt.getX(), epAt.getY(), epAt.getZ());
                    if (PtcDebug.on(ep.getShooter())) {
                        PtcDebug.send((Player) ep.getShooter(), "HIT: " + report);
                    }
                    if (TEST_LOG) plugin.getLogger().info("[test] " + report);
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

        Entity vehicle = player.getVehicle();
        if (vehicle != null) player.leaveVehicle();

        player.teleport(dest);
        player.setFallDistance(0.0f);

        // Vanilla applies 5.0 fall-style damage on EP TP (unless creative).
        if (player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.damage(5.0, ep);
        }

        World w = dest.getWorld();
        if (w != null) {
            w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    private void playEffects(Location hitLoc) {
        World world = hitLoc.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.GUST, hitLoc, 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, hitLoc, 1, 0.0, 0.0, 0.0, 0.0);
        world.playSound(hitLoc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        world.spawnParticle(Particle.PORTAL, hitLoc, 32, 0.5, 0.5, 0.5, 0.1);
    }

}
