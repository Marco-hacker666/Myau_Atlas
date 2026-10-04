package myau.module;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Every module Myau registers has exactly one category, and the table names
 * nothing that is not registered (2026-09-28). Seven modules once sat in no
 * menu because the category lists were kept by hand.
 */
public class ModuleCategoriesTest {

    private static Set<String> registered() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/myau/Myau.java")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("moduleManager\\.modules\\.put\\((\\w+)\\.class").matcher(source);
        Set<String> names = new TreeSet<String>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    public void everyRegisteredModuleHasACategory() throws Exception {
        Set<String> categorised = new HashSet<String>();
        for (Class<? extends Module> type : ModuleCategories.all().keySet()) {
            categorised.add(type.getSimpleName());
        }
        Set<String> missing = new TreeSet<String>(registered());
        missing.removeAll(categorised);
        assertTrue("registered but in no category: " + missing, missing.isEmpty());
    }

    @Test
    public void theTableNamesOnlyRegisteredModules() throws Exception {
        Set<String> registered = registered();
        assertTrue(registered.size() > 100);
        Set<String> stray = new TreeSet<String>();
        for (Class<? extends Module> type : ModuleCategories.all().keySet()) {
            if (!registered.contains(type.getSimpleName())) {
                stray.add(type.getSimpleName());
            }
        }
        assertTrue("in the table but never registered: " + stray, stray.isEmpty());
    }

    @Test
    public void theCategoriesAreLiquidBouncesInItsOrder() {
        StringBuilder names = new StringBuilder();
        for (Category category : Category.values()) {
            names.append(category.displayName()).append(' ');
        }
        assertEquals("Combat Player Movement Render World Misc Exploit Client Theme Legit ", names.toString());
        assertEquals(Category.WORLD, ModuleCategories.of(myau.module.modules.Scaffold.class));
        assertEquals(Category.COMBAT, ModuleCategories.of(myau.module.modules.KillAura.class));
        assertEquals(Category.CLIENT, ModuleCategories.of(myau.module.modules.FlagDetector.class));
        assertEquals(Category.EXPLOIT, ModuleCategories.of(myau.module.modules.Disabler.class));
        assertEquals(Category.LEGIT, ModuleCategories.of(myau.module.modules.LegitHUD.class));
        assertEquals(Category.LEGIT, ModuleCategories.of(myau.module.modules.HitParticleEffects.class));
    }
}
