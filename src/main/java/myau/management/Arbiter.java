package myau.management;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has the player right now, for the modules that would otherwise pull
 * the same things different ways without knowing about each other.
 *
 * Every conflict of 2026-09-25 was one module doing something sound on its
 * own while another depended on the opposite: Blink holding the movement
 * while Clutch's placements were judged against it (09:43:57, three blocks
 * refused and a 6.5 block relocate); AntiVoid holding the clicks of a catch
 * over the void (12:27:22, 5.7 block lagback); FakeLag dumping ten positions
 * in front of a placement (12:45:04). Each was patched pair by pair; this is
 * the general form. A module doing something that has to be judged by the
 * server against where the player really is -- catching a fall -- says so
 * here, and the modules that hold packets or change movement check it and
 * stand aside until it is over.
 *
 * Two states:
 *   catching -- a fall is being caught. Nothing may hold outgoing or incoming
 *     packets (what is held is let go), start a blink, or alter movement.
 *   viewHeld -- the catch has the camera (REAL rotation, turning back
 *     afterwards). Looking down with blocks in hand is then the catch's
 *     doing, not the player bridging, so edge helpers that key off that
 *     (SafeWalk, Eagle) stay out of it.
 *
 * Set from the client thread; read from the network thread too, hence
 * volatile.
 */
public final class Arbiter {

    private static volatile String catcher;
    private static volatile String viewHolder;
    /** Modules that stood aside during the current catch, for its trace. */
    private static final Set<String> yielded = ConcurrentHashMap.newKeySet();

    private Arbiter() {
    }

    public static void setCatching(String module, boolean on) {
        if (on) {
            catcher = module;
        } else if (module.equals(catcher)) {
            catcher = null;
        }
    }

    public static boolean catching() {
        return catcher != null;
    }

    public static String catcher() {
        return catcher;
    }

    public static void setViewHeld(String module, boolean on) {
        if (on) {
            viewHolder = module;
        } else if (module.equals(viewHolder)) {
            viewHolder = null;
        }
    }

    public static boolean viewHeld() {
        return viewHolder != null;
    }

    /** A module stood aside for the catch. */
    public static void yielded(String module) {
        if (catcher != null) {
            yielded.add(module);
        }
    }

    /** Who stood aside since this was last asked, oldest knowledge gone. */
    public static List<String> drainYielded() {
        List<String> names = new ArrayList<String>(yielded);
        yielded.removeAll(names);
        return names;
    }
}
