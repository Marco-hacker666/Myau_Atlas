package myau.module;

import myau.property.Property;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Every registered module has a description in ModuleDocs, and keys read as words (2026-10-04). */
public class ModuleDocsTest {

    @Test
    public void everyRegisteredModuleIsDescribed() throws Exception {
        String myau = new String(Files.readAllBytes(Paths.get("src/main/java/myau/Myau.java")), StandardCharsets.UTF_8);
        String docs = new String(Files.readAllBytes(Paths.get("src/main/java/myau/module/ModuleDocs.java")),
                StandardCharsets.UTF_8);
        Matcher registered = Pattern.compile("moduleManager\\.modules\\.put\\((\\w+)\\.class").matcher(myau);
        Set<String> missing = new TreeSet<String>();
        while (registered.find()) {
            if (!docs.contains("d(\"" + registered.group(1) + "\",")) {
                missing.add(registered.group(1));
            }
        }
        assertTrue("no description in ModuleDocs: " + missing, missing.isEmpty());
    }

    @Test
    public void keysReadAsWords() {
        assertEquals("Release every", Property.prettify("release-every"));
        assertEquals("Auto block CPS", Property.prettify("AutoBlockCPS"));
        assertEquals("LB H speed", Property.prettify("LB-HSpeed"));
        assertEquals("Min CPS", Property.prettify("MinCPS"));
        assertEquals("CPS mode", Property.prettify("CPS Mode"));
        assertEquals("Hud x", Property.prettify("hud-x"));
        assertEquals("500", Property.prettify("500"));
    }
}
