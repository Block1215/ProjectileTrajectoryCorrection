package dev.bl.projectiletrajectorycorrection;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads the EXACT inertia vanilla blended into a throw.
 *
 * Vanilla's shootFromRotation adds
 *     (known.x, onGround ? 0 : known.y, known.z)
 * where {@code known = ServerPlayer.getKnownMovement()} is the movement the
 * client last reported (reset to zero on a client tick with no move packet).
 * That value is not exposed by the Bukkit API, and estimating it from position
 * samples is badly off at elytra speeds (packets arrive unevenly relative to
 * server ticks), so we read it straight from the server player.
 *
 * Paper runs with Mojang names, so these names are stable across 1.21.11 and
 * 26.x. If anything is missing, {@link #of(Player)} returns null and the caller
 * falls back to its own estimate.
 */
final class ShooterInertia {

    private static volatile boolean broken;
    private static Method getHandle;
    private static Method getKnownMovement;
    private static Method onGround;
    private static Field vx, vy, vz;

    private ShooterInertia() {}

    /** Inertia exactly as vanilla applied it, or null if unavailable. */
    static Vector of(Player player) {
        if (broken) return null;
        try {
            if (getHandle == null) {
                getHandle = player.getClass().getMethod("getHandle");
            }
            Object handle = getHandle.invoke(player);
            if (getKnownMovement == null) {
                Class<?> hc = handle.getClass();
                getKnownMovement = hc.getMethod("getKnownMovement");
                onGround = hc.getMethod("onGround");
                Class<?> vec3 = getKnownMovement.getReturnType();
                vx = vec3.getField("x");
                vy = vec3.getField("y");
                vz = vec3.getField("z");
            }
            Object known = getKnownMovement.invoke(handle);
            boolean grounded = (Boolean) onGround.invoke(handle);
            return new Vector(
                vx.getDouble(known),
                grounded ? 0.0 : vy.getDouble(known),
                vz.getDouble(known));
        } catch (Throwable t) {
            broken = true;
            return null;
        }
    }
}
