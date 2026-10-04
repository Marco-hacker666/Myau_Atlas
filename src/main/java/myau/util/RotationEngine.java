package myau.util;

import myau.module.modules.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * How a hand turns a mouse, shared by the modules that turn the player.
 *
 * Until 2026-09-25 each module had its own: Clutch the most careful (steps of
 * whole mouse counts, a speed that builds up over a tick or two and drifts
 * instead of jumping), KillAura a smoothstep with its own rounding, Scaffold
 * none at all -- it jumped straight to whatever angle it wanted, rounded to a
 * fixed 0.0096 that is no one's sensitivity. A server comparing turns sees
 * the weakest of them. This is Clutch's, taken out so all of them can use it:
 *
 * - step(): one tick of turning toward a target, at most maxStep degrees,
 *   speeding up by at most accel degrees a tick over the last tick's turn,
 *   the speed drifting within randomPercent, the result on the mouse grid.
 *   With features (2026-10-04, amounts in the Rotations module): NOISE, a
 *   normal speed wander in place of that drift; CURVE, a bowed path; EASE, a
 *   slowed last stretch. The plain step() asks for none and is unchanged.
 * - quantize(): just the grid, for a module that picks its own angle.
 * - nearestOnBox(): multipoint -- the point of a box nearest the line of
 *   sight, which is the least turning that still lands on it, instead of the
 *   centre every time.
 *
 * One instance per module: it remembers that module's last turn.
 */
public final class RotationEngine {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private float lastStep;
    private float thisStep;
    private float speedFactor = 1.0F;
    private int tick = Integer.MIN_VALUE;

    /** Forget the last turn: the next one starts from rest. */
    public void reset() {
        this.lastStep = 0.0F;
        this.thisStep = 0.0F;
        this.speedFactor = 1.0F;
    }

    /** The turn made on the previous tick, in the measure step() was last given. */
    public float lastStep() {
        roll();
        return this.lastStep;
    }

    /* A new tick moves this tick's turn to "last"; a skipped tick means the
       hand stopped, so the next turn starts from rest. */
    private void roll() {
        int now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        if (now == this.tick) {
            return;
        }
        this.lastStep = now == this.tick + 1 ? this.thisStep : 0.0F;
        this.thisStep = 0.0F;
        this.tick = now;
    }

    /**
     * One tick of turning from (fromYaw, fromPitch) toward (toYaw, toPitch).
     *
     * @param maxStep       the most to turn this tick, degrees
     * @param accel         the most the turn may exceed last tick's by; 0 for no limit
     * @param randomPercent how far below maxStep the speed may drift, 0-100
     * @param l1            measure turns as |yaw|+|pitch| (Clutch's plans are
     *                      costed that way) rather than the straight angle
     * @return {yaw, pitch}, yaw continuous with fromYaw
     */
    public float[] step(float fromYaw, float fromPitch, float toYaw, float toPitch,
                        float maxStep, float accel, int randomPercent, boolean l1) {
        return step(fromYaw, fromPitch, toYaw, toPitch, maxStep, accel, randomPercent, l1,
                EnumSet.noneOf(Feature.class));
    }

    /**
     * What a module lets the engine add to a turn (2026-10-04). The amounts
     * are the Rotations module's; with it off, none applies.
     *   NOISE  the speed wanders on a normal distribution, both ways, instead
     *          of randomPercent's uniform only-slower drift;
     *   CURVE  a turn that does not land this tick bows off its line along a
     *          smooth random walk;
     *   EASE   the last stretch is slowed, never below a floor, so the turn
     *          does not stop dead at full speed. Costs ticks.
     */
    public enum Feature { NOISE, CURVE, EASE }

    /** The random walk the bow follows, in standard deviations. */
    private double bow;
    private final Random noise = new Random();

    /**
     * step() with features: the same turn, then speed noise, easing and the
     * bow as asked for and as the Rotations module sets them. The step that
     * reaches the target is never bowed, so a click traced along it is exact.
     */
    public float[] step(float fromYaw, float fromPitch, float toYaw, float toPitch,
                        float maxStep, float accel, int randomPercent, boolean l1, Set<Feature> features) {
        roll();
        float deltaYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float deltaPitch = toPitch - fromPitch;
        float total = measure(deltaYaw, deltaPitch, l1);
        if (total < 0.05F) {
            return new float[]{fromYaw, fromPitch};
        }
        Rotations settings = features.isEmpty() ? null : Rotations.active();
        float step = maxStep;
        if (settings != null && features.contains(Feature.NOISE) && settings.speedNoise.getValue() > 0) {
            /* Normal around a speed a little under the cap (the cap is the
               module's promise), sd as set, three sd at most below. */
            double sd = settings.speedNoise.getValue() / 100.0;
            float drawn = (float) Math.max(Math.max(0.2, 1.0 - 3.0 * sd),
                    Math.min(1.0, 1.0 - sd + this.noise.nextGaussian() * sd));
            this.speedFactor += (drawn - this.speedFactor) * 0.35F;
            step *= this.speedFactor;
        } else if (randomPercent > 0) {
            float drawn = 1.0F - (float) RandomUtil.nextDouble(0.0, randomPercent / 100.0);
            this.speedFactor += (drawn - this.speedFactor) * 0.35F;
            step *= this.speedFactor;
        }
        if (accel > 0.0F) {
            step = Math.min(step, this.lastStep + accel);
        }
        if (settings != null && features.contains(Feature.EASE) && settings.ease.getValue()) {
            /* Over the last ease-zone degrees, the step shrinks with what is
               left, down to the floor -- never zero, so it always arrives. */
            float zone = settings.easeZone.getValue();
            if (total < zone) {
                step = Math.min(step, Math.max(settings.easeFloor.getValue(), step * total / zone));
            }
        }
        float scale = total <= step ? 1.0F : step / total;
        float nextYaw = fromYaw + deltaYaw * scale;
        float nextPitch = fromPitch + deltaPitch * scale;
        if (settings != null && features.contains(Feature.CURVE)) {
            double keep = settings.curveSmooth.getValue() / 100.0;
            this.bow = this.bow * keep + this.noise.nextGaussian() * Math.sqrt(1.0 - keep * keep);
            if (scale < 1.0F && settings.curve.getValue() > 0) {
                /* Off to the side of this tick's line, in proportion to the
                   step: (yaw, pitch) turned a quarter. */
                float moveYaw = deltaYaw * scale;
                float movePitch = deltaPitch * scale;
                float length = (float) Math.sqrt(moveYaw * moveYaw + movePitch * movePitch);
                if (length > 1.0F) {
                    float side = (float) (this.bow * settings.curve.getValue() / 100.0) * length;
                    nextYaw -= movePitch / length * side;
                    nextPitch += moveYaw / length * side;
                }
            }
        }
        float[] out = quantize(fromYaw, fromPitch, nextYaw, nextPitch);
        this.thisStep += measure(out[0] - fromYaw, out[1] - fromPitch, l1);
        return out;
    }

    private static float measure(float yaw, float pitch, boolean l1) {
        return l1 ? Math.abs(yaw) + Math.abs(pitch) : (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    /**
     * The turn from (fromYaw, fromPitch) to about (toYaw, toPitch) in whole
     * mouse counts at the player's sensitivity -- the only turns a mouse can
     * make. Off by at most half a count, a tenth of a degree or less.
     */
    public static float[] quantize(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        double unit = RotationUtil.gcd();
        float deltaYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float deltaPitch = toPitch - fromPitch;
        float yaw = fromYaw + (float) (Math.round(deltaYaw / unit) * unit);
        float pitch = fromPitch + (float) (Math.round(deltaPitch / unit) * unit);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    /** The straight angle between two looks, degrees. */
    public static float angle(float yawA, float pitchA, float yawB, float pitchB) {
        float yaw = MathHelper.wrapAngleTo180_float(yawA - yawB);
        float pitch = pitchA - pitchB;
        return (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    /** The unit vector of a look. */
    public static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** The look from the eyes to a point, yaw continuous with nearYaw. */
    public static float[] rotationsTo(Vec3 eyes, Vec3 point, float nearYaw) {
        double dx = point.xCoord - eyes.xCoord;
        double dy = point.yCoord - eyes.yCoord;
        double dz = point.zCoord - eyes.zCoord;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{nearYaw + MathHelper.wrapAngleTo180_float(yaw - nearYaw),
                MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    /**
     * Multipoint: the point of the box nearest the line of sight from the eyes
     * along (yaw, pitch). Already looking into the box, it is on the line and
     * no turn is needed; otherwise it is on the edge nearest the crosshair,
     * the smallest turn that lands. Found by projecting onto the line and
     * clamping into the box, a few times over -- exact for a line through the
     * box, and within a hair of it otherwise.
     */
    public static Vec3 nearestOnBox(AxisAlignedBB box, Vec3 eyes, float yaw, float pitch) {
        Vec3 look = direction(yaw, pitch);
        Vec3 point = new Vec3((box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0);
        for (int i = 0; i < 4; i++) {
            double t = Math.max(0.0, (point.xCoord - eyes.xCoord) * look.xCoord
                    + (point.yCoord - eyes.yCoord) * look.yCoord
                    + (point.zCoord - eyes.zCoord) * look.zCoord);
            point = RotationUtil.closestPointOnAabb(box, eyes.addVector(look.xCoord * t, look.yCoord * t, look.zCoord * t));
        }
        return point;
    }

    /**
     * The box shrunk toward its middle: inset of its width off each side and
     * the vertical band from low to high of its height. Aiming inside it, not
     * at the very edge, leaves room for the target to move before the look
     * falls off it.
     */
    public static AxisAlignedBB aimBox(AxisAlignedBB box, double inset, double low, double high) {
        double w = (box.maxX - box.minX) * inset;
        double d = (box.maxZ - box.minZ) * inset;
        double h = box.maxY - box.minY;
        return new AxisAlignedBB(box.minX + w, box.minY + h * low, box.minZ + d,
                box.maxX - w, box.minY + h * high, box.maxZ - d);
    }
}
