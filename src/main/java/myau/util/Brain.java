package myau.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What this client has learned about a server, kept between sessions.
 *
 * Everything the diagnostics produced so far was thrown away when the game
 * closed. That put a ceiling on all of it: a flag rate measured over ten
 * minutes has error bars wider than most of the effects worth finding, and a
 * question like "does this module actually cause corrections here" cannot be
 * answered inside one game at all. It needs hours, and hours mean a file.
 *
 * The model is deliberately small and explicit. For every kind of flag, and for
 * every module, it holds four numbers: how long that module was on, how many
 * flags of that kind arrived while it was, how long it was off, and how many
 * arrived then. That is enough to ask the only question that matters -- is the
 * rate higher with it than without -- and it needs no understanding of what any
 * module does.
 *
 * KINDS ARE NOT A LIST. A flag's kind is derived from what was observable when
 * it arrived and used as a key; a server that starts objecting to something new
 * produces a new key on its own.
 *
 * COMPARISON NEEDS BOTH SIDES. Time with a module off is as valuable as time
 * with it on. Confidence is bounded by whichever side has less evidence.
 *
 * EVIDENCE AGES (plan step 9, 2026-09-28). Every count and every minute loses
 * half its weight every {@link #HALF_LIFE_DAYS} days. A rate is then a weighted
 * average that follows the server as it changes -- an anticheat update, a
 * different route -- instead of being outvoted forever by what was true a
 * month ago, and confidence, which rests on the weighted minutes, falls as the
 * evidence behind it gets old. Nothing is deleted: the lifetime count of each
 * kind is kept undecayed beside it, and so are first and last seen. Three
 * horizons are therefore visible for every kind: lifetime (total), recent
 * (the decayed count) and this session (session).
 *
 * THE FILE (brain-2). Written to a temporary file and moved into place, so a
 * crash mid-write leaves the previous file intact. It names its server, the
 * time its decayed numbers refer to, and ends with an "end" line, so a
 * truncated file is recognised. A file that does not read cleanly is copied to
 * backups/brain/ before anything is written over it; its good lines are still
 * used. A brain-1 file is migrated (its evidence aged from the time it was last
 * written) and the original kept in backups/brain/. Before this, a failed read
 * cleared everything and the next save overwrote the file, and an unrecognised
 * version was "left alone" only until the next save replaced it (F-15, F-16).
 * It stays plain text and hand-editable: deleting a line is still the way to
 * drop a conclusion.
 */
public final class Brain {

    private static final String VERSION = "brain-2";
    private static final String VERSION_1 = "brain-1";

    /** Evidence loses half its weight in this many days (the user's choice, 2026-09-28). */
    public static final double HALF_LIFE_DAYS = 14.0;
    private static final double HALF_LIFE_MS = HALF_LIFE_DAYS * 24.0 * 3600.0 * 1000.0;
    /** Ageing is applied at most this often; within it the error is negligible. */
    private static final long DECAY_EVERY_MS = 60_000L;

    /** Minutes on each side before a comparison is allowed to mean anything. */
    public static final double MIN_MINUTES_PER_SIDE = 4.0;
    /** Beyond this, new combinations are folded into their label. */
    private static final int MAX_KINDS = 120;

    private static volatile File dir = new File("./config/Myau/");
    private static volatile File archive = new File("./backups/brain/");
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /* Flags are recorded from the network thread and exposure from the client
       thread, so every map here is touched by both; all access is under the
       class lock (the synchronized statics). */
    private static String serverKey = "unknown";
    private static final Map<String, Kind> KINDS = new LinkedHashMap<String, Kind>();
    private static final Map<String, Exposure> EXPOSURE = new LinkedHashMap<String, Exposure>();
    private static boolean dirty;
    /** The time the decayed numbers refer to. */
    private static long asOf;
    private static long sessionStartedAt;
    private static double sessionMinutes;
    private static String lastProblem = "";

    private Brain() {
    }

    /** Flags of one kind, split by which modules were on at the time. */
    public static final class Kind {
        public final String signature;
        /** Lifetime count, never decayed: the sample size. */
        public long total;
        /** Decayed count: the recent evidence. */
        public double recent;
        /** Count since the current session began. */
        public int session;
        public long firstSeen;
        public long lastSeen;
        /** {flagsWhileOn, flagsWhileOff} per module, decayed. */
        public final Map<String, double[]> perModule = new LinkedHashMap<String, double[]>();

        Kind(String signature) {
            this.signature = signature;
        }

        double[] slot(String module) {
            double[] slot = this.perModule.get(module);
            if (slot == null) {
                slot = new double[2];
                this.perModule.put(module, slot);
            }
            return slot;
        }
    }

    /** Minutes a module has spent switched on and switched off, decayed. */
    public static final class Exposure {
        public double minutesOn;
        public double minutesOff;
        public long firstSeen;
        public long lastSeen;

        Exposure copy() {
            Exposure copy = new Exposure();
            copy.minutesOn = this.minutesOn;
            copy.minutesOff = this.minutesOff;
            copy.firstSeen = this.firstSeen;
            copy.lastSeen = this.lastSeen;
            return copy;
        }
    }

    // ---- recording ----------------------------------------------------

    public static synchronized void setServer(String key) {
        if (key == null || key.isEmpty()) {
            key = "unknown";
        }
        key = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (!key.equals(serverKey)) {
            save();
            KINDS.clear();
            EXPOSURE.clear();
            dirty = false;
            serverKey = key;
            load();
            beginSession();
        }
    }

    public static synchronized String server() {
        return serverKey;
    }

    /** Starts the "this session" counters over (a new connection). */
    public static synchronized void beginSession() {
        sessionStartedAt = clock.getAsLong();
        sessionMinutes = 0.0;
        for (Kind kind : KINDS.values()) {
            kind.session = 0;
        }
    }

    /** Adds elapsed time to every module's on or off total. */
    public static synchronized void noteExposure(Map<String, Boolean> moduleStates, double minutes) {
        if (minutes <= 0.0) {
            return;
        }
        long now = clock.getAsLong();
        decayTo(now, false);
        for (Map.Entry<String, Boolean> entry : moduleStates.entrySet()) {
            Exposure exposure = EXPOSURE.get(entry.getKey());
            if (exposure == null) {
                exposure = new Exposure();
                exposure.firstSeen = now;
                EXPOSURE.put(entry.getKey(), exposure);
            }
            if (Boolean.TRUE.equals(entry.getValue())) {
                exposure.minutesOn += minutes;
            } else {
                exposure.minutesOff += minutes;
            }
            exposure.lastSeen = now;
        }
        sessionMinutes += minutes;
        dirty = true;
    }

    /**
     * One flag of the given kind, credited against every module by whether it
     * was on. Nothing is attributed to a cause here -- this only records the
     * coincidence, and the arithmetic that turns coincidences into a suspect
     * happens when someone asks.
     */
    public static synchronized void noteFlag(String signature, int count, Map<String, Boolean> moduleStates) {
        long now = clock.getAsLong();
        decayTo(now, false);
        Kind kind = KINDS.get(signature);
        if (kind == null && KINDS.size() >= MAX_KINDS) {
            /* Past the cap new combinations are folded into their label alone,
               which keeps the common kinds sharp. */
            signature = signature.indexOf('|') < 0 ? signature
                    : signature.substring(0, signature.indexOf('|')) + "|other";
            kind = KINDS.get(signature);
        }
        if (kind == null) {
            kind = new Kind(signature);
            kind.firstSeen = now;
            KINDS.put(signature, kind);
        }
        kind.total += count;
        kind.recent += count;
        kind.session += count;
        kind.lastSeen = now;
        for (Map.Entry<String, Boolean> entry : moduleStates.entrySet()) {
            double[] slot = kind.slot(entry.getKey());
            if (Boolean.TRUE.equals(entry.getValue())) {
                slot[0] += count;
            } else {
                slot[1] += count;
            }
        }
        dirty = true;
    }

    /** Ages every decayed number to {@code now}; only every minute unless forced. */
    private static void decayTo(long now, boolean force) {
        if (asOf == 0L) {
            asOf = now;
            return;
        }
        long elapsed = now - asOf;
        if (elapsed <= 0L || (!force && elapsed < DECAY_EVERY_MS)) {
            return;
        }
        double factor = Math.pow(0.5, elapsed / HALF_LIFE_MS);
        for (Exposure exposure : EXPOSURE.values()) {
            exposure.minutesOn *= factor;
            exposure.minutesOff *= factor;
        }
        for (Kind kind : KINDS.values()) {
            kind.recent *= factor;
            for (double[] slot : kind.perModule.values()) {
                slot[0] *= factor;
                slot[1] *= factor;
            }
        }
        asOf = now;
    }

    // ---- asking -------------------------------------------------------

    /** A module's excess flag rate, with how much evidence it rests on. */
    public static final class Suspect {
        public final String module;
        public final String kind;
        public final double rateOn;
        public final double rateOff;
        public final double confidence;
        /** Lifetime flags of this kind. */
        public final long samples;
        /** Decayed flags of this kind. */
        public final double recent;
        /** Flags of this kind this session. */
        public final int session;
        /** Weighted minutes with the module on and off. */
        public final double minutesOn;
        public final double minutesOff;
        /** Flags of this kind while the module was on (weighted). */
        public final double flagsOn;
        /** When a flag of this kind was last seen. */
        public final long lastSeen;

        Suspect(String module, Kind kind, double flagsOn, double rateOn, double rateOff,
                double minutesOn, double minutesOff, double confidence) {
            this.module = module;
            this.kind = kind.signature;
            this.rateOn = rateOn;
            this.rateOff = rateOff;
            this.confidence = confidence;
            this.samples = kind.total;
            this.recent = kind.recent;
            this.session = kind.session;
            this.minutesOn = minutesOn;
            this.minutesOff = minutesOff;
            this.flagsOn = flagsOn;
            this.lastSeen = kind.lastSeen;
        }

        /** Flags per minute this module appears to add. */
        public double excess() {
            return this.rateOn - this.rateOff;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%s +%.2f/min on %s (on %.2f, off %.2f, conf %.0f%%, n=%d recent %.1f session %d)",
                    this.module, excess(), this.kind, this.rateOn, this.rateOff,
                    this.confidence * 100.0, this.samples, this.recent, this.session);
        }
    }

    /**
     * Modules whose presence coincides with more flags than their absence,
     * strongest first.
     *
     * Confidence is the smaller side's (weighted) evidence measured against the
     * minimum, capped at one. It is not a p-value and is not claimed to be: it
     * exists so that a conclusion drawn from almost no time without a module --
     * or from time long past -- is visibly weak rather than silently wrong.
     */
    public static synchronized List<Suspect> suspects(double minExcess) {
        decayTo(clock.getAsLong(), true);
        List<Suspect> found = new ArrayList<Suspect>();
        for (Kind kind : KINDS.values()) {
            for (Map.Entry<String, double[]> entry : kind.perModule.entrySet()) {
                Exposure exposure = EXPOSURE.get(entry.getKey());
                if (exposure == null
                        || exposure.minutesOn < MIN_MINUTES_PER_SIDE
                        || exposure.minutesOff < MIN_MINUTES_PER_SIDE) {
                    continue;
                }
                double rateOn = entry.getValue()[0] / exposure.minutesOn;
                double rateOff = entry.getValue()[1] / exposure.minutesOff;
                if (rateOn - rateOff < minExcess) {
                    continue;
                }
                double weaker = Math.min(exposure.minutesOn, exposure.minutesOff);
                double confidence = Math.min(1.0, weaker / (MIN_MINUTES_PER_SIDE * 4.0));
                found.add(new Suspect(entry.getKey(), kind, entry.getValue()[0], rateOn, rateOff,
                        exposure.minutesOn, exposure.minutesOff, confidence));
            }
        }
        Collections.sort(found, new Comparator<Suspect>() {
            @Override
            public int compare(Suspect a, Suspect b) {
                return Double.compare(b.excess() * b.confidence, a.excess() * a.confidence);
            }
        });
        return found;
    }

    /** The module with the least (weighted) evidence on its thinner side. */
    public static synchronized String leastKnown(List<String> candidates) {
        decayTo(clock.getAsLong(), true);
        String worst = null;
        double worstMinutes = Double.MAX_VALUE;
        for (String name : candidates) {
            Exposure exposure = EXPOSURE.get(name);
            double weaker = exposure == null ? 0.0
                    : Math.min(exposure.minutesOn, exposure.minutesOff);
            if (weaker < worstMinutes) {
                worstMinutes = weaker;
                worst = name;
            }
        }
        return worstMinutes >= MIN_MINUTES_PER_SIDE * 4.0 ? null : worst;
    }

    /** A copy; null if the module has never been seen. */
    public static synchronized Exposure exposure(String module) {
        Exposure exposure = EXPOSURE.get(module);
        return exposure == null ? null : exposure.copy();
    }

    public static synchronized int kindCount() {
        return KINDS.size();
    }

    /** A snapshot of the kinds (the Kind objects are live; read them, do not change them). */
    public static synchronized Map<String, Kind> kinds() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Kind>(KINDS));
    }

    public static synchronized double sessionMinutes() {
        return sessionMinutes;
    }

    public static synchronized long sessionStartedAt() {
        return sessionStartedAt;
    }

    /** What went wrong with the last read or write of the file; "" when nothing did. */
    public static synchronized String lastProblem() {
        return lastProblem;
    }

    // ---- persistence --------------------------------------------------

    private static File file() {
        return new File(dir, "brain-" + serverKey + ".txt");
    }

    /** Written to a temporary file and moved into place: never half a file. */
    public static synchronized void save() {
        if (!dirty || KINDS.isEmpty() && EXPOSURE.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        decayTo(now, true);
        File target = file();
        File temp = new File(dir, target.getName() + ".tmp");
        PrintWriter writer = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                lastProblem = "cannot create " + dir;
                return;
            }
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(temp, false), StandardCharsets.UTF_8));
            writer.println(VERSION);
            writer.println("server\t" + serverKey);
            writer.println("asof\t" + asOf);
            writer.println(String.format(Locale.ROOT, "halflife-days\t%.1f", HALF_LIFE_DAYS));
            for (Map.Entry<String, Exposure> entry : EXPOSURE.entrySet()) {
                Exposure exposure = entry.getValue();
                writer.println(String.format(Locale.ROOT, "E\t%s\t%.4f\t%.4f\t%d\t%d", entry.getKey(),
                        exposure.minutesOn, exposure.minutesOff, exposure.firstSeen, exposure.lastSeen));
            }
            for (Kind kind : KINDS.values()) {
                writer.println(String.format(Locale.ROOT, "K\t%s\t%d\t%.4f\t%d\t%d", kind.signature,
                        kind.total, kind.recent, kind.firstSeen, kind.lastSeen));
                for (Map.Entry<String, double[]> entry : kind.perModule.entrySet()) {
                    writer.println(String.format(Locale.ROOT, "F\t%s\t%s\t%.4f\t%.4f", kind.signature,
                            entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
                }
            }
            writer.println("end");
            writer.flush();
            if (writer.checkError()) {
                throw new java.io.IOException("write failed");
            }
            writer.close();
            writer = null;
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception e) {
            // Losing what was learned is bad; crashing the game over it is worse.
            lastProblem = "save failed: " + e;
        } finally {
            if (writer != null) {
                writer.close();
            }
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    public static synchronized void load() {
        KINDS.clear();
        EXPOSURE.clear();
        dirty = false;
        lastProblem = "";
        long now = clock.getAsLong();
        File source = file();
        if (!source.exists()) {
            asOf = now;
            return;
        }
        List<String> lines;
        try {
            lines = readLines(source);
        } catch (Exception e) {
            keepAside(source, "unreadable");
            lastProblem = "unreadable, kept in backups/brain: " + e;
            asOf = now;
            return;
        }
        String header = lines.isEmpty() ? "" : lines.get(0).trim();
        if (VERSION.equals(header)) {
            readV2(source, lines, now);
        } else if (VERSION_1.equals(header)) {
            /* Migrated, not guessed at: brain-1 had no clock, so its numbers
               are taken as of the time the file was last written, and aged
               from there. The original is kept. */
            keepAside(source, "v1");
            asOf = source.lastModified() > 0L ? source.lastModified() : now;
            int bad = readRecords(lines, 1, asOf, false);
            lastProblem = "migrated from brain-1" + (bad > 0 ? ", " + bad + " unreadable lines" : "");
            dirty = true;
        } else {
            /* Not a version this knows. Kept aside rather than overwritten by
               the next save -- that is what used to happen to it. */
            keepAside(source, "unknown");
            lastProblem = "unknown format '" + header + "', kept in backups/brain";
            asOf = now;
            return;
        }
        decayTo(now, true);
    }

    private static void readV2(File source, List<String> lines, long now) {
        int index = 1;
        long fileAsOf = 0L;
        String fileServer = null;
        for (; index < lines.size(); index++) {
            String[] parts = lines.get(index).split("\t");
            if ("server".equals(parts[0]) && parts.length >= 2) {
                fileServer = parts[1];
            } else if ("asof".equals(parts[0]) && parts.length >= 2) {
                try {
                    fileAsOf = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    // handled below
                }
            } else if (!"halflife-days".equals(parts[0])) {
                break;
            }
        }
        if (fileServer != null && !fileServer.equals(serverKey)) {
            /* Another server's evidence under this server's name (a renamed
               file). Using it would be exactly the contamination this exists
               to prevent. */
            keepAside(source, "foreign");
            lastProblem = "file is for '" + fileServer + "', kept in backups/brain";
            asOf = now;
            return;
        }
        asOf = fileAsOf > 0L ? fileAsOf : source.lastModified();
        boolean ended = !lines.isEmpty() && "end".equals(lines.get(lines.size() - 1).trim());
        int bad = readRecords(lines, index, asOf, true);
        if (bad > 0 || !ended) {
            /* Whatever read cleanly is still evidence -- every line stands on
               its own -- but the file as found is kept before it is replaced. */
            keepAside(source, "corrupt");
            lastProblem = (ended ? "" : "truncated, ") + bad + " unreadable lines; original kept in backups/brain";
            dirty = true;
        }
    }

    /** Reads E/K/F records from {@code from}; returns how many lines could not be read. */
    private static int readRecords(List<String> lines, int from, long stamp, boolean v2) {
        int bad = 0;
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || "end".equals(line.trim())) {
                continue;
            }
            String[] parts = line.split("\t");
            try {
                if ("E".equals(parts[0]) && parts.length >= 4) {
                    Exposure exposure = new Exposure();
                    exposure.minutesOn = Double.parseDouble(parts[2]);
                    exposure.minutesOff = Double.parseDouble(parts[3]);
                    exposure.firstSeen = v2 && parts.length >= 6 ? Long.parseLong(parts[4]) : stamp;
                    exposure.lastSeen = v2 && parts.length >= 6 ? Long.parseLong(parts[5]) : stamp;
                    EXPOSURE.put(parts[1], exposure);
                } else if ("K".equals(parts[0]) && parts.length >= 3) {
                    Kind kind = new Kind(parts[1]);
                    kind.total = Long.parseLong(parts[2]);
                    kind.recent = v2 && parts.length >= 6 ? Double.parseDouble(parts[3]) : kind.total;
                    kind.firstSeen = v2 && parts.length >= 6 ? Long.parseLong(parts[4]) : stamp;
                    kind.lastSeen = v2 && parts.length >= 6 ? Long.parseLong(parts[5]) : stamp;
                    KINDS.put(parts[1], kind);
                } else if ("F".equals(parts[0]) && parts.length >= 5) {
                    Kind kind = KINDS.get(parts[1]);
                    if (kind == null) {
                        bad++;
                        continue;
                    }
                    double[] slot = kind.slot(parts[2]);
                    slot[0] = Double.parseDouble(parts[3]);
                    slot[1] = Double.parseDouble(parts[4]);
                } else {
                    bad++;
                }
            } catch (RuntimeException e) {
                bad++;
            }
        }
        return bad;
    }

    private static List<String> readLines(File source) throws java.io.IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    /** Copies a file into backups/brain/ under a name saying why; never deletes. */
    private static void keepAside(File source, String reason) {
        try {
            File into = archive;
            if (!into.exists() && !into.mkdirs()) {
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(clock.getAsLong()));
            File copy = new File(into, source.getName() + "." + reason + "-" + stamp);
            Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // Nothing more can be done; the original is still in place until the next save.
        }
    }

    public static synchronized void forget() {
        KINDS.clear();
        EXPOSURE.clear();
        dirty = true;
        save();
    }

    // ---- test support --------------------------------------------------

    /** Test support only: where files go, and what time it is. */
    static synchronized void useForTests(File data, File archiveDir, LongSupplier testClock) {
        dir = data;
        archive = archiveDir;
        clock = testClock;
        serverKey = "unknown";
        KINDS.clear();
        EXPOSURE.clear();
        dirty = false;
        asOf = 0L;
        sessionStartedAt = 0L;
        sessionMinutes = 0.0;
        lastProblem = "";
    }
}
