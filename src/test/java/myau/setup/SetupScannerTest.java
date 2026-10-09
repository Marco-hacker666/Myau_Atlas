package myau.setup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The "is this already installed?" rules, without touching the game folder.
 */
public class SetupScannerTest {

    private static SetupEntry mod(String fileName, boolean matchPrefix) {
        return new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "X", "", fileName, "https://example.com/" + fileName, 0, false, matchPrefix);
    }

    @Test
    public void exactNameMatches() {
        assertTrue(SetupScanner.matchesJarName("controlling-7.0.0.1.jar", mod("Controlling-7.0.0.1.jar", false)));
    }

    @Test
    public void browserNumberedDuplicateMatchesEitherBrowserStyle() {
        SetupEntry entry = mod("Controlling-7.0.0.1.jar", false);
        // Chrome inserts the counter before the extension...
        assertTrue(SetupScanner.matchesJarName("Controlling-7.0.0.1 (1).jar", entry));
        // ...some clients append it after.
        assertTrue(SetupScanner.matchesJarName("Controlling-7.0.0.1.jar (23)", entry));
    }

    @Test
    public void numberedDuplicateNeedsTheWholeSuffix() {
        assertFalse(SetupScanner.isNumberedDuplicate("controlling-7.0.0.1.jar (x)", "controlling-7.0.0.1.jar"));
        assertFalse(SetupScanner.isNumberedDuplicate("controlling-7.0.0.1jarbak", "controlling-7.0.0.1.jar"));
    }

    /** Regression: the original compared an upper-case tag against lower-case text. */
    @Test
    public void optiFineCountsAnyHdBuildWhenPrefixMatchingIsOn() {
        SetupEntry optifine = mod("preview_OptiFine_1.8.9_HD_U_M6_pre2.jar", true);
        assertTrue(SetupScanner.matchesJarName("OptiFine_1.8.9_HD_U_M7.jar", optifine));
        assertTrue(SetupScanner.matchesJarName("OptiFine_1.8.9.jar", optifine));
    }

    @Test
    public void prefixMatchingIsOptIn() {
        SetupEntry entry = mod("preview_OptiFine_1.8.9_HD_U_M6_pre2.jar", false);
        assertFalse(SetupScanner.matchesJarName("OptiFine_1.8.9_HD_U_M7.jar", entry));
    }

    @Test
    public void unrelatedJarDoesNotMatch() {
        assertFalse(SetupScanner.matchesJarName("somethingelse.jar", mod("Controlling-7.0.0.1.jar", true)));
    }

    @Test
    public void zipsAreNotMatchedByTheJarRules() {
        SetupEntry pack = new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Stewound", "", "Stewound.zip", "https://example.com/p.zip", 0, false);
        // The exact name still means "installed"...
        assertTrue(SetupScanner.matchesJarName("stewound.zip", pack));
        // ...but a differently named zip is not a numbered duplicate, because
        // those rules only apply to jars.
        assertFalse(SetupScanner.matchesJarName("stewound (1).zip", pack));
        assertFalse(SetupScanner.isPrefixMatch("stewound.zip", "stewound.zip"));
    }

    @Test
    public void packDescriptionIsExtracted() {
        String mcmeta = "{\n  \"pack\": {\n    \"pack_format\": 1,\n    \"description\": \"Stewound\"\n  }\n}";
        assertEquals("Stewound", SetupScanner.extractPackDescription(mcmeta));
    }

    @Test
    public void packDescriptionMissingReturnsNull() {
        assertNull(SetupScanner.extractPackDescription("{\"pack\":{}}"));
        assertNull(SetupScanner.extractPackDescription(null));
    }
}
