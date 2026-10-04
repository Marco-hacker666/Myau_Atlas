package myau.property;

import com.google.gson.JsonObject;
import myau.config.Config;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** Value ownership: base plus overrides, only the base saved (plan step 7, 2026-09-28). */
public class PropertyOverrideTest {

    @After
    public void notLoading() {
        Config.loading = false;
    }

    private static IntProperty delay() {
        return new IntProperty("normal-delay", 100, 50, 1000);
    }

    private static int saved(IntProperty property) {
        JsonObject json = new JsonObject();
        property.write(json);
        return json.get(property.getName()).getAsInt();
    }

    private static float saved(FloatProperty property) {
        JsonObject json = new JsonObject();
        property.write(json);
        return json.get(property.getName()).getAsFloat();
    }

    @Test
    public void withoutOverridesNothingChanges() {
        IntProperty p = delay();
        assertEquals(Integer.valueOf(100), p.getValue());
        assertEquals(Property.Source.DEFAULT, p.getSource());
        assertTrue(p.setValue(180));
        assertEquals(Integer.valueOf(180), p.getValue());
        assertEquals(Integer.valueOf(180), p.getBaseValue());
        assertEquals(Property.Source.USER, p.getSource());
        assertFalse(p.isOverridden());
        assertEquals(180, saved(p));
    }

    @Test
    public void readingAConfigIsAProfileValue() {
        IntProperty p = delay();
        Config.loading = true;
        p.setValue(150);
        Config.loading = false;
        assertEquals(Property.Source.PROFILE, p.getSource());
    }

    @Test
    public void overrideIsEffectiveButNeverSaved() {
        IntProperty p = delay();
        p.setValue(200);
        assertTrue(p.override(Property.Source.TRIAL, "AutoTune", 240));
        assertEquals(Integer.valueOf(240), p.getValue());
        assertEquals(Integer.valueOf(200), p.getBaseValue());
        assertEquals(Property.Source.TRIAL, p.getSource());
        assertEquals("AutoTune", p.getSourceOwner());
        assertEquals("the base is saved, not the trial", 200, saved(p));
        p.release("AutoTune");
        assertEquals(Integer.valueOf(200), p.getValue());
        assertFalse(p.isOverridden());
    }

    @Test
    public void governorBeatsTrialBeatsLearned() {
        IntProperty p = delay();
        p.setValue(200);
        p.override(Property.Source.LEARNED, "Adaptive", 150);
        assertEquals(Integer.valueOf(150), p.getValue());
        p.override(Property.Source.TRIAL, "AutoTune", 230);
        assertEquals(Integer.valueOf(230), p.getValue());
        p.override(Property.Source.GOVERNOR, "LatencyGovernor", 80);
        assertEquals(Integer.valueOf(80), p.getValue());
        assertEquals("LatencyGovernor", p.getSourceOwner());
        /* Releasing the top reveals the next one down, not the base. */
        p.release("LatencyGovernor");
        assertEquals(Integer.valueOf(230), p.getValue());
        p.release("AutoTune");
        assertEquals(Integer.valueOf(150), p.getValue());
        p.release("Adaptive");
        assertEquals(Integer.valueOf(200), p.getValue());
    }

    @Test
    public void releaseOrderDoesNotMatter() {
        IntProperty p = delay();
        p.override(Property.Source.TRIAL, "AutoTune", 230);
        p.override(Property.Source.GOVERNOR, "LatencyGovernor", 80);
        p.release("AutoTune");
        assertEquals("the governor still holds", Integer.valueOf(80), p.getValue());
        p.release("LatencyGovernor");
        assertEquals(Integer.valueOf(100), p.getValue());
    }

    @Test
    public void anOwnerReplacesItsOwnOverride() {
        IntProperty p = delay();
        p.override(Property.Source.TRIAL, "AutoTune", 230);
        p.override(Property.Source.TRIAL, "AutoTune", 260);
        assertEquals(Integer.valueOf(260), p.getValue());
        assertEquals(Integer.valueOf(260), p.overrideOf("AutoTune"));
        p.release("AutoTune");
        assertFalse(p.isOverridden());
    }

    @Test
    public void thePlayerChangingTheValueWhileGovernedChangesTheBase() {
        /* Also what a profile load does mid-trial: the loaded value is the
           base, and stays when the trial ends (F-09). */
        IntProperty p = delay();
        p.setValue(200);
        p.override(Property.Source.GOVERNOR, "LatencyGovernor", 80);
        p.setValue(300);
        assertEquals("still governed", Integer.valueOf(80), p.getValue());
        assertEquals(Integer.valueOf(300), p.getBaseValue());
        assertEquals(300, saved(p));
        p.release("LatencyGovernor");
        assertEquals(Integer.valueOf(300), p.getValue());
    }

    @Test
    public void outOfRangeOverrideIsRefused() {
        IntProperty p = delay();
        assertFalse(p.override(Property.Source.TRIAL, "AutoTune", 20));
        assertFalse(p.isOverridden());
        assertEquals(Integer.valueOf(100), p.getValue());
    }

    @Test
    public void releasingSomethingNeverLaidIsHarmless() {
        IntProperty p = delay();
        p.release("Nobody");
        assertEquals(Integer.valueOf(100), p.getValue());
    }

    @Test
    public void floatsWorkTheSame() {
        FloatProperty range = new FloatProperty("range", 3.1F, 3.0F, 6.0F);
        range.setValue(3.3F);
        range.override(Property.Source.GOVERNOR, "LatencyGovernor", 3.0F);
        assertEquals(3.0F, range.getValue(), 0.0F);
        assertEquals(3.3F, saved(range), 0.0F);
    }

    @Test
    public void describeSaysWhereTheValueCameFrom() {
        IntProperty p = delay();
        p.setValue(200);
        assertTrue(p.describe(), p.describe().startsWith("200 (USER"));
        p.override(Property.Source.TRIAL, "AutoTune", 240);
        String line = p.describe();
        assertTrue(line, line.startsWith("240 (TRIAL by AutoTune"));
        assertTrue(line, line.contains("base 200 USER"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aBaseSourceIsNotAnOverride() {
        delay().override(Property.Source.USER, "Someone", 120);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void propertiesThatCannotSaveTheirBaseRefuseOverrides() {
        new BooleanProperty("flag", true).override(Property.Source.TRIAL, "AutoTune", false);
    }
}
