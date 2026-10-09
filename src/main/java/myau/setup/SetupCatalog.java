package myau.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The list of things the setup screen can fetch. Ported from OpenSkid
 * (GPL-3.0) and kept in one place so the catalog is easy to edit.
 *
 * <p>Every entry here was checked to resolve to a real jar or zip. The
 * CurseForge API link OpenSkid used for Controlling now answers 404, so it
 * points at the ForgeCDN file that the API redirect used to hand out.
 */
public final class SetupCatalog {
    private SetupCatalog() {
    }

    public static List<SetupEntry> mods() {
        List<SetupEntry> out = new ArrayList<>();
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "OptiFine", "Performance plus zoom. Installs silently.",
                "preview_OptiFine_1.8.9_HD_U_M6_pre2.jar",
                "https://raw.githubusercontent.com/loloshelly102-a11y/OpenSkid/main/configs/preview_OptiFine_1.8.9_HD_U_M6_pre2.jar",
                0, true, true));
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "3DSkinLayers", "3D skin layers on players.",
                "3dSkinLayers-forge-mc1.8.9-1.2.0.jar",
                "https://cdn.modrinth.com/data/zV5r3pPn/versions/1.2.0-forge-1.8.9/3dSkinLayers-forge-mc1.8.9-1.2.0.jar",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "Controlling", "Searchable keybind menu.",
                "Controlling-7.0.0.1.jar",
                "https://mediafilez.forgecdn.net/files/3810/294/Controlling-7.0.0.1.jar",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "CreamyKeys", "Keystrokes overlay.",
                "CreamyKeys-0.0.5-1.8.9.jar",
                "https://cdn.modrinth.com/data/vKOxyOq8/versions/pdFnz68w/CreamyKeys-0.0.5-1.8.9.jar",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "ModernF3", "Cleaner F3 debug screen.",
                "ModernF3-1.8.9-forge-1.0.0.jar",
                "https://cdn.modrinth.com/data/nc1d7LP3/versions/JGiY0f7H/ModernF3-1.8.9-forge-1.0.0.jar",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.MODS, SetupEntry.Source.DIRECT,
                "WaveyCapes", "Animated cape physics.",
                "waveycapes-forge-mc1.8.9-1.2.0.jar",
                "https://cdn.modrinth.com/data/kYuIpRLv/versions/1.2.0-forge-1.8/waveycapes-forge-mc1.8.9-1.2.0.jar",
                0, false));
        return Collections.unmodifiableList(out);
    }

    public static List<SetupEntry> packs() {
        List<SetupEntry> out = new ArrayList<>();
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "2sa 250k [128x]", "PvP texture pack.",
                "!            §b 2sa §e250k [128x].zip",
                "https://www.mediafire.com/file/v0vs9i7y5eiwgib/!++++++++++++%C2%A7b+2sa+%C2%A7e250k+",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Fosyx Sound Pack", "PvP sound pack.",
                "!        Fosyx Sound Pack.zip",
                "https://www.mediafire.com/file/fjbu486iuaaczcs/%2521_Fosyx_Sound_Pack.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Urge Revamp", "PvP texture pack.",
                "!    \u00A74Urge Revamp.zip",
                "https://www.mediafire.com/file/l0lm240xobgx18e/!++++%C2%A74Urge+Revamp.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Wood Sound Overlay", "Hit sound overlay.",
                "§4Wood §fSound §4Overlay.zip",
                "https://www.mediafire.com/file/i9u7i4rkll8xnac/%C2%A74Wood+%C2%A7fSound+%C2%A74Overlay.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Axe Sound Overlay", "Hit sound overlay.",
                "\u00A77Axe \u00A7eSound \u00A77Overlay.zip",
                "https://www.mediafire.com/file/u6vhi74i2zr8vro/%C2%A77Axe+%C2%A7eSound+%C2%A77Overlay.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Meow Sound Overlay", "Hit sound overlay.",
                "\u00A7bMeow \u00A7fSound \u00A7bOverlay.zip",
                "https://www.mediafire.com/file/8pl6uc50gq4zlvz/%C2%A7bMeow+%C2%A7fSound+%C2%A7bOverlay.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Laser Sound Overlay", "Hit sound overlay.",
                "\u00A7cLaser \u00A7aSound \u00A7cOverlay.zip",
                "https://www.mediafire.com/file/msfsktym46xzbqg/%C2%A7cLaser+%C2%A7aSound+%C2%A7cOverlay.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Glass Sound Overlay", "Hit sound overlay.",
                "\u00A7fGlass \u00A7bSound \u00A7fOverlay.zip",
                "https://www.mediafire.com/file/935puti6h77hyyc/%C2%A7fGlass+%C2%A7bSound+%C2%A7fOverlay.zip/file",
                0, false));
        out.add(new SetupEntry(SetupEntry.Section.PACKS, SetupEntry.Source.MEDIAFIRE_SHARE,
                "Stewound", "Sound pack.",
                "Stewound.zip",
                "https://www.mediafire.com/file/4yokus1l2j2hiox/Stewound.zip/file",
                0, false));
        return Collections.unmodifiableList(out);
    }

    public static List<SetupEntry> all() {
        List<SetupEntry> out = new ArrayList<>();
        out.addAll(mods());
        out.addAll(packs());
        return Collections.unmodifiableList(out);
    }

    /** The entries installed without asking, the first time the client runs. */
    public static List<SetupEntry> autoEntries() {
        List<SetupEntry> out = new ArrayList<>();
        for (SetupEntry entry : all()) {
            if (entry.auto) {
                out.add(entry);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Everything in one section, catalog order preserved. */
    public static List<SetupEntry> bySection(SetupEntry.Section section) {
        List<SetupEntry> out = new ArrayList<>();
        for (SetupEntry entry : all()) {
            if (entry.section == section) {
                out.add(entry);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
