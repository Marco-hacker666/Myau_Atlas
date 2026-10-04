package myau.property;

import myau.property.properties.IntProperty;
import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** The shared min-max setting (2026-09-28, Rise item 5). */
public class RangeTest {

    @Test
    public void anIntRangeIsTwoOrdinarySettings() {
        IntRange range = new IntRange("cps", 8, 14, 1, 20);
        assertEquals(2, range.properties().size());
        assertEquals("cps-min", range.properties().get(0).getName());
        assertEquals("cps-max", range.properties().get(1).getName());
        assertEquals(8, range.low());
        assertEquals(14, range.high());
        assertEquals("8-14", range.describe());
    }

    @Test
    public void drawsCoverTheWholeRangeAndNothingElse() {
        IntRange range = new IntRange("cps", 8, 10, 1, 20);
        Random random = new Random(1);
        Set<Integer> seen = new HashSet<Integer>();
        for (int i = 0; i < 500; i++) {
            int v = range.random(random);
            assertTrue(range.contains(v));
            seen.add(v);
        }
        assertEquals(3, seen.size());
    }

    @Test
    public void slidersTheWrongWayRoundStillMakeARange() {
        IntRange range = new IntRange("cps", 8, 14, 1, 20);
        range.minProperty().setValue(16);
        assertEquals(14, range.low());
        assertEquals(16, range.high());
        Random random = new Random(2);
        for (int i = 0; i < 200; i++) {
            int v = range.random(random);
            assertTrue(v >= 14 && v <= 16);
        }
    }

    @Test
    public void aSinglePointIsThatPoint() {
        IntRange range = new IntRange("cps", 12, 12, 1, 20);
        assertEquals(12, range.random(new Random(3)));
        assertEquals("12", range.describe());
    }

    @Test
    public void wrappingExistingSettingsRegistersNothingNewAndReadsThemLive() {
        IntProperty min = new IntProperty("MinCPS", 10, 1, 20);
        IntProperty max = new IntProperty("MaxCPS", 14, 1, 20);
        IntRange range = IntRange.of(min, max);
        assertTrue("already fields of the module", range.properties().isEmpty());
        max.setValue(18);
        assertEquals(18, range.high());
    }

    @Test
    public void aFloatRangeDrawsInsideAndInterpolates() {
        FloatRange range = new FloatRange("reach", 2.8F, 3.2F, 0.0F, 6.0F);
        assertEquals("reach-min", range.properties().get(0).getName());
        Random random = new Random(4);
        for (int i = 0; i < 200; i++) {
            float v = range.random(random);
            assertTrue(v >= 2.8F && v <= 3.2F);
        }
        assertEquals(3.0F, range.lerp(0.5F), 1.0E-5F);
        assertEquals(2.8F, range.lerp(-1.0F), 1.0E-5F);
        assertEquals("2.80-3.20", range.describe());
    }

    @Test
    public void aValueOutsideTheLimitsIsRefusedAsBefore() {
        IntRange range = new IntRange("cps", 8, 14, 1, 20);
        assertFalse(range.maxProperty().setValue(25));
        assertEquals(14, range.high());
    }
}
