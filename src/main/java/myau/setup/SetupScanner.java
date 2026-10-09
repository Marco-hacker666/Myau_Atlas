package myau.setup;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Works out whether a catalog entry is already installed, by looking in
 * {@code mods/} and {@code resourcepacks/}.
 *
 * <p>Ported from OpenSkid (GPL-3.0). The name matching is exposed as pure
 * static helpers ({@link #matchesJarName}, {@link #isPrefixMatch},
 * {@link #isNumberedDuplicate}, {@link #extractPackDescription}) so the tricky
 * cases -- a differently numbered duplicate, an OptiFine build under another
 * {@code _HD_U_} tag, a pack recognised from its {@code pack.mcmeta}
 * description -- are covered by unit tests instead of only by hand.
 */
public final class SetupScanner {
    private SetupScanner() {
    }

    public static File gameDir() {
        try {
            File dir = net.minecraft.client.Minecraft.getMinecraft().mcDataDir;
            if (dir != null) {
                return dir;
            }
        } catch (Exception ignored) {
        }
        return new File(".");
    }

    public static File modsDir() {
        File dir = new File(gameDir(), "mods");
        dir.mkdirs();
        return dir;
    }

    public static File packsDir() {
        File dir = new File(gameDir(), "resourcepacks");
        dir.mkdirs();
        return dir;
    }

    public static File targetDir(SetupEntry entry) {
        return entry.section == SetupEntry.Section.MODS ? modsDir() : packsDir();
    }

    public static boolean isInstalled(SetupEntry entry) {
        try {
            File[] files = targetDir(entry).listFiles();
            if (files == null) {
                return false;
            }
            for (File file : files) {
                if (matchesJarName(file.getName(), entry)) {
                    return true;
                }
            }
            if (entry.section == SetupEntry.Section.PACKS) {
                return matchesPackDescription(files, entry);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * True when {@code candidate} is the entry's own file, a matching
     * differently-numbered build, or (when the entry asks for it) any build of
     * the same mod under a different version tag.
     */
    static boolean matchesJarName(String candidate, SetupEntry entry) {
        if (candidate == null || entry == null || entry.fileName == null) {
            return false;
        }
        String name = candidate.toLowerCase(Locale.ROOT);
        String want = entry.fileName.toLowerCase(Locale.ROOT);
        if (name.equals(want)) {
            return true;
        }
        if (entry.matchPrefix && isPrefixMatch(name, want)) {
            return true;
        }
        return isNumberedDuplicate(name, want);
    }

    /**
     * OptiFine ships a dozen 1.8.9 builds; {@code preview_OptiFine_1.8.9_HD_U_M6_pre2.jar}
     * should count as installed when the user has any {@code OptiFine_1.8.9_HD_U_*.jar}.
     */
    static boolean isPrefixMatch(String name, String want) {
        if (name == null || want == null) {
            return false;
        }
        // Lower-cased here, not by the caller: the tag we look for ("_HD_U_") is
        // upper-case, and the original searched for it in an already lower-cased
        // string, so it never matched a different OptiFine build.
        String plain = name.toLowerCase(Locale.ROOT);
        String core = want.toLowerCase(Locale.ROOT);
        if (!plain.endsWith(".jar")) {
            return false;
        }
        if (core.startsWith("preview_")) {
            core = core.substring("preview_".length());
        }
        int underscore = core.lastIndexOf("_hd_u_");
        String prefix = underscore > 0 ? core.substring(0, underscore) : core;
        if (plain.startsWith("preview_")) {
            plain = plain.substring("preview_".length());
        }
        return plain.startsWith(prefix);
    }

    /**
     * Recognises browser-style duplicates of a jar. Chrome writes
     * {@code Foo-1.0 (1).jar} and some clients write {@code Foo-1.0.jar (1)};
     * the original only understood the first, so it kept offering a mod the
     * user already had under the other name.
     */
    static boolean isNumberedDuplicate(String name, String want) {
        if (name == null || want == null) {
            return false;
        }
        String candidate = name.toLowerCase(Locale.ROOT);
        String target = want.toLowerCase(Locale.ROOT);
        if (!target.endsWith(".jar")) {
            return false;
        }
        String base = target.substring(0, target.length() - 4);
        if (candidate.endsWith(".jar")) {
            String core = candidate.substring(0, candidate.length() - 4);
            if (!core.startsWith(base)) {
                return false;
            }
            if (core.substring(base.length()).trim().matches("\\(\\d+\\)")) {
                return true;
            }
        }
        if (candidate.startsWith(target + " ")) {
            return candidate.substring(target.length()).trim().matches("\\(\\d+\\)");
        }
        return false;
    }

    private static boolean matchesPackDescription(File[] files, SetupEntry entry) {
        String key = SetupEntry.stripColors(entry.name).toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty()) {
            return false;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                continue;
            }
            String description = readPackDescription(file);
            if (description != null && description.toLowerCase(Locale.ROOT).contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String readPackDescription(File zip) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(zip);
            ZipEntry meta = zipFile.getEntry("pack.mcmeta");
            if (meta == null) {
                return null;
            }
            InputStream in = zipFile.getInputStream(meta);
            try {
                byte[] buffer = new byte[4096];
                StringBuilder text = new StringBuilder();
                int read;
                while ((read = in.read(buffer)) != -1 && text.length() < 4096) {
                    text.append(new String(buffer, 0, read, "UTF-8"));
                }
                return extractPackDescription(text.toString());
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Pulls the {@code "description":"..."} value out of a {@code pack.mcmeta} body. */
    static String extractPackDescription(String raw) {
        if (raw == null) {
            return null;
        }
        int desc = raw.indexOf("\"description\"");
        if (desc < 0) {
            return null;
        }
        int colon = raw.indexOf(':', desc);
        int firstQuote = raw.indexOf('"', colon);
        int secondQuote = raw.indexOf('"', firstQuote + 1);
        if (colon < 0 || firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return raw.substring(firstQuote + 1, secondQuote);
    }

    /** The older of an exact copy and a numbered duplicate, for cleanup. */
    public static File findDuplicateJar(SetupEntry entry) {
        try {
            if (entry.fileName == null || !entry.fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            File[] files = targetDir(entry).listFiles();
            if (files == null) {
                return null;
            }
            String want = entry.fileName.toLowerCase(Locale.ROOT);
            File exact = null;
            File duplicate = null;
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.equals(want)) {
                    exact = file;
                } else if (isNumberedDuplicate(name, want)) {
                    duplicate = file;
                }
            }
            if (exact != null && duplicate != null) {
                return duplicate.lastModified() < exact.lastModified() ? duplicate : exact;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
