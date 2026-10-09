package myau.setup;

import java.io.File;

/**
 * Whether the first-run setup has been dealt with. A marker file under
 * {@code config/Myau/} keeps the wizard from opening on every launch.
 *
 * <p>Ported from OpenSkid (GPL-3.0); the path follows this client's own config
 * folder.
 */
public final class SetupState {
    private SetupState() {
    }

    public static File markerFile() {
        try {
            return new File(SetupScanner.gameDir(), "config/Myau/setup_done");
        } catch (Exception e) {
            return new File("./config/Myau/setup_done");
        }
    }

    public static boolean isDone() {
        try {
            return markerFile().exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static void markDone() {
        try {
            File marker = markerFile();
            File parent = marker.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            if (!marker.exists()) {
                marker.createNewFile();
            }
        } catch (Exception ignored) {
        }
    }

    public static void reset() {
        try {
            File marker = markerFile();
            if (marker.exists()) {
                marker.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
