package myau.setup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SetupEntryTest {

    @Test
    public void stripColorsRemovesSectionSignCodes() {
        assertEquals("Wood Sound Overlay", SetupEntry.stripColors("\u00A74Wood \u00A7fSound \u00A74Overlay"));
    }

    @Test
    public void stripColorsHandlesNullAndPlainText() {
        assertEquals("", SetupEntry.stripColors(null));
        assertEquals("OptiFine", SetupEntry.stripColors("OptiFine"));
    }

    @Test
    public void displayNameIsStrippedAndTrimmed() {
        SetupEntry entry = new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "\u00A7b 2sa \u00A7e250k ", "PvP texture pack.",
                "pack.zip", "https://example.com/pack.zip", 0, false);
        assertEquals("2sa 250k", entry.displayName());
    }

    @Test
    public void formatBytesIsHumanReadable() {
        assertEquals("512 B", SetupEntry.formatBytes(512));
        assertEquals("2 KB", SetupEntry.formatBytes(2048));
        assertEquals("1.5 MB", SetupEntry.formatBytes((long) (1.5 * 1024 * 1024)));
        assertEquals("?", SetupEntry.formatBytes(-1));
    }

    @Test
    public void defaultConstructorLeavesPrefixMatchingOff() {
        SetupEntry entry = new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "Controlling", "Searchable keybind menu.",
                "Controlling-7.0.0.1.jar", "https://example.com/c.jar", 0, false);
        assertEquals(false, entry.matchPrefix);
        assertEquals(false, entry.auto);
        assertEquals(0L, entry.expectedBytes);
    }
}
