package myau.util;

// Ported from OpenSkid (GPL-3.0): tunnel stuck/back/turn state, adapted from
// the Expo AutoTunnel concept. Used by Tower's Tunnel mode.
public class TunnelEngine {
    public enum Action {
        NONE,
        BACK,
        TURN
    }

    private boolean init;
    private double lastX;
    private double lastY;
    private double lastZ;
    private long lastProgressMs;
    private int backTicksLeft;
    private long lastTurnMs;

    public void reset() {
        this.init = false;
        this.backTicksLeft = 0;
        this.lastProgressMs = 0L;
        this.lastTurnMs = 0L;
    }

    public Action update(double x, double y, double z, boolean blocked, long nowMs, long stuckTimeoutMs,
                         int backTicks, long turnCooldownMs, boolean autoBack, boolean autoTurn) {
        if (!this.init) {
            this.init = true;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastProgressMs = nowMs;
            return Action.NONE;
        }
        double dx = x - this.lastX;
        double dz = z - this.lastZ;
        if (dx * dx + dz * dz > 0.0001) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastProgressMs = nowMs;
        }
        if (this.backTicksLeft > 0) {
            this.backTicksLeft--;
            this.lastProgressMs = nowMs;
            return Action.BACK;
        }
        if (blocked && nowMs - this.lastProgressMs >= stuckTimeoutMs) {
            if (autoBack && backTicks > 0) {
                this.backTicksLeft = backTicks - 1;
                return Action.BACK;
            }
            if (autoTurn && nowMs - this.lastTurnMs >= turnCooldownMs) {
                this.lastTurnMs = nowMs;
                this.lastProgressMs = nowMs;
                return Action.TURN;
            }
        }
        return Action.NONE;
    }
}
