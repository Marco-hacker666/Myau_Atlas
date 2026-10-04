package myau.util;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * KillAura's "Advanced" rotations: the aim model of Rise 6.9.5's KillAura
 * (Rotation Mode "Advanced"), rewritten here from reading how it behaves --
 * the same steps, the same constants, the same defaults (docs/PAID-CLIENT-GAP.md,
 * 2026-09-28). None of its code is copied.
 *
 * Where our other modes turn at a speed, this one moves a simulated hand:
 *
 * - Where to look (aim point). A point on the target predicted from how the
 *   two players have been moving, which the hand only follows after a
 *   reaction time (180 ms, jittered). Small drifts wait for the reaction;
 *   a big jump is taken sooner. Each update covers part of the way, never
 *   all of it, so the look lags a strafing target the way an eye does.
 * - How to get there (step). A WindMouse: a pull toward the target
 *   ("gravity") plus a wandering push ("wind") that dies down as the target
 *   gets near, a step size that is large far away and shrinks close in,
 *   an occasional short burst of speed, a floor so the last few degrees are
 *   not crawled, and a proportional term.
 * - Overshoot. On a new target far enough away, most of the time the turn
 *   aims just past it and comes back, as a flick does.
 * - Settle. Within the dead zone and on the target, the hand stops for a
 *   couple of ticks instead of trembling on it.
 * - Flick guard. No single tick turns further than a hand can.
 * - Trigger reaction. The first hit on a target waits a human reaction
 *   (95 ms, jittered) after the crosshair lands on it.
 *
 * The caller puts the result on the mouse grid (RotationUtil.gcd) and does
 * the ray casts; everything here is plain arithmetic, so it can be tested.
 *
 * One instance per module. Client thread.
 */
public final class AdvancedAim {

    /** The model's settings; defaults are Rise's. */
    public static final class Settings {
        public float gravity = 9.0F;
        public float wind = 6.0F;
        public float dampedDistance = 12.0F;
        public float maxStep = 15.0F;
        public float overshootChance = 77.0F;
        public float overshootScale = 0.0F;
        public float overshootMax = 17.0F;
        public float gaussian = 0.0F;
        public float accuracy = 40.0F;
        public float minStep = 0.0F;
        public float prediction = 1.0F;
        public float deadzone = 1.0F;
        public float anchor = 0.0F;
        public int holdTicks = 2;
        public float cruiseFloor = 1.0F;
        public float paceJitter = 0.0F;
        public float burstChance = 21.0F;
        public float burstStrength = 0.0F;
        public boolean flickGuard = true;
        public float flickMax = 29.0F;
        public float aimReaction = 180.0F;
        public float aimReactionJitter = 44.0F;
        public float triggerReaction = 95.0F;
        public float triggerReactionJitter = 30.0F;
    }

    private static final float SQRT3 = (float) Math.sqrt(3.0);
    private static final float SQRT5 = (float) Math.sqrt(5.0);
    private static final int NO_TARGET = Integer.MIN_VALUE;

    private final Random random;

    /* The hand. */
    private float windX;
    private float windY;
    private float velocityX;
    private float velocityY;
    private float stepSize;
    private float[] overshoot;
    private int overshootTicks;
    private int holdTicks;
    private int burstTicks;
    private float burstStrength;
    private int lastTargetId = NO_TARGET;

    /* The eye. */
    private Vec3 aimPoint;
    private long nextAimUpdate;
    private Vec3 targetMotion = new Vec3(0.0, 0.0, 0.0);
    private Vec3 playerMotion = new Vec3(0.0, 0.0, 0.0);

    /* The finger. */
    private int triggerTargetId = NO_TARGET;
    private boolean triggerArmed;
    private long triggerReadyAt;

    public AdvancedAim() {
        this(new Random());
    }

    /** With a seeded random, for tests. */
    public AdvancedAim(Random random) {
        this.random = random;
    }

    /** Forget everything: the next target starts from rest. */
    public void reset() {
        this.windX = 0.0F;
        this.windY = 0.0F;
        this.velocityX = 0.0F;
        this.velocityY = 0.0F;
        this.stepSize = 0.0F;
        this.overshoot = null;
        this.overshootTicks = 0;
        this.holdTicks = 0;
        this.burstTicks = 0;
        this.burstStrength = 0.0F;
        this.lastTargetId = NO_TARGET;
        this.aimPoint = null;
        this.nextAimUpdate = 0L;
        this.targetMotion = new Vec3(0.0, 0.0, 0.0);
        this.playerMotion = new Vec3(0.0, 0.0, 0.0);
        disarm();
    }

    // ------------------------------------------------------------ the eye

    /** One tick of both players' movement, smoothed (call once a tick). */
    public void observeMotion(Vec3 targetDelta, Vec3 playerDelta) {
        this.targetMotion = scale(this.targetMotion, 0.72).add(scale(targetDelta, 0.28));
        this.playerMotion = scale(this.playerMotion, 0.76).add(scale(playerDelta, 0.24));
    }

    /**
     * Where the target will be, relative to how this player is moving: a
     * point high on its body, led by the smoothed relative motion, kept on
     * (or just off) its box.
     */
    public Vec3 predictedPoint(double posX, double posY, double posZ, double height, AxisAlignedBB box,
                               Vec3 eyes, Settings s) {
        double distance = eyes.distanceTo(new Vec3(posX, posY + height * 0.75, posZ));
        double lead = Math.min(3.5, Math.max(0.0, s.prediction + distance * 0.017));
        Vec3 relative = this.targetMotion.subtract(this.playerMotion);
        double y = Math.max(0.35, Math.min(height * 0.82, height - 0.12));
        Vec3 point = new Vec3(posX, posY + y, posZ).add(scale(relative, lead));
        return clampToBox(point, box.expand(0.18, 0.1, 0.18));
    }

    /**
     * The point the hand is aiming at: it follows where the target is
     * wanted only after a reaction time, and then only part of the way.
     */
    public Vec3 aimPoint(Vec3 wanted, AxisAlignedBB box, double distance, long now, Settings s) {
        if (this.aimPoint == null) {
            this.aimPoint = wanted;
            this.nextAimUpdate = now + reactionDelay(false, s);
            return this.aimPoint;
        }
        Vec3 delta = wanted.subtract(this.aimPoint);
        double moved = delta.lengthVector();
        double noticeable = 0.028 + Math.min(0.2, distance * 0.006);
        double large = 0.36 + Math.min(0.85, distance * 0.07);
        boolean jumped = moved > large;
        if (moved > noticeable && (now >= this.nextAimUpdate || jumped)) {
            double share = Math.min(0.7, Math.max(0.08, s.anchor + moved * 0.32));
            this.aimPoint = clampToBox(this.aimPoint.add(scale(delta, share)), box.expand(0.12, 0.12, 0.12));
            this.nextAimUpdate = now + reactionDelay(jumped, s);
        }
        return this.aimPoint;
    }

    /** Reaction time for the eye; quicker for a jump than for a drift. */
    long reactionDelay(boolean jumped, Settings s) {
        double mean = s.aimReaction;
        double jitter = s.aimReactionJitter;
        if (jumped) {
            mean *= 0.6;
            jitter *= 0.5;
        }
        return jittered(mean, jitter, 20L, 700L);
    }

    /**
     * The points to try, best first when several can hit: the aim point, the
     * box's middle at eye height, and a grid over the box's faces.
     */
    public static List<Vec3> candidates(AxisAlignedBB box, Vec3 aim, Vec3 eyes) {
        double[] across = {0.05, 0.25, 0.5, 0.75, 0.95};
        double[] up = {0.05, 0.2, 0.35, 0.5, 0.65, 0.8, 0.95};
        List<Vec3> points = new ArrayList<Vec3>(up.length * across.length * 4 + across.length * across.length * 2 + 2);
        points.add(aim);
        double height = box.maxY - box.minY;
        points.add(new Vec3((box.minX + box.maxX) / 2.0,
                box.minY + Math.max(0.0, Math.min(eyes.yCoord - box.minY, height * 0.9)),
                (box.minZ + box.maxZ) / 2.0));
        for (double v : up) {
            for (double a : across) {
                points.add(at(box, 0.01, v, a));
                points.add(at(box, 0.99, v, a));
                points.add(at(box, a, v, 0.01));
                points.add(at(box, a, v, 0.99));
            }
        }
        for (double a : across) {
            for (double b : across) {
                points.add(at(box, a, 0.02, b));
                points.add(at(box, a, 0.98, b));
            }
        }
        return points;
    }

    /** The point of the box nearest the eyes, a hair inside it. */
    public static Vec3 nearestInside(AxisAlignedBB box, Vec3 eyes) {
        return new Vec3(clampInset(eyes.xCoord, box.minX + 0.03, box.maxX - 0.03),
                clampInset(eyes.yCoord, box.minY + 0.03, box.maxY - 0.03),
                clampInset(eyes.zCoord, box.minZ + 0.03, box.maxZ - 0.03));
    }

    // ------------------------------------------------------------ the hand

    /**
     * One tick of turning from the current look toward the wanted one.
     *
     * @param onTarget whether the current look already lands on the target
     * @return {yaw, pitch}, before the mouse grid
     */
    public float[] step(float yaw, float pitch, float wantYaw, float wantPitch, int targetId, boolean onTarget,
                        Settings s) {
        float maxStep = s.maxStep;
        float damped = s.dampedDistance;
        float minStep = Math.max(0.25F, s.minStep);
        float accuracy = MathHelper.clamp_float(s.accuracy / 100.0F, 0.4F, 1.0F);
        if (this.stepSize <= 0.001F) {
            this.stepSize = maxStep;
        }
        if (targetId != this.lastTargetId) {
            this.windX = 0.0F;
            this.windY = 0.0F;
            this.velocityX = 0.0F;
            this.velocityY = 0.0F;
            this.stepSize = maxStep;
            this.overshoot = null;
            this.overshootTicks = 0;
            this.aimPoint = null;
            this.nextAimUpdate = 0L;
            this.holdTicks = 0;
            this.burstTicks = 0;
            this.burstStrength = 0.0F;
            disarm();
            this.lastTargetId = targetId;
            planOvershoot(yaw, pitch, wantYaw, wantPitch, s);
        }

        float goalYaw = wantYaw;
        float goalPitch = wantPitch;
        boolean overshooting = false;
        if (this.overshoot != null) {
            float oy = MathHelper.wrapAngleTo180_float(this.overshoot[0] - yaw);
            float op = this.overshoot[1] - pitch;
            if (!((float) Math.hypot(oy, op) < 1.15F) && this.overshootTicks-- > 0) {
                goalYaw = this.overshoot[0];
                goalPitch = this.overshoot[1];
                overshooting = true;
            } else {
                this.overshoot = null;
            }
        }

        float dx = MathHelper.wrapAngleTo180_float(goalYaw - yaw);
        float dy = goalPitch - pitch;
        float distance = (float) Math.hypot(dx, dy);
        float safe = Math.max(0.001F, distance);
        float deadzone = s.deadzone;

        /* Settle: on the target and close enough, hold still. */
        if (!overshooting && onTarget && distance <= deadzone) {
            this.holdTicks = Math.max(this.holdTicks, s.holdTicks);
        }
        if (!overshooting && this.holdTicks > 0 && onTarget && distance <= deadzone * 1.35F) {
            this.holdTicks--;
            this.velocityX *= 0.42F;
            this.velocityY *= 0.42F;
            return new float[]{yaw, pitch};
        }
        if (this.holdTicks > 0) {
            this.holdTicks--;
        }
        if (distance < 0.001F) {
            this.velocityX *= 0.6F;
            this.velocityY *= 0.6F;
            return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
        }

        /* Wind far away, calm close in; the step size follows. */
        float windMagnitude = Math.min(s.wind, distance);
        if (distance >= damped) {
            this.windX = this.windX / SQRT3 + gaussian(windMagnitude / SQRT5, windMagnitude);
            this.windY = this.windY / SQRT3 + gaussian(windMagnitude / SQRT5, windMagnitude);
            this.stepSize = Math.max(this.stepSize, maxStep * (0.78F + this.random.nextFloat() * 0.32F));
        } else {
            this.windX /= SQRT3;
            this.windY /= SQRT3;
            if (this.stepSize < minStep) {
                this.stepSize = minStep + this.random.nextFloat() * 0.7F;
            } else {
                this.stepSize = Math.max(minStep, this.stepSize / SQRT5);
            }
        }

        float gravity = s.gravity;
        if (distance < 3.5F) {
            gravity *= 0.82F + accuracy * 0.12F;
        }
        this.velocityX += this.windX + gravity * dx / safe;
        this.velocityY += this.windY + gravity * dy / safe;

        /* Pace: jitter, and now and then a short burst. */
        float pace = 1.0F;
        if (s.paceJitter > 1.0E-4F) {
            pace += gaussian(s.paceJitter * 0.42F, s.paceJitter);
        }
        if (!overshooting && distance > deadzone * 1.6F && distance < damped * 1.45F) {
            float chance = s.burstChance / 100.0F;
            if (this.burstTicks <= 0 && chance > 1.0E-4F && this.random.nextFloat() < chance * 0.12F) {
                this.burstTicks = 1 + this.random.nextInt(2);
                this.burstStrength = s.burstStrength * (0.65F + this.random.nextFloat() * 0.55F);
            }
        }
        if (this.burstTicks > 0) {
            pace *= 1.0F + this.burstStrength;
            this.burstTicks--;
        } else {
            this.burstStrength *= 0.55F;
        }
        pace = MathHelper.clamp_float(pace, 0.72F, 1.55F);

        float cap = Math.max(minStep, this.stepSize * pace);
        float speed = (float) Math.hypot(this.velocityX, this.velocityY);
        if (speed > cap) {
            float to = cap * (0.52F + this.random.nextFloat() * 0.48F);
            this.velocityX = this.velocityX / speed * to;
            this.velocityY = this.velocityY / speed * to;
        }
        if (distance < 2.0F) {
            float damping = 0.86F + accuracy * 0.08F;
            this.velocityX *= damping;
            this.velocityY *= damping;
        } else if (distance < 5.0F) {
            this.velocityX *= 0.94F;
            this.velocityY *= 0.94F;
        }

        /* Cruise floor: the last few degrees are not crawled. */
        if (!overshooting && s.cruiseFloor > 0.01F && distance > deadzone * 1.25F && distance < 14.0F) {
            float floor = s.cruiseFloor * (0.86F + this.random.nextFloat() * 0.24F);
            float now = (float) Math.hypot(this.velocityX, this.velocityY);
            if (now < floor) {
                float add = (floor - now) * (0.72F + this.random.nextFloat() * 0.36F);
                this.velocityX += dx / safe * add;
                this.velocityY += dy / safe * add;
            }
        }

        /* Proportional term, larger far away. */
        float far = MathHelper.clamp_float(distance / 45.0F, 0.0F, 1.0F);
        float gain = (0.16F + far * 0.42F) * (0.55F + accuracy * 0.45F);
        gain *= MathHelper.clamp_float(0.88F + (pace - 1.0F) * 0.5F, 0.74F, 1.18F);
        if (this.burstStrength > 0.02F) {
            gain *= 1.0F + this.burstStrength * 0.35F;
        }
        if (overshooting) {
            gain *= 0.55F;
        }
        float outYaw = yaw + this.velocityX + dx * gain;
        float outPitch = MathHelper.clamp_float(pitch + this.velocityY + dy * (gain * 0.85F), -89.9F, 89.9F);

        /* Fine settle: the last degree and a half by fraction, not by force. */
        if (!overshooting && distance < 1.65F) {
            float fraction = (0.56F + accuracy * 0.28F) * (0.94F + this.random.nextFloat() * 0.1F);
            outYaw = yaw + dx * fraction;
            outPitch = MathHelper.clamp_float(pitch + dy * fraction, -89.9F, 89.9F);
            this.velocityX *= 0.55F;
            this.velocityY *= 0.55F;
        }

        if (s.gaussian > 0.0F && distance > 0.35F) {
            float spread = MathHelper.clamp_float(distance / 16.0F, 0.25F, 1.0F) * (1.05F - accuracy * 0.35F);
            if (overshooting) {
                spread *= 0.8F;
            }
            outYaw += gaussian(s.gaussian * spread, s.gaussian * 2.2F * spread);
            outPitch += gaussian(s.gaussian * 0.45F * spread, s.gaussian * 1.5F * spread);
            outPitch = MathHelper.clamp_float(outPitch, -89.9F, 89.9F);
        }

        /* Flick guard: no tick turns further than a hand can. */
        if (s.flickGuard && !overshooting) {
            float fx = MathHelper.wrapAngleTo180_float(outYaw - yaw);
            float fy = outPitch - pitch;
            float turn = (float) Math.hypot(fx, fy);
            if (turn > 0.001F) {
                float limit = s.flickMax * (0.85F + MathHelper.clamp_float(distance / 24.0F, 0.0F, 1.0F) * 0.4F);
                limit *= 0.92F + this.random.nextFloat() * 0.2F;
                if (turn > limit) {
                    float ratio = limit / turn;
                    outYaw = yaw + fx * ratio;
                    outPitch = MathHelper.clamp_float(pitch + fy * ratio, -89.9F, 89.9F);
                    this.velocityX *= 0.82F;
                    this.velocityY *= 0.82F;
                }
            }
        }
        return new float[]{outYaw, outPitch};
    }

    /** On a new target far enough off, usually aim just past it first. */
    private void planOvershoot(float yaw, float pitch, float wantYaw, float wantPitch, Settings s) {
        float chance = s.overshootChance;
        if (chance <= 0.0F) {
            return;
        }
        float accuracy = MathHelper.clamp_float(s.accuracy / 100.0F, 0.4F, 1.0F);
        float effective = chance * (1.15F - accuracy * 0.35F);
        float dx = MathHelper.wrapAngleTo180_float(wantYaw - yaw);
        float dy = wantPitch - pitch;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < 9.0F || this.random.nextFloat() * 100.0F > effective) {
            return;
        }
        float size = Math.min(s.overshootMax, Math.max(1.5F, distance * s.overshootScale));
        size *= 0.9F + (1.0F - accuracy) * 0.35F;
        float safe = Math.max(0.001F, distance);
        float ux = dx / safe;
        float uy = dy / safe;
        float along = size + Math.abs(gaussian(size * 0.25F, size));
        float side = gaussian(size * 0.35F, size);
        this.overshoot = new float[]{
                wantYaw + ux * along - uy * side,
                MathHelper.clamp_float(wantPitch + uy * along + ux * side * 0.65F, -89.0F, 89.0F)};
        this.overshootTicks = 8 + this.random.nextInt(6);
    }

    /** Whether an overshoot is planned or under way (tests, debugging). */
    public boolean overshooting() {
        return this.overshoot != null;
    }

    // ------------------------------------------------------------ the finger

    /**
     * Whether the first hit may go: a reaction time after the look first
     * landed on this target. Looking away re-arms it.
     */
    public boolean triggerReady(int targetId, boolean onTarget, long now, Settings s) {
        if (!onTarget) {
            disarm();
            return false;
        }
        if (this.triggerArmed && this.triggerTargetId == targetId) {
            return now >= this.triggerReadyAt;
        }
        this.triggerTargetId = targetId;
        this.triggerArmed = true;
        long delay = jittered(s.triggerReaction, s.triggerReactionJitter, 0L, 450L);
        this.triggerReadyAt = now + delay;
        return delay <= 0L;
    }

    /** No target in reach: the next landing waits a reaction again. */
    public void lostTarget() {
        disarm();
    }

    private void disarm() {
        this.triggerTargetId = NO_TARGET;
        this.triggerArmed = false;
        this.triggerReadyAt = 0L;
    }

    // ------------------------------------------------------------ helpers

    /**
     * A normal draw of this spread, clamped to +/- max.
     *
     * Rise's decompiled source lost the draw's assignment here (it returns
     * 0, or +max on a rare tail); the clamp around it only makes sense for
     * this, so this is what is implemented.
     */
    float gaussian(float spread, float max) {
        if (spread <= 0.0F) {
            return 0.0F;
        }
        float limit = Math.max(1.0E-4F, Math.abs(max));
        float value = (float) (this.random.nextGaussian() * spread);
        return Math.max(-limit, Math.min(limit, value));
    }

    /** A delay around mean with a normal jitter, clamped. */
    long jittered(double mean, double jitter, long min, long max) {
        double value = mean;
        if (jitter > 1.0E-4) {
            value += this.random.nextGaussian() * jitter;
        }
        return Math.round(Math.max(min, Math.min(max, value)));
    }

    public static Vec3 clampToBox(Vec3 point, AxisAlignedBB box) {
        return new Vec3(Math.max(box.minX, Math.min(point.xCoord, box.maxX)),
                Math.max(box.minY, Math.min(point.yCoord, box.maxY)),
                Math.max(box.minZ, Math.min(point.zCoord, box.maxZ)));
    }

    private static double clampInset(double value, double low, double high) {
        return low > high ? (low + high) / 2.0 : Math.max(low, Math.min(high, value));
    }

    private static Vec3 at(AxisAlignedBB box, double x, double y, double z) {
        return new Vec3(box.minX + (box.maxX - box.minX) * x, box.minY + (box.maxY - box.minY) * y,
                box.minZ + (box.maxZ - box.minZ) * z);
    }

    private static Vec3 scale(Vec3 v, double k) {
        return new Vec3(v.xCoord * k, v.yCoord * k, v.zCoord * k);
    }
}
