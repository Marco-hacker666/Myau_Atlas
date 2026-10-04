package myau.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Appends log lines to files on a thread of its own.
 *
 * The diagnostic logs (clutch-, fakelag-, flags-, hits-, perf-) were each
 * opened, appended to and closed on the client thread, in the middle of a
 * tick, at exactly the moments something was happening -- the perf log
 * measured FakeLag.onTick at up to 3.4 ms against a 5 us average, which is
 * that file open. The caller now only formats its line and queues it; the
 * text and its timestamp are fixed when it is queued, so nothing about the
 * log changes except when the disk sees it.
 *
 * Lines queued at exit are written by a shutdown hook, so closing the game
 * straight after a fall still leaves its trace behind.
 */
public final class AsyncLog {

    private static final class Entry {
        final File file;
        final List<String> lines;

        Entry(File file, List<String> lines) {
            this.file = file;
            this.lines = lines;
        }
    }

    /* Bounded (F-29, 2026-09-28): a disk that stalls must not become memory
       that grows without limit. Past this, entries are dropped and counted. */
    static final int CAPACITY = 20000;
    private static final LinkedBlockingQueue<Entry> QUEUE = new LinkedBlockingQueue<Entry>(CAPACITY);
    private static Thread worker;
    private static boolean exitRegistered;
    private static final java.util.concurrent.atomic.AtomicLong DROPPED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FAILED = new java.util.concurrent.atomic.AtomicLong();

    private AsyncLog() {
    }

    /** Entries dropped because the queue was full. */
    public static long dropped() {
        return DROPPED.get();
    }

    /** Entries that could not be written (a file error). */
    public static long failed() {
        return FAILED.get();
    }

    public static int queued() {
        return QUEUE.size();
    }

    public static void append(File file, String line) {
        append(file, Collections.singletonList(line));
    }

    public static void append(File file, List<String> lines) {
        if (file == null || lines.isEmpty()) {
            return;
        }
        start();
        if (!QUEUE.offer(new Entry(file, new ArrayList<String>(lines)))) {
            DROPPED.incrementAndGet();
        }
    }

    /**
     * Starts the worker -- again, if it has died. It used to be started once:
     * an Error escaping write() ended the thread, worker stayed non-null, and
     * every log was silently lost for the rest of the session (F-29).
     */
    private static synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Entry entry;
                    try {
                        entry = QUEUE.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        write(entry);
                    } catch (Throwable t) {
                        FAILED.incrementAndGet();
                    }
                }
            }
        }, "Myau-AsyncLog");
        worker.setDaemon(true);
        worker.start();
        if (!exitRegistered) {
            exitRegistered = true;
            /* The last stage of the one ordered exit. */
            myau.management.Shutdown.register(myau.management.Shutdown.Stage.FLUSH_LOGS, "AsyncLog",
                    AsyncLog::drain);
        }
    }

    /**
     * Writes out everything queued, in order, and stops the worker.
     *
     * The worker is stopped first and waited for: an entry it had already
     * taken used to be written after the ones drained here, or not at all if
     * the JVM halted first.
     */
    static void drain() {
        Thread running;
        synchronized (AsyncLog.class) {
            running = worker;
            worker = null;
        }
        if (running != null) {
            running.interrupt();
            try {
                running.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Entry entry;
        while ((entry = QUEUE.poll()) != null) {
            try {
                write(entry);
            } catch (Throwable t) {
                FAILED.incrementAndGet();
            }
        }
    }

    /** Synchronized so the exit hook and the worker never interleave lines in one file. */
    private static synchronized void write(Entry entry) {
        PrintWriter writer = null;
        try {
            File dir = entry.file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            writer = new PrintWriter(new FileWriter(entry.file, true));
            for (String line : entry.lines) {
                writer.println(line);
            }
            writer.flush();
        } catch (Exception ignored) {
            // A log that cannot be written is not worth anything else failing for.
            FAILED.incrementAndGet();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
