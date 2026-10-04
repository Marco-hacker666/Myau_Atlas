package myau.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** KeepRange's input arithmetic and the knockback planner's scoring (2026-09-28). */
public class KeepRangeAndKnockbackTest {

    /* Yaw 0 looks along +Z; forward moves +Z, strafe +1 (left) moves +X. */

    @Test
    public void awayFromATargetAheadIsBackwards() {
        /* Target at +Z, so away is -Z. */
        assertArrayEquals(new int[]{-1, 0}, KeepRangeMath.bestAway(0.0F, 0.0, -1.0));
    }

    @Test
    public void awayFromATargetToTheSideIsAStrafe() {
        /* Target at +X (to the left at yaw 0): away is -X, strafe right. */
        assertArrayEquals(new int[]{0, -1}, KeepRangeMath.bestAway(0.0F, -1.0, 0.0));
    }

    @Test
    public void theYawTurnsTheAnswer() {
        /* Yaw 90 looks along -X: target at -X is ahead, away is backwards. */
        assertArrayEquals(new int[]{-1, 0}, KeepRangeMath.bestAway(90.0F, 1.0, 0.0));
    }

    @Test
    public void diagonalsAreChosenWhenTheyFitBest() {
        /* Away is -Z and -X at once: backwards and right. */
        assertArrayEquals(new int[]{-1, -1}, KeepRangeMath.bestAway(0.0F, -1.0, -1.0));
    }

    @Test
    public void stopDropsOnlyThePartThatClosesIn() {
        int[] away = {-1, 0};
        assertArrayEquals(new float[]{0.0F, 1.0F}, KeepRangeMath.apply(false, away, 1.0F, 1.0F), 0.0F);
        assertArrayEquals("already backing off", new float[]{-1.0F, 0.0F},
                KeepRangeMath.apply(false, away, -1.0F, 0.0F), 0.0F);
        assertArrayEquals("sneaking counts too", new float[]{0.0F, 0.0F},
                KeepRangeMath.apply(false, away, 0.3F, 0.0F), 0.0F);
    }

    @Test
    public void backwardsTakesTheAwayInputAtThePlayersPace() {
        int[] away = {-1, -1};
        assertArrayEquals(new float[]{-1.0F, -1.0F}, KeepRangeMath.apply(true, away, 1.0F, 0.0F), 0.0F);
        assertArrayEquals("sneaking stays slow", new float[]{-0.3F, -0.3F},
                KeepRangeMath.apply(true, away, 0.3F, 0.0F), 1.0E-6F);
    }

    @Test
    public void noInputIsLeftAlone() {
        assertArrayEquals(new float[]{0.0F, 0.0F}, KeepRangeMath.apply(true, new int[]{-1, 0}, 0.0F, 0.0F), 0.0F);
    }

    @Test
    public void theComboHasToBeRunningFirst() {
        assertFalse(KeepRangeMath.comboReached(16, 2));
        assertTrue(KeepRangeMath.comboReached(17, 2));
        assertTrue("0: always", KeepRangeMath.comboReached(0, 0));
    }

    // ------------------------------------------------------------ knockback

    @Test
    public void worseAndNearerHazardsScoreHigher() {
        assertTrue(KnockbackPlanner.score("Lava", 1.0, 0.0) > KnockbackPlanner.score("Web", 1.0, 0.0));
        assertTrue(KnockbackPlanner.score("Void", 1.0, 0.0) > KnockbackPlanner.score("Deep Drop", 1.0, 0.0));
        assertTrue(KnockbackPlanner.score("Void", 1.0, 0.0) > KnockbackPlanner.score("Void", 4.0, 0.0));
        assertEquals(145.0 - 7.0 * 2.0, KnockbackPlanner.score("Void", 2.0, 0.0), 1.0E-9);
        assertEquals(88.0 + 10.0 * 3.5 - 6.0, KnockbackPlanner.score("Ditch", 1.0, 25.0), 1.0E-9);
    }

    @Test
    public void shallowWaterFarAwayIsNotWorthIt() {
        assertTrue(KnockbackPlanner.score("Water", 4.9, 0.0) < KnockbackPlanner.MIN_SCORE);
        assertTrue(KnockbackPlanner.score("Water", 0.8, 3.0) >= KnockbackPlanner.MIN_SCORE);
    }

    @Test
    public void theYawPointsAlongTheDirection() {
        assertEquals(0.0F, KnockbackPlanner.yawToward(0.0, 1.0), 1.0E-4F);
        assertEquals(90.0F, Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(
                KnockbackPlanner.yawToward(-1.0, 0.0))), 1.0E-4F);
        /* The planner's own directions: (-sin a, cos a) is yaw a. */
        double a = Math.toRadians(37.0);
        assertEquals(37.0F, net.minecraft.util.MathHelper.wrapAngleTo180_float(
                KnockbackPlanner.yawToward(-Math.sin(a), Math.cos(a))), 1.0E-3F);
    }
}
