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
 * Each tick we redo vanilla's ray-vs-box test in the pearl's frame of reference,
 * so the pearl's motion during the tick is accounted for. The hit box is the
 * same size vanilla would use, so this only fixes missed hits - it never makes
 * the pair collide from further away than normal.
 */
public final class WindChargePearlCollisionTask extends BukkitRunnable {

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

                // Fixed size: unlike vanilla it never grows with the charge's
                // age. Set in game with /ptc hitbox.
                double margin = Settings.margin();

                for (EnderPearl ep : pearls) {
                    if (!ep.isValid() || ep.isDead()) continue;

                    Vector epPos = ep.getLocation().toVector();
                    Vector epVel = ep.getVelocity();
                    Vector epNext = epPos.clone().add(epVel);

                    // Cast the charge's path in the pearl's frame of reference so
                    // the pearl's own motion during the tick is accounted for.
                    BoundingBox relBox = ep.getBoundingBox().clone()
                        .expand(margin)
                        .shift(epPos.clone().multiply(-1.0));
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

                    if (PtcDebug.on(ep.getShooter())) {
                        PtcDebug.send((Player) ep.getShooter(), "HIT: charge caught pearl (charge age "
                            + wc.getTicksLived() + "t)");
                    }
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
