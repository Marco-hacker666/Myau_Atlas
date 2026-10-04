package myau.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** The vanilla damage floor and the mitigation check built on it (2026-10-04). */
public class MitigationTest {

    private static final long WINDOW = 500L;

    @Test
    public void vanillaDamageMatchesTheGame() {
        /* Diamond sword (7), no armour: 8. */
        assertEquals(8.0F, DamageModel.expected(7.0F, 0, 0, 0, false, 0, 0)[0], 1.0E-4F);
        /* Iron sword, Sharpness I, crit: (1+6)*1.5+1.25 = 11.75. */
        assertEquals(11.75F, DamageModel.expected(6.0F, 1, 0, 0, true, 0, 0)[0], 1.0E-4F);
        /* Full iron armour (15 points): 8 * 10/25 = 3.2. */
        assertEquals(3.2F, DamageModel.expected(7.0F, 0, 0, 0, false, 15, 0)[0], 1.0E-4F);
        /* Strength I: (1+7) * 2.3 = 18.4. */
        assertEquals(18.4F, DamageModel.expected(7.0F, 0, 1, 0, false, 0, 0)[0], 1.0E-3F);
    }

    @Test
    public void protectionIsARange() {
        /* Protection II: floor((6+4)/3*0.75) = 2 a piece, 8 for four. */
        assertEquals(2, DamageModel.protectionPoints(2));
        assertEquals(0, DamageModel.protectionPoints(0));
        float[] range = DamageModel.expected(7.0F, 0, 0, 0, false, 0, 8);
        /* raw 8: 4..8 points, 16%..32% off 8. */
        assertEquals(8.0F * 17 / 25.0F, range[0], 1.0E-4F);
        assertEquals(8.0F * 21 / 25.0F, range[1], 1.0E-4F);
    }

    /** One hit on target 1 that should take at least expected and takes dealt. */
    private static DamageWatch.Hit hit(DamageWatch watch, long[] now, float[] health, float expected, float dealt) {
        DamageWatch.Hit result = null;
        now[0] += 50L;
        watch.tick(1, health[0], 0, now[0], WINDOW);
        watch.attack(1, now[0], expected);
        now[0] += 50L;
        watch.tick(1, health[0], 10, now[0], WINDOW);
        health[0] -= dealt;
        for (int t = 9; t >= 0; t--) {
            now[0] += 50L;
            DamageWatch.Hit h = watch.tick(1, health[0], t, now[0], WINDOW);
            if (h != null) {
                result = h;
            }
        }
        health[0] = 20.0F;
        return result;
    }

    @Test
    public void hitsFarUnderVanillaAreMitigation() {
        DamageWatch watch = new DamageWatch();
        long[] now = {10_000L};
        float[] health = {20.0F};
        hit(watch, now, health, 6.0F, 6.5F);
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        hit(watch, now, health, 6.0F, 1.5F);
        hit(watch, now, health, 6.0F, 2.0F);
        assertFalse("two low of three", watch.mitigation(now[0], 20_000L).active);
        hit(watch, now, health, 6.0F, 1.0F);
        DamageWatch.Mitigation m = watch.mitigation(now[0], 20_000L);
        assertTrue(m.active);
        assertEquals(3, m.low);
        assertEquals(4, m.of);
        assertEquals(1.5F, m.dealt, 1.0E-4F);
        assertFalse("too old", watch.mitigation(now[0] + 30_000L, 20_000L).active);
    }

    @Test
    public void wholeNumberHealthNeedsAWiderMargin() {
        DamageWatch watch = new DamageWatch();
        watch.setPrecision(1.0F);
        long[] now = {10_000L};
        float[] health = {20.0F};
        /* 2.5 of 6: low with exact health, not with a score that may be off by one. */
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, 6.0F, 2.5F);
        }
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        watch.setPrecision(0.0F);
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, 6.0F, 2.5F);
        }
        assertTrue(watch.mitigation(now[0], 20_000L).active);
    }

    @Test
    public void unknownOrTinyExpectationsAreIgnored() {
        DamageWatch watch = new DamageWatch();
        long[] now = {10_000L};
        float[] health = {20.0F};
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, Float.NaN, 0.5F);
            hit(watch, now, health, 1.0F, 0.1F);
        }
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        assertEquals(0, watch.mitigation(now[0], 20_000L).of);
    }
}
