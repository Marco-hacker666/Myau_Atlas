package myau.ui.impl.clickgui.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Lets the menu be checked without anyone having to look at it.
 *
 * Every visual defect in this menu so far -- a white wash over the blur, a
 * corner radius silently clamped to four pixels, a triangle fan wound the wrong
 * way, a panel culled entirely, slider labels printed through the row below --
 * was found the same way: someone played the game, took a screenshot, and said
 * it looked wrong. That is a slow loop and a rude one, and none of those five
 * needed a human eye to detect. Three were arithmetic and two were a blank
 * region where something should have been drawn.
 *
 * So the menu reports on itself, in two ways that need no one watching:
 *
 * IT KEEPS ITS OWN RECEIPTS. Every rectangle drawn in a frame is recorded with
 * the purpose it was drawn for. Afterwards the record is checked for the things
 * that are wrong by construction rather than by taste -- two rows of the same
 * list overlapping, a click target that does not match the rectangle it was
 * drawn as, a surface with no area. Those are the bugs that shipped, and each
 * of them is a comparison of two numbers.
 *
 * IT CAN TAKE ITS OWN PICTURE. A key writes the framebuffer to a file, so
 * whoever is working on the menu can look at the result directly instead of
 * asking for a screenshot and waiting.
 *
 * Both are off unless switched on. Recording every rectangle costs allocation
 * on every frame, which is not a thing to leave running in a client that is
 * also trying to land hits.
 */
final class AtlasInspector {

    private static final File DIR = new File("./config/Myau/ui/");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private AtlasInspector() {
    }

    private static boolean recording;
    private static final List<Rect> DRAWN = new ArrayList<Rect>();
    private static final List<String> FINDINGS = new ArrayList<String>();

    /** One rectangle, and what it was for. */
    private static final class Rect {
        final String purpose;
        final String group;
        final float x;
        final float y;
        final float x2;
        final float y2;

        Rect(String group, String purpose, float x, float y, float x2, float y2) {
            this.group = group;
            this.purpose = purpose;
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean overlaps(Rect other) {
            return this.x < other.x2 && other.x < this.x2
                    && this.y < other.y2 && other.y < this.y2;
        }

        @Override
        public String toString() {
            return String.format("%s/%s [%.1f,%.1f %.1f,%.1f]",
                    this.group, this.purpose, this.x, this.y, this.x2, this.y2);
        }
    }

    /* ---- live mode ---------------------------------------------------
       A capture every couple of seconds while the menu is open, so whoever is
       working on it can watch rather than ask. Deliberately not every frame:
       the point is to see what the menu looks like, and thirty near-identical
       pictures a second answers that no better than one every two seconds
       while costing a disk write each time. */

    private static boolean live;
    private static long lastCapture;
    private static String lastFindings = "";

    /** Newest captures kept; older ones are deleted rather than accumulating. */
    private static final int KEEP = 12;
    private static final long INTERVAL_MS = 2000L;

    static boolean isLive() {
        return live;
    }

    static String toggleLive() {
        live = !live;
        lastCapture = 0L;
        lastFindings = "";
        if (live) {
            recording = true;
            return "live inspect ON -- a frame every 2s into config/Myau/ui/";
        }
        recording = false;
        return "live inspect OFF";
    }

    /**
     * Called at the end of every frame while live. Captures on the interval,
     * and audits the same frame that was captured so a picture and its
     * findings describe the same thing.
     */
    static void tick() {
        if (!live) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCapture < INTERVAL_MS) {
            return;
        }
        lastCapture = now;

        List<String> findings = audit();
        String joined = join(findings);
        /* Only written when it changes. A file repeating the same clean line
           every two seconds buries the moment something broke. */
        if (!joined.equals(lastFindings)) {
            lastFindings = joined;
            appendLog(findings);
        }
        capture();
        prune();
        /* audit() cleared nothing, so recording stays on for the next frame. */
    }

    private static String join(List<String> findings) {
        if (findings.isEmpty()) {
            return "clean";
        }
        StringBuilder sb = new StringBuilder();
        for (String finding : findings) {
            sb.append(finding).append((char) 10);
        }
        return sb.toString();
    }

    private static void appendLog(List<String> findings) {
        PrintWriter writer = null;
        try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                return;
            }
            writer = new PrintWriter(new FileWriter(new File(DIR, "layout.txt"), true));
            writer.println("==== " + new Date() + " ==== " + DRAWN.size() + " rects");
            if (findings.isEmpty()) {
                writer.println("clean");
            } else {
                for (String finding : findings) {
                    writer.println("  " + finding);
                }
            }
            writer.println();
            writer.flush();
        } catch (Exception ignored) {
            // Nothing useful to do; the menu keeps drawing.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /** Keeps the newest few captures and removes the rest. */
    private static void prune() {
        try {
            File[] files = DIR.listFiles(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith("atlas-") && name.endsWith(".png");
                }
            });
            if (files == null || files.length <= KEEP) {
                return;
            }
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = KEEP; i < files.length; i++) {
                if (!files[i].delete()) {
                    files[i].deleteOnExit();
                }
            }
        } catch (Exception ignored) {
            // Disk housekeeping is not worth failing a frame over.
        }
    }

    static boolean isRecording() {
        return recording;
    }

    static void beginFrame() {
        if (recording) {
            DRAWN.clear();
        }
    }

    /**
     * Records a rectangle the menu just drew.
     *
     * Rows in the same group are expected never to overlap; that is the whole
     * of the check, and it is the one that would have caught slider rows
     * reserving twenty pixels while drawing twenty-four.
     */
    static void note(String group, String purpose, float x, float y, float x2, float y2) {
        if (recording) {
            DRAWN.add(new Rect(group, purpose, x, y, x2, y2));
        }
    }

    /**
     * Records where a click for something was tested, so it can be compared
     * against where that thing was drawn.
     *
     * The two drifted apart twice: once when the list started scrolling by
     * pixels while hit testing still assumed whole rows, and once when the
     * detail pane gained a settling offset the click path did not know about.
     * Both were invisible in a screenshot and obvious in two numbers.
     */
    static void noteHit(String group, String purpose, float x, float y, float x2, float y2) {
        note(group, "hit:" + purpose, x, y, x2, y2);
    }

    /** Checks the frame just recorded and returns what is wrong with it. */
    static List<String> audit() {
        FINDINGS.clear();
        for (int i = 0; i < DRAWN.size(); i++) {
            Rect a = DRAWN.get(i);
            if (a.x2 - a.x < 0.5F || a.y2 - a.y < 0.5F) {
                FINDINGS.add("degenerate: " + a);
                continue;
            }
            if (a.purpose.startsWith("hit:")) {
                Rect drawn = find(a.group, a.purpose.substring(4));
                if (drawn == null) {
                    FINDINGS.add("hit target with nothing drawn for it: " + a);
                } else if (Math.abs(drawn.y - a.y) > 1.0F || Math.abs(drawn.y2 - a.y2) > 1.0F) {
                    FINDINGS.add(String.format(
                            "hit/draw mismatch on %s/%s: drawn y %.1f..%.1f, tested y %.1f..%.1f",
                            a.group, a.purpose.substring(4), drawn.y, drawn.y2, a.y, a.y2));
                }
                continue;
            }
            for (int j = i + 1; j < DRAWN.size(); j++) {
                Rect b = DRAWN.get(j);
                if (!a.group.equals(b.group) || b.purpose.startsWith("hit:")) {
                    continue;
                }
                if (a.overlaps(b)) {
                    FINDINGS.add("overlap in " + a.group + ": " + a + "  vs  " + b);
                }
            }
        }
        return FINDINGS;
    }

    private static Rect find(String group, String purpose) {
        for (Rect rect : DRAWN) {
            if (rect.group.equals(group) && rect.purpose.equals(purpose)) {
                return rect;
            }
        }
        return null;
    }

    /**
     * Runs one frame's worth of checking and writes the result.
     *
     * Always writes, even when nothing is wrong: a report saying a frame was
     * clean is evidence, and a missing file is ambiguous between "no problems"
     * and "never ran".
     */
    static String check() {
        recording = true;
        /* The caller redraws once with recording on, then calls report(). */
        return "recording next frame";
    }

    static String report() {
        List<String> findings = audit();
        recording = false;
        PrintWriter writer = null;
        try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                return "could not create " + DIR;
            }
            File target = new File(DIR, "layout.txt");
            writer = new PrintWriter(new FileWriter(target, true));
            writer.println("==== " + new Date() + " ====");
            writer.println(DRAWN.size() + " rectangles checked");
            if (findings.isEmpty()) {
                writer.println("clean");
            } else {
                for (String finding : findings) {
                    writer.println("  " + finding);
                }
            }
            writer.println();
            writer.flush();
            return findings.isEmpty()
                    ? "layout clean (" + DRAWN.size() + " rects)"
                    : findings.size() + " layout problems, see config/Myau/ui/layout.txt";
        } catch (Exception e) {
            return "check failed: " + e;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Writes the current framebuffer to a file.
     *
     * Uses the client's own screenshot path rather than reading pixels here:
     * it already handles the framebuffer being a different size from the
     * window, which is the case whenever the game is not at 100% scaling and
     * is exactly the sort of detail a hand-rolled version gets wrong.
     */
    static String capture() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!DIR.exists() && !DIR.mkdirs()) {
                return "could not create " + DIR;
            }
            String name = "atlas-" + STAMP.format(new Date()) + ".png";
            /* saveScreenshot always writes under <dir>/screenshots/, and does
               not create a subfolder in the name -- so "ui/" + name failed
               with FileNotFoundException and no capture was ever saved. */
            File shots = new File(DIR, "screenshots");
            if (!shots.exists() && !shots.mkdirs()) {
                return "could not create " + shots;
            }
            ScreenShotHelper.saveScreenshot(DIR, name,
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            return "saved config/Myau/ui/screenshots/" + name;
        } catch (Throwable t) {
            return "capture failed: " + t;
        }
    }
}
