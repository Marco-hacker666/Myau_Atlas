package myau.util;

/**
 * KeepRange's arithmetic, apart from the game so it can be tested
 * (2026-09-28, item 4 of the Rise comparison).
 *
 * Movement input is a forward and a strafe, each -1, 0 or 1 (times 0.3 while
 * sneaking), turned into a direction by the yaw the player moves with. Of
 * the eight non-zero inputs, one points most nearly away from the target;
 * "Stop" drops the parts of the player's own input that go the other way,
 * "Backwards" replaces the input with it.
 */
public final class KeepRangeMath {

    private KeepRangeMath() {
    }

    /**
     * The input {forward, strafe} whose direction at this yaw is closest to
     * (awayX, awayZ). Minecraft's moveFlying: x = strafe*cos - forward*sin,
     * z = forward*cos + strafe*sin, with the yaw in radians.
     */
    public static int[] bestAway(float yawDegrees, double awayX, double awayZ) {
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 1.0E-9) {
            return new int[]{-1, 0};
        }
        double ax = awayX / length;
        double az = awayZ / length;
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        int[] best = null;
        double bestDot = -Double.MAX_VALUE;
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                if (forward == 0 && strafe == 0) {
                    continue;
                }
                double x = strafe * cos - forward * sin;
                double z = forward * cos + strafe * sin;
                double norm = Math.sqrt(x * x + z * z);
                double dot = (x * ax + z * az) / norm;
                if (dot > bestDot + 1.0E-9) {
                    bestDot = dot;
                    best = new int[]{forward, strafe};
                }
            }
        }
        return best;
    }

    /**
     * The adjusted input {forward, strafe}.
     *
     * @param backwards "Backwards" mode: take the away input, at the size of
     *                  the player's own (sneaking stays slow). Otherwise
     *                  "Stop": zero each part that points against it.
     */
    public static float[] apply(boolean backwards, int[] away, float forward, float strafe) {
        if (forward == 0.0F && strafe == 0.0F) {
            return new float[]{forward, strafe};
        }
        if (backwards) {
            float size = Math.max(Math.abs(forward), Math.abs(strafe));
            return new float[]{away[0] * size, away[1] * size};
        }
        float outForward = away[0] != 0 && Math.signum(forward) == -away[0] ? 0.0F : forward;
        float outStrafe = away[1] != 0 && Math.signum(strafe) == -away[1] ? 0.0F : strafe;
        return new float[]{outForward, outStrafe};
    }

    /**
     * Whether keeping range applies yet: the player has been landing hits
     * without taking any for long enough. Rise counts eight ticks of target
     * hurt time per combo hit; 0 means always.
     */
    public static boolean comboReached(int comboTicks, int comboToStart) {
        return comboToStart <= 0 || comboTicks > comboToStart * 8;
    }
}
