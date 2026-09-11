package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each online player's per-tick position delta, which equals their
 * velocity in blocks/tick — the same "known movement" (client-reported motion)
 * that vanilla folds into thrown projectiles as inertia.
 *
 * Bukkit's {@code Player.getVelocity()} is unreliable for players (movement is
 * client-authoritative and the server-side delta is often zero), so we sample
 * positions ourselves once per tick.
 */
public final class PlayerMovementTracker {

    // Ignore deltas larger than this (blocks/tick) — they indicate a teleport or
    // portal, not real movement, and must not be treated as throw inertia.
    private static final double MAX_SANE_DELTA = 10.0;

    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Vector> velocities = new HashMap<>();

    public void start(Plugin plugin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                sample();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void sample() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Location cur = player.getLocation();
            Location last = lastLocations.get(id);

            if (last != null && last.getWorld() == cur.getWorld()) {
                Vector delta = cur.toVector().subtract(last.toVector());
                if (delta.lengthSquared() <= MAX_SANE_DELTA * MAX_SANE_DELTA) {
                    velocities.put(id, delta);
                } else {
                    velocities.put(id, new Vector(0, 0, 0));
                }
            }
            lastLocations.put(id, cur.clone());
        }
    }

    /**
     * Returns the player's most recent per-tick movement (blocks/tick), or a
     * zero vector if not yet sampled.
     */
    public Vector getVelocity(UUID playerId) {
        Vector v = velocities.get(playerId);
        return v != null ? v.clone() : new Vector(0, 0, 0);
    }

    public void forget(UUID playerId) {
        lastLocations.remove(playerId);
        velocities.remove(playerId);
    }
}
