package myau.module.modules;

import myau.module.Module;
import myau.property.Property;
import myau.property.PropertyGroup;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Every setting a module registers has a name of its own (2026-09-28).
 *
 * The config is keyed by setting name within the module; two settings with
 * one name save over each other and load into the same slot. This collects
 * them the way Myau's start-up does -- Property fields, then the members of
 * PropertyGroup fields -- for the modules changed this batch.
 */
public class SettingNamesTest {

    private static List<Property<?>> settingsOf(Module module) throws Exception {
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (Field field : module.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(module);
            if (value instanceof Property<?>) {
                out.add((Property<?>) value);
            } else if (value instanceof PropertyGroup) {
                out.addAll(((PropertyGroup) value).properties());
            }
        }
        return out;
    }

    private static void assertUnique(Module module) throws Exception {
        Set<String> seen = new HashSet<String>();
        List<Property<?>> settings = settingsOf(module);
        assertFalse(module.getName() + " has settings", settings.isEmpty());
        for (Property<?> property : settings) {
            assertTrue(module.getName() + ": two settings named " + property.getName(),
                    seen.add(property.getName().toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    public void theModulesChangedTodayHaveUniqueSettingNames() throws Exception {
        assertUnique(new KillAura());
        assertUnique(new AutoClicker());
        assertUnique(new FlagDetector());
        assertUnique(new KeepRange());
        assertUnique(new AimBacktrack());
        assertUnique(new KBDisplacement());
    }

    @Test
    public void killAuraKeepsItsCpsKeysAndAddsTheAdvancedOnes() throws Exception {
        Set<String> names = new HashSet<String>();
        for (Property<?> property : settingsOf(new KillAura())) {
            names.add(property.getName());
        }
        assertTrue(names.contains("MinCPS"));
        assertTrue(names.contains("MaxCPS"));
        assertTrue(names.contains("Adv-Gravity"));
        assertTrue(names.contains("KBDisplace"));
        assertFalse("the range wraps the old keys, it does not add new ones", names.contains("cps-min"));
    }
}
