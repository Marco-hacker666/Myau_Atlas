package myau.util;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** KillAura's Advanced rotation model (2026-09-28, Rise item 3). */
public class AdvancedAimTest {

    private static AdvancedAim.Settings noOvershoot() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.overshootChance = 0.0F;
        return s;
    }

    private static float angle(float yawA, float pitchA, float yawB, float pitchB) {
        return (float) Math.hypot(MathHelper.wrapAngleTo180_float(yawA - yawB), pitchA - pitchB);
    }

    /** Ticks until within 1 degree of the target, from 60 away. */
    private static int ticksToSettle(long seed, AdvancedAim.Settings s, float[] maxTurn) {
        AdvancedAim aim = new AdvancedAim(new Random(seed));
        float yaw = 0.0F;
        float pitch = 0.0F;
        for (int tick = 1; tick <= 200; tick++) {
            float[] next = aim.step(yaw, pitch, 60.0F, 10.0F, 7, false, s);
            maxTurn[0] = Math.max(maxTurn[0], angle(next[0], next[1], yaw, pitch));
            yaw = next[0];
            pitch = next[1];
            if (angle(yaw, pitch, 60.0F, 10.0F) < 1.0F) {
                return tick;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ the hand

    @Test
    public void itGetsThereInAHumanTime() {
        for (long seed = 1; seed <= 30; seed++) {
            float[] maxTurn = {0.0F};
            int ticks = ticksToSettle(seed, noOvershoot(), maxTurn);
            assertTrue("seed " + seed + " never settled", ticks > 0);
            assertTrue("seed " + seed + " settled in " + ticks + " ticks: not a snap", ticks >= 3);
            assertTrue("seed " + seed + " took " + ticks + " ticks", ticks <= 40);
        }
    }

    @Test
    public void noTickTurnsFurtherThanTheFlickGuardAllows() {
        AdvancedAim.Settings s = noOvershoot();
        s.gravity = 20.0F;
        s.maxStep = 60.0F;
        s.wind = 10.0F;
        /* The widest the guard ever allows: flickMax x 1.25 x 1.12. */
        float widest = s.flickMax * 1.25F * 1.12F;
        for (long seed = 1; seed <= 30; seed++) {
            AdvancedAim aim = new AdvancedAim(new Random(seed));
            float[] next = aim.step(0.0F, 0.0F, 170.0F, 0.0F, 1, false, s);
            assertTrue("seed " + seed + ": " + next[0], angle(next[0], next[1], 0.0F, 0.0F) <= widest + 1.0E-3F);
        }
    }

    @Test
    public void withoutTheFlickGuardAFarTurnIsFaster() {
        AdvancedAim.Settings guarded = noOvershoot();
        guarded.gravity = 20.0F;
        guarded.maxStep = 60.0F;
        AdvancedAim.Settings free = noOvershoot();
        free.gravity = 20.0F;
        free.maxStep = 60.0F;
        free.flickGuard = false;
        float guardedTurn = 0.0F;
        float freeTurn = 0.0F;
        for (long seed = 1; seed <= 20; seed++) {
            float[] a = new AdvancedAim(new Random(seed)).step(0.0F, 0.0F, 170.0F, 0.0F, 1, false, guarded);
            float[] b = new AdvancedAim(new Random(seed)).step(0.0F, 0.0F, 170.0F, 0.0F, 1, false, free);
            guardedTurn += Math.abs(a[0]);
            freeTurn += Math.abs(b[0]);
        }
        assertTrue(freeTurn > guardedTurn);
    }

    @Test
    public void onTheTargetInsideTheDeadZoneTheHandHolds() {
        AdvancedAim.Settings s = noOvershoot();
        s.holdTicks = 2;
        AdvancedAim aim = new AdvancedAim(new Random(3));
        aim.step(0.0F, 0.0F, 30.0F, 0.0F, 1, false, s);
        float[] first = aim.step(29.5F, 0.2F, 30.0F, 0.0F, 1, true, s);
        assertArrayEquals(new float[]{29.5F, 0.2F}, first, 0.0F);
        float[] second = aim.step(29.5F, 0.2F, 30.0F, 0.0F, 1, true, s);
        assertArrayEquals("held for its hold ticks", new float[]{29.5F, 0.2F}, second, 0.0F);
    }

    @Test
    public void offTheTargetItDoesNotHold() {
        AdvancedAim aim = new AdvancedAim(new Random(3));
        AdvancedAim.Settings s = noOvershoot();
        aim.step(0.0F, 0.0F, 30.0F, 0.0F, 1, false, s);
        float[] next = aim.step(29.5F, 0.2F, 30.0F, 0.0F, 1, false, s);
        assertTrue("it keeps closing in", angle(next[0], next[1], 30.0F, 0.0F) < angle(29.5F, 0.2F, 30.0F, 0.0F));
    }

    @Test
    public void aFarNewTargetIsOvershotAndComeBackFrom() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.overshootChance = 100.0F;
        int overshot = 0;
        for (long seed = 1; seed <= 20; seed++) {
            AdvancedAim aim = new AdvancedAim(new Random(seed));
            float yaw = 0.0F;
            float pitch = 0.0F;
            float furthest = 0.0F;
            for (int tick = 0; tick < 60; tick++) {
                float[] next = aim.step(yaw, pitch, 40.0F, 0.0F, 1, false, s);
                yaw = next[0];
                pitch = next[1];
                furthest = Math.max(furthest, yaw);
            }
            if (furthest > 40.5F) {
                overshot++;
            }
            assertEquals("seed " + seed + " came back", 40.0F, yaw, 1.5F);
        }
        assertTrue("overshot in " + overshot + " of 20", overshot >= 10);
    }

    @Test
    public void aNearNewTargetIsNeverOvershot() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.overshootChance = 100.0F;
        for (long seed = 1; seed <= 20; seed++) {
            AdvancedAim aim = new AdvancedAim(new Random(seed));
            aim.step(0.0F, 0.0F, 5.0F, 0.0F, 1, false, s);
            assertFalse(aim.overshooting());
        }
    }

    @Test
    public void theSameSeedMovesTheSameWay() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        AdvancedAim a = new AdvancedAim(new Random(42));
        AdvancedAim b = new AdvancedAim(new Random(42));
        float ya = 0.0F, pa = 0.0F, yb = 0.0F, pb = 0.0F;
        for (int tick = 0; tick < 30; tick++) {
            float[] na = a.step(ya, pa, 50.0F, -5.0F, 9, false, s);
            float[] nb = b.step(yb, pb, 50.0F, -5.0F, 9, false, s);
            ya = na[0]; pa = na[1]; yb = nb[0]; pb = nb[1];
        }
        assertEquals(ya, yb, 0.0F);
        assertEquals(pa, pb, 0.0F);
    }

    @Test
    public void pitchStaysInRange() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.gaussian = 0.6F;
        AdvancedAim aim = new AdvancedAim(new Random(5));
        float yaw = 0.0F, pitch = 85.0F;
        for (int tick = 0; tick < 40; tick++) {
            float[] next = aim.step(yaw, pitch, 20.0F, 89.9F, 1, false, s);
            yaw = next[0];
            pitch = next[1];
            assertTrue(pitch <= 89.9F && pitch >= -89.9F);
        }
    }

    // ------------------------------------------------------------ the finger

    @Test
    public void theFirstHitWaitsAReaction() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.triggerReaction = 95.0F;
        s.triggerReactionJitter = 0.0F;
        AdvancedAim aim = new AdvancedAim(new Random(1));
        assertFalse("just landed", aim.triggerReady(4, true, 1000L, s));
        assertFalse(aim.triggerReady(4, true, 1094L, s));
        assertTrue(aim.triggerReady(4, true, 1095L, s));
        assertTrue("stays ready while on it", aim.triggerReady(4, true, 1500L, s));
    }

    @Test
    public void lookingAwayOrAnotherTargetWaitsAgain() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.triggerReaction = 95.0F;
        s.triggerReactionJitter = 0.0F;
        AdvancedAim aim = new AdvancedAim(new Random(1));
        aim.triggerReady(4, true, 1000L, s);
        assertTrue(aim.triggerReady(4, true, 1100L, s));
        assertFalse(aim.triggerReady(4, false, 1150L, s));
        assertFalse("re-armed", aim.triggerReady(4, true, 1200L, s));
        assertTrue(aim.triggerReady(4, true, 1295L, s));
        assertFalse("a new target", aim.triggerReady(5, true, 1300L, s));
        aim.lostTarget();
        assertFalse(aim.triggerReady(5, true, 1400L, s));
    }

    @Test
    public void noReactionMeansAtOnce() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.triggerReaction = 0.0F;
        s.triggerReactionJitter = 0.0F;
        assertTrue(new AdvancedAim(new Random(1)).triggerReady(4, true, 1000L, s));
    }

    // ------------------------------------------------------------ the eye

    private static final AxisAlignedBB BOX = new AxisAlignedBB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);

    @Test
    public void aStillTargetIsAimedHighOnItsBody() {
        AdvancedAim aim = new AdvancedAim(new Random(1));
        Vec3 point = aim.predictedPoint(0.0, 0.0, 0.0, 1.8, BOX, new Vec3(0.0, 1.62, -3.0), new AdvancedAim.Settings());
        assertEquals(0.0, point.xCoord, 1.0E-9);
        assertEquals(1.8 * 0.82, point.yCoord, 1.0E-9);
        assertEquals(0.0, point.zCoord, 1.0E-9);
    }

    @Test
    public void aMovingTargetIsLedButKeptNearItsBox() {
        AdvancedAim aim = new AdvancedAim(new Random(1));
        for (int i = 0; i < 20; i++) {
            aim.observeMotion(new Vec3(0.25, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0));
        }
        Vec3 point = aim.predictedPoint(0.0, 0.0, 0.0, 1.8, BOX, new Vec3(0.0, 1.62, -3.0), new AdvancedAim.Settings());
        assertTrue("led the way it moves: " + point.xCoord, point.xCoord > 0.2);
        assertTrue("no further than just off the box", point.xCoord <= 0.3 + 0.18 + 1.0E-9);
    }

    @Test
    public void theEyeWaitsAReactionForADrift() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.aimReaction = 200.0F;
        s.aimReactionJitter = 0.0F;
        AdvancedAim aim = new AdvancedAim(new Random(1));
        Vec3 start = new Vec3(0.0, 1.4, 0.0);
        assertSame(start, aim.aimPoint(start, BOX, 3.0, 1000L, s));
        Vec3 drift = new Vec3(0.1, 1.4, 0.0);
        assertEquals("not yet", 0.0, aim.aimPoint(drift, BOX, 3.0, 1100L, s).xCoord, 1.0E-9);
        Vec3 moved = aim.aimPoint(drift, BOX, 3.0, 1200L, s);
        assertTrue("part of the way", moved.xCoord > 0.0 && moved.xCoord < 0.1);
    }

    @Test
    public void aJumpIsFollowedAtOnceButOnlyPartly() {
        AdvancedAim.Settings s = new AdvancedAim.Settings();
        s.aimReaction = 200.0F;
        s.aimReactionJitter = 0.0F;
        AdvancedAim aim = new AdvancedAim(new Random(1));
        AxisAlignedBB wide = new AxisAlignedBB(-2.0, 0.0, -2.0, 2.0, 1.8, 2.0);
        aim.aimPoint(new Vec3(-1.0, 1.4, 0.0), wide, 3.0, 1000L, s);
        Vec3 moved = aim.aimPoint(new Vec3(1.0, 1.4, 0.0), wide, 3.0, 1010L, s);
        assertTrue("moved before the reaction: " + moved.xCoord, moved.xCoord > -1.0);
        assertTrue("at most 70% of the way", moved.xCoord <= -1.0 + 2.0 * 0.7 + 1.0E-9);
    }

    @Test
    public void candidatesStartWithTheAimPointAndCoverTheBox() {
        Vec3 aimPoint = new Vec3(0.1, 1.2, 0.0);
        java.util.List<Vec3> points = AdvancedAim.candidates(BOX, aimPoint, new Vec3(0.0, 1.62, -3.0));
        assertSame(aimPoint, points.get(0));
        assertEquals(7 * 5 * 4 + 5 * 5 * 2 + 2, points.size());
        for (Vec3 p : points.subList(1, points.size())) {
            assertTrue(p.xCoord >= BOX.minX && p.xCoord <= BOX.maxX);
            assertTrue(p.yCoord >= BOX.minY && p.yCoord <= BOX.maxY);
        }
    }

    @Test
    public void theNearestPointIsJustInsideTheBox() {
        Vec3 near = AdvancedAim.nearestInside(BOX, new Vec3(0.0, 1.62, -3.0));
        assertEquals(-0.27, near.zCoord, 1.0E-9);
        assertEquals(1.62, near.yCoord, 1.0E-9);
        Vec3 above = AdvancedAim.nearestInside(BOX, new Vec3(0.0, 5.0, -3.0));
        assertEquals(1.77, above.yCoord, 1.0E-9);
    }

    // ------------------------------------------------------------ helpers

    @Test
    public void drawsAreClamped() {
        AdvancedAim aim = new AdvancedAim(new Random(9));
        for (int i = 0; i < 2000; i++) {
            float g = aim.gaussian(5.0F, 2.0F);
            assertTrue(g >= -2.0F && g <= 2.0F);
            long d = aim.jittered(180.0, 400.0, 20L, 700L);
            assertTrue(d >= 20L && d <= 700L);
        }
        assertEquals(0.0F, aim.gaussian(0.0F, 2.0F), 0.0F);
    }
}
