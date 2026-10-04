package myau.management;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the client does on exit, in one hook and in a fixed order.
 *
 * There used to be three JVM shutdown hooks: the config save, Adaptive's
 * restore-then-Brain-save, and AsyncLog's drain. The JVM starts all hooks at
 * once and in no particular order, so "restore first, then save" -- which
 * Adaptive's comment called not optional -- was never guaranteed: a module
 * switched off for a probe could be written to the config as off. And a
 * value an experiment had only borrowed (an AutoTune trial, a
 * LatencyGovernor cut) had no restore at all, so quitting mid-trial made it
 * the configuration (F-06, F-07, F-26; docs/ARCH-AUDIT-2026-09-28.md).
 *
 * The stages run strictly in order; within a stage, in registration order.
 * One task failing is reported and does not stop the rest.
 */
public final class Shutdown {

    public enum Stage {
        /** Put back anything temporarily changed: probes, trials, governed values. */
        RESTORE,
        /** Write the configuration -- after RESTORE, so it holds the chosen values. */
        SAVE_CONFIG,
        /** Other persistent state (Brain). */
        SAVE_STATE,
        /** Last: write out queued log lines, including any the stages above produced. */
        FLUSH_LOGS
    }

    private static final class Task {
        final String name;
        final Runnable body;

        Task(String name, Runnable body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final Map<Stage, List<Task>> TASKS = new EnumMap<Stage, List<Task>>(Stage.class);
    private static final AtomicBoolean RAN = new AtomicBoolean(false);
    private static boolean installed;

    private Shutdown() {
    }

    public static synchronized void register(Stage stage, String name, Runnable body) {
        List<Task> list = TASKS.get(stage);
        if (list == null) {
            list = new ArrayList<Task>();
            TASKS.put(stage, list);
        }
        list.add(new Task(name, body));
        if (!installed) {
            installed = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Shutdown::run, "Myau-Shutdown"));
        }
    }

    /**
     * Runs every stage once. Safe to call more than once (only the first
     * call does anything), so a normal quit could run it on the client thread
     * and the JVM hook would then find nothing left to do.
     */
    public static void run() {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        for (Stage stage : Stage.values()) {
            List<Task> list;
            synchronized (Shutdown.class) {
                List<Task> registered = TASKS.get(stage);
                list = registered == null ? new ArrayList<Task>() : new ArrayList<Task>(registered);
            }
            for (Task task : list) {
                try {
                    task.body.run();
                } catch (Throwable t) {
                    System.err.println("[Myau] shutdown task " + stage + "/" + task.name + " failed:");
                    t.printStackTrace();
                }
            }
        }
    }

    /** Test support only. */
    static synchronized void resetForTests() {
        TASKS.clear();
        RAN.set(false);
    }
}
