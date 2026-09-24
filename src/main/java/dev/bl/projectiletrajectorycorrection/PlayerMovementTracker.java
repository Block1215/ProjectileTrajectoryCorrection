package dev.bl.projectiletrajectorycorrection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Samples each online player's position once per tick.
 *
 * Two uses:
 *  - the per-tick delta, a fallback estimate of vanilla's "known movement"
 *    when it cannot be read directly (see {@link ShooterInertia});
 *  - a short position history, so an elytra wind charge can be thrown from
 *    where the player was a few ticks ago.
 *
 * Bukkit's {@code Player.getVelocity()} is unreliable for players (movement is
 * client-authoritative), so positions are sampled directly.
 */
public final class PlayerMovementTracker {

    // Ignore deltas larger than this (blocks/tick) - a teleport or portal, not
    // real movement. It also clears the history so no one is rewound across it.
    private static final double MAX_SANE_DELTA = 10.0;

    /** History length; comfortably above the largest rewind allowed. */
    private static final int HISTORY = Settings.REWIND_MAX + 2;

    private record Sample(int tick, Location loc) {}

    private final Map<UUID, Deque<Sample>> history = new HashMap<>();
    private final Map<UUID, Vector> velocities = new HashMap<>();
    private int tick;

    public void start(Plugin plugin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                sample();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void sample() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Location cur = player.getLocation();
            Deque<Sample> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
            Sample last = h.peekFirst();

            if (last != null && last.loc().getWorld() == cur.getWorld()) {
                Vector delta = cur.toVector().subtract(last.loc().toVector());
                if (delta.lengthSquared() <= MAX_SANE_DELTA * MAX_SANE_DELTA) {
                    velocities.put(id, delta);
                } else {
                    velocities.put(id, new Vector(0, 0, 0));
                    h.clear();
                }
            } else if (last != null) {
                h.clear();
            }

            h.addFirst(new Sample(tick, cur.clone()));
            while (h.size() > HISTORY) h.removeLast();
        }

        // Drop players who left.
        for (Iterator<UUID> it = history.keySet().iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (Bukkit.getPlayer(id) == null) {
                it.remove();
                velocities.remove(id);
            }
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

    /**
     * Where the player was {@code ticks} ticks before a throw happening now, or
     * the oldest known position if the history is shorter. Null if unknown.
     *
     * Throws are handled from packets, which the server processes before this
     * tick's scheduled sample - so the newest sample is already one tick old
     * at that point, and "N ticks ago" is N-1 samples back from it.
     */
    public Location getLocationTicksAgo(UUID playerId, int ticks) {
        Deque<Sample> h = history.get(playerId);
        if (h == null || h.isEmpty()) return null;
        int target = tick - Math.max(0, ticks - 1);
        Sample found = null;
        for (Sample s : h) {          // newest first
            found = s;
            if (s.tick() <= target) break;
        }
        return found.loc().clone();
    }
}
