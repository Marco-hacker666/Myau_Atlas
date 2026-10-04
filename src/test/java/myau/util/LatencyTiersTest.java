package myau.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** LatencyGovernor's tier decision: hysteresis and settling (plan step 8, F-25). */
public class LatencyTiersTest {

    private static final int MID = 150;
    private static final int HIGH = 250;
    private static final int JITTER = 60;
    private static final int MARGIN = 20;
    private static final int SETTLE = 5;

    private static int feed(LatencyTiers tiers, int average, int jitter) {
        return tiers.update(average, jitter, MID, HIGH, JITTER, MARGIN, SETTLE);
    }

    /** Counts tier changes over a series of averages (steady jitter). */
    private static int changes(LatencyTiers tiers, int[] averages) {
        int changes = 0;
        int last = LatencyTiers.UNKNOWN;
        for (int average : averages) {
            int tier = feed(tiers, average, 5);
            if (last != LatencyTiers.UNKNOWN && tier != last) {
                changes++;
            }
            last = tier;
        }
        return changes;
    }

    @Test
    public void firstDecisionIsImmediateButNeverTheWorstTier() {
        assertEquals(0, feed(new LatencyTiers(), 80, 5));
        assertEquals(1, feed(new LatencyTiers(), 180, 5));
        assertEquals(1, feed(new LatencyTiers(), 300, 5));
    }

    @Test
    public void theWorstTierNeedsThreeTimesTheSettling() {
        LatencyTiers tiers = new LatencyTiers();
        feed(tiers, 180, 5);
        for (int i = 1; i < SETTLE * 3; i++) {
            assertEquals("sample " + i, 1, feed(tiers, 300, 5));
        }
        assertEquals(2, feed(tiers, 300, 5));
    }

    @Test
    public void aShortSpikeNeverReachesTheWorstTier() {
        /* 09:36:24 on 2026-09-28: a jitter spike, gone six seconds later. */
        LatencyTiers tiers = new LatencyTiers();
        feed(tiers, 120, 20);
        for (int i = 0; i < 6; i++) {
            assertTrue(feed(tiers, 135, 68) < 2);
        }
        for (int i = 0; i < 30; i++) {
            assertTrue(feed(tiers, 90, 20) < 2);
        }
    }

    @Test
    public void noisyLineAtAThresholdDoesNotFlap() {
        /* The brief's example, around a 85 ms threshold, repeated: a naive
           single threshold changes tier on almost every sample. */
        int[] pattern = {79, 83, 80, 92, 77, 88};
        int[] series = new int[pattern.length * 20];
        for (int i = 0; i < series.length; i++) {
            series[i] = pattern[i % pattern.length];
        }
        LatencyTiers tiers = new LatencyTiers();
        int changes = 0;
        int last = LatencyTiers.UNKNOWN;
        for (int average : series) {
            int tier = tiers.update(average, 5, 85, HIGH, JITTER, MARGIN, SETTLE);
            if (last != LatencyTiers.UNKNOWN && tier != last) {
                changes++;
            }
            last = tier;
        }
        assertTrue("changed " + changes + " times", changes <= 1);
    }

    @Test
    public void theUsersLineUnderTheHighThresholdStaysPut() {
        /* ~225 ms wandering up to the 250 ms line and back. */
        int[] series = {225, 238, 251, 229, 244, 255, 231, 226, 249, 252, 236, 228, 241, 253, 230};
        assertTrue(changes(new LatencyTiers(), series) <= 1);
    }

    @Test
    public void aRealRiseIsTakenAfterSettling() {
        LatencyTiers tiers = new LatencyTiers();
        feed(tiers, 100, 5);
        for (int i = 1; i < SETTLE; i++) {
            assertEquals("still settling at sample " + i, 0, feed(tiers, 200, 5));
        }
        assertEquals(1, feed(tiers, 200, 5));
    }

    @Test
    public void oneOddSampleChangesNothing() {
        LatencyTiers tiers = new LatencyTiers();
        feed(tiers, 100, 5);
        feed(tiers, 400, 5);
        for (int i = 0; i < 10; i++) {
            assertEquals(0, feed(tiers, 100, 5));
        }
    }

    @Test
    public void leavingNeedsTheMargin() {
        LatencyTiers tiers = new LatencyTiers();
        assertEquals(1, feed(tiers, 180, 5));
        /* Just under the threshold, inside the margin: stays. */
        for (int i = 0; i < 20; i++) {
            assertEquals(1, feed(tiers, 140, 5));
        }
        /* Below threshold - margin, long enough: leaves. */
        for (int i = 1; i < SETTLE; i++) {
            assertEquals(1, feed(tiers, 120, 5));
        }
        assertEquals(0, feed(tiers, 120, 5));
    }

    @Test
    public void jitterBumpsOneTierWithItsOwnHysteresis() {
        LatencyTiers tiers = new LatencyTiers();
        assertEquals(1, feed(tiers, 100, 70));
        assertTrue(tiers.jittery());
        /* Between 4/5 of the limit and the limit: still jittery. */
        for (int i = 0; i < 10; i++) {
            assertEquals(1, feed(tiers, 100, 55));
        }
        /* Below 4/5 of the limit: clears, and the tier follows after settling. */
        for (int i = 1; i < SETTLE; i++) {
            feed(tiers, 100, 40);
        }
        assertFalse(tiers.jittery());
        assertEquals(0, feed(tiers, 100, 40));
    }

    @Test
    public void jitterAloneNeverReachesTheWorstTier() {
        /* Pika, 10:01-10:08: a middling average with jitter at the limit. */
        LatencyTiers tiers = new LatencyTiers();
        for (int i = 0; i < 60; i++) {
            assertTrue(feed(tiers, 160, 70) <= 1);
        }
        assertEquals(1, tiers.tier());
    }

    @Test
    public void neverAboveTheTopTier() {
        LatencyTiers tiers = new LatencyTiers();
        int tier = 0;
        for (int i = 0; i < SETTLE * 3 + 1; i++) {
            tier = feed(tiers, 400, 200);
        }
        assertEquals(2, tier);
    }

    @Test
    public void resetForgetsEverything() {
        LatencyTiers tiers = new LatencyTiers();
        feed(tiers, 300, 5);
        tiers.reset();
        assertEquals(LatencyTiers.UNKNOWN, tiers.tier());
        assertEquals(0, feed(tiers, 80, 5));
    }

    @Test
    public void sameInputSameOutput() {
        int[] series = {100, 180, 190, 260, 240, 120, 90, 300, 310, 305};
        LatencyTiers a = new LatencyTiers();
        LatencyTiers b = new LatencyTiers();
        for (int average : series) {
            assertEquals(feed(a, average, 30), feed(b, average, 30));
        }
    }
}
