package myau.property;

import com.google.gson.JsonObject;
import myau.property.properties.ModeProperty;
import org.junit.Test;

import static org.junit.Assert.*;

/** ModeProperty.hide / alias (2026-10-08): fewer modes in the menus, old configs still load. */
public class ModePropertyTest {

    private static ModeProperty sample() {
        return new ModeProperty("m", 0, new String[]{"A", "B", "C", "D"}).hide("B", "D").alias("OldC", "C");
    }

    @Test
    public void menuListsOnlyVisibleModes() {
        assertEquals("A, C", sample().getValuePrompt());
    }

    @Test
    public void menuRowPicksTheRightMode() {
        ModeProperty m = sample();
        assertTrue(m.setVisible(1));
        assertEquals("C", m.getModeString());
    }

    @Test
    public void cyclingSkipsHiddenModes() {
        ModeProperty m = sample();
        m.nextMode();
        assertEquals("C", m.getModeString());
        m.nextMode();
        assertEquals("A", m.getModeString());
        m.previousMode();
        assertEquals("C", m.getModeString());
    }

    @Test
    public void hiddenModeStillLoadsAndIsListedWhileCurrent() {
        ModeProperty m = sample();
        JsonObject json = new JsonObject();
        json.addProperty("m", "D");
        assertTrue(m.read(json));
        assertEquals("D", m.getModeString());
        assertEquals("A, C, D", m.getValuePrompt());
    }

    @Test
    public void oldNameLoadsAsNewName() {
        ModeProperty m = sample();
        JsonObject json = new JsonObject();
        json.addProperty("m", "OldC");
        assertTrue(m.read(json));
        assertEquals("C", m.getModeString());
        JsonObject out = new JsonObject();
        m.write(out);
        assertEquals("C", out.get("m").getAsString());
    }
}
