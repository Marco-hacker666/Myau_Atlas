package myau.module.modules;

import myau.module.Module;
import myau.property.Property;
import myau.property.PropertyGroup;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * No module has two settings with one name (2026-09-28 full check): the
 * config keys a module's settings by name, so a clash saves one over the
 * other. Every module class that can be built outside the game is checked.
 */
public class AllSettingNamesTest {

    @Test
    public void everyModuleThatBuildsHasUniqueSettingNames() throws Exception {
        File dir = new File("build/classes/java/main/myau/module/modules");
        assertTrue("compiled classes at " + dir.getAbsolutePath(), dir.isDirectory());
        List<String> clashes = new ArrayList<String>();
        int checked = 0;
        File[] files = dir.listFiles();
        assertNotNull(files);
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".class") || name.contains("$")) {
                continue;
            }
            Class<?> type = Class.forName("myau.module.modules." + name.substring(0, name.length() - 6),
                    false, getClass().getClassLoader());
            if (!Module.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Module module;
            try {
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                module = (Module) constructor.newInstance();
            } catch (Throwable cannotBuildHere) {
                continue;
            }
            checked++;
            Map<String, String> seen = new HashMap<String, String>();
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(module);
                List<Property<?>> settings = new ArrayList<Property<?>>();
                if (value instanceof Property<?>) {
                    settings.add((Property<?>) value);
                } else if (value instanceof PropertyGroup) {
                    settings.addAll(((PropertyGroup) value).properties());
                }
                for (Property<?> property : settings) {
                    /* Exact names: config keys are case-sensitive, so "color" and
                       "Color" (TargetHUD) are two keys, not a clash. */
                    String key = property.getName();
                    String previous = seen.put(key, field.getName());
                    if (previous != null) {
                        clashes.add(type.getSimpleName() + "." + property.getName()
                                + " (" + previous + ", " + field.getName() + ")");
                    }
                }
            }
        }
        System.out.println("setting names checked in " + checked + " modules");
        assertTrue("checked " + checked, checked > 50);
        assertTrue("clashes: " + clashes, clashes.isEmpty());
    }
}
