package myau.setup;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the catalog so a broken entry (missing link, wrong section, a URL that
 * moved) is caught here rather than by a user whose download silently fails.
 */
public class SetupCatalogTest {

    @Test
    public void everyEntryIsComplete() {
        for (SetupEntry entry : SetupCatalog.all()) {
            assertFalse(entry.fileName, entry.fileName == null || entry.fileName.trim().isEmpty());
            assertTrue(entry.fileName, entry.url != null && entry.url.startsWith("http"));
            assertFalse(entry.name, entry.displayName().isEmpty());
        }
    }

    @Test
    public void modsAreJarsAndDirect() {
        List<SetupEntry> mods = SetupCatalog.mods();
        assertFalse(mods.isEmpty());
        for (SetupEntry entry : mods) {
            assertEquals(entry.fileName, SetupEntry.Section.MODS, entry.section);
            assertEquals(entry.fileName, SetupEntry.Source.DIRECT, entry.source);
            assertTrue(entry.fileName, entry.fileName.endsWith(".jar"));
        }
    }

    @Test
    public void packsAreZipsFromSharePages() {
        List<SetupEntry> packs = SetupCatalog.packs();
        assertFalse(packs.isEmpty());
        for (SetupEntry entry : packs) {
            assertEquals(entry.fileName, SetupEntry.Section.PACKS, entry.section);
            assertEquals(entry.fileName, SetupEntry.Source.MEDIAFIRE_SHARE, entry.source);
            assertTrue(entry.fileName, entry.fileName.endsWith(".zip"));
        }
    }

    @Test
    public void allIsModsPlusPacks() {
        assertEquals(SetupCatalog.mods().size() + SetupCatalog.packs().size(), SetupCatalog.all().size());
    }

    @Test
    public void onlyOptiFineInstallsItself() {
        List<SetupEntry> auto = SetupCatalog.autoEntries();
        assertEquals(1, auto.size());
        assertEquals("OptiFine", auto.get(0).displayName());
        assertTrue(auto.get(0).matchPrefix);
    }

    @Test
    public void bySectionKeepsOrder() {
        assertEquals(names(SetupCatalog.mods()), names(SetupCatalog.bySection(SetupEntry.Section.MODS)));
        assertEquals(names(SetupCatalog.packs()), names(SetupCatalog.bySection(SetupEntry.Section.PACKS)));
    }

    private static java.util.List<String> names(List<SetupEntry> entries) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SetupEntry entry : entries) {
            out.add(entry.displayName());
        }
        return out;
    }

    /**
     * The CurseForge API link OpenSkid shipped now returns 404; the entry must
     * stay on a host that still serves the file.
     */
    @Test
    public void controllingPointsAtAWorkingHost() {
        for (SetupEntry entry : SetupCatalog.mods()) {
            if (entry.displayName().equals("Controlling")) {
                assertTrue(entry.url, entry.url.contains("forgecdn"));
                return;
            }
        }
        throw new AssertionError("Controlling entry is missing from the catalog");
    }
}
