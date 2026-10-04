package myau.management;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Every packet the client is holding back right now: who holds it, which way,
 * how many, since when, why, and what lets it go (plan step 13,
 * docs/ARCH-AUDIT-2026-09-28.md).
 *
 * Seven things hold packets, each with a queue of its own: LagManager and
 * BlinkManager (shared, outgoing), FakeLag (outgoing), BackTrack,
 * KnockbackDelay and ServerLag (incoming), DelayManager (incoming, ahead of the
 * event bus, for BedNuker). Nothing could answer "what is being held" in one
 * place -- FlagDetector knew about two of them by name. Each holder now
 * registers a Source, and anything that wants the answer asks here.
 *
 * Sources are read from whatever thread asks (the client thread, normally);
 * they read their holder's concurrent queues and volatile fields and must not
 * block. A source that throws is skipped, not fatal.
 *
 * Leases (2026-09-28, after Rise's BlinkComponent, docs/PAID-CLIENT-GAP.md).
 * Every holder lets go through code of its own -- an onDisabled, a branch
 * that ends the hold -- and a holder that never reaches that code keeps its
 * packets for good: its module switched off by a path that skips the
 * release, a handler that throws every tick, a branch that forgets. Nothing
 * noticed. A holder can now register a Lease with its Source, and once a
 * tick (enforce) a hold is ended for it when
 *   - the module it holds for is switched off, or
 *   - it is older than the longest that holder ever holds on purpose.
 * Both limits sit outside anything a holder does when working, so a lease
 * never ends a hold that would have ended anyway: it is a backstop, and every
 * time it acts is counted and written to the log as a fault to look at.
 */
public final class PacketHolds {

    public enum Direction { OUT, IN }

    /** One holder's hold at one moment. */
    public static final class Hold {
        public final String holder;
        public final Direction direction;
        public final int count;
        /** When the oldest held packet was taken; 0 if unknown. */
        public final long since;
        public final String reason;
        public final String release;

        public Hold(String holder, Direction direction, int count, long since, String reason, String release) {
            this.holder = holder;
            this.direction = direction;
            this.count = count;
            this.since = since;
            this.reason = reason;
            this.release = release;
        }

        /** "Blink/AntiVoid out 12 for 850ms (void blink; until AntiVoid lets go)" */
        public String describe(long now) {
            StringBuilder sb = new StringBuilder(this.holder).append(' ')
                    .append(this.direction.name().toLowerCase(Locale.ROOT)).append(' ').append(this.count);
            if (this.since > 0L) {
                sb.append(" for ").append(Math.max(0L, now - this.since)).append("ms");
            }
            sb.append(" (").append(this.reason).append("; ").append(this.release).append(')');
            return sb.toString();
        }
    }

    /** Reports a holder's current hold, or null when it holds nothing. */
    public interface Source {
        Hold current();
    }

    /** When a hold has outlived its reason, and how to end it. */
    public interface Lease {
        /**
         * The module the current hold is for, as its name; the hold ends when
         * that module is off. Null when no module is named (then only the
         * ceiling applies).
         */
        String owner();

        /**
         * The longest this holder holds on purpose right now, in ms, with a
         * margin; a hold older than this is ended. Negative: no ceiling (the
         * Blink module holds for as long as the player keeps it on).
         */
        long ceilingMs();

        /** Lets everything held go, in order. Called on the client thread. */
        void expire();
    }

    /** One lease ended by enforce(), for the log and for tests. */
    public static final class Expiry {
        public final long at;
        public final String holder;
        public final int count;
        public final String why;

        Expiry(long at, String holder, int count, String why) {
            this.at = at;
            this.holder = holder;
            this.count = count;
            this.why = why;
        }

        @Override
        public String toString() {
            return this.holder + " " + this.count + " packets: " + this.why;
        }
    }

    private static final class Entry {
        final Source source;
        final Lease lease;

        Entry(Source source, Lease lease) {
            this.source = source;
            this.lease = lease;
        }
    }

    /**
     * What a ceiling allows beyond the longest hold a holder's settings can
     * ask for: a hold still there a second after it should have ended is not
     * being ended.
     */
    public static final long LEASE_MARGIN_MS = 1000L;

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<Entry>();
    private static volatile int expiries;
    private static volatile int failures;
    private static volatile Expiry lastExpiry;

    private PacketHolds() {
    }

    /** A holder that is only reported, never ended from here. */
    public static void register(Source source) {
        ENTRIES.add(new Entry(source, null));
    }

    /** A holder with a lease: reported, and ended by enforce() once its hold outlives its reason. */
    public static void register(Source source, Lease lease) {
        ENTRIES.add(new Entry(source, lease));
    }

    /** Everything held now, in registration order. */
    public static List<Hold> snapshot() {
        List<Hold> holds = new ArrayList<Hold>();
        for (Entry entry : ENTRIES) {
            Hold hold = read(entry.source);
            if (hold != null) {
                holds.add(hold);
            }
        }
        return holds;
    }

    /** The source's hold, or null when it holds nothing or cannot say. */
    private static Hold read(Source source) {
        try {
            Hold hold = source.current();
            return hold != null && hold.count > 0 ? hold : null;
        } catch (RuntimeException ignored) {
            // A holder that cannot say what it holds is left out, not fatal.
            return null;
        }
    }

    /** Total packets held, both ways. */
    public static int total() {
        int total = 0;
        for (Hold hold : snapshot()) {
            total += hold.count;
        }
        return total;
    }

    /** One line, "" when nothing is held. */
    public static String describe() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Hold hold : snapshot()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(hold.describe(now));
        }
        return sb.toString();
    }

    /**
     * Ends every leased hold that has outlived its reason; returns what it
     * ended. Client thread.
     *
     * @param moduleOn whether the named module is switched on; a name it does
     *                 not know must answer true (unknown is not "off").
     */
    public static List<Expiry> enforce(long now, Predicate<String> moduleOn) {
        List<Expiry> ended = new ArrayList<Expiry>();
        for (Entry entry : ENTRIES) {
            if (entry.lease == null) {
                continue;
            }
            Hold hold = read(entry.source);
            if (hold == null) {
                continue;
            }
            String why = null;
            try {
                String owner = entry.lease.owner();
                long ceiling = entry.lease.ceilingMs();
                if (owner != null && !moduleOn.test(owner)) {
                    why = "held for " + owner + ", which is off";
                } else if (ceiling >= 0L && hold.since > 0L && now - hold.since > ceiling) {
                    why = "held " + (now - hold.since) + "ms, longer than its " + ceiling + "ms ceiling";
                }
            } catch (RuntimeException e) {
                /* A lease that cannot answer is left alone this tick: ending
                   a hold on a guess is itself a fault. */
                failures++;
                continue;
            }
            if (why == null) {
                continue;
            }
            Expiry expiry = new Expiry(now, hold.holder, hold.count, why);
            try {
                entry.lease.expire();
            } catch (RuntimeException e) {
                /* One holder failing to let go must not stop the others. */
                failures++;
                expiry = new Expiry(now, hold.holder, hold.count, why + "; ending it failed: " + e);
            }
            expiries++;
            lastExpiry = expiry;
            ended.add(expiry);
        }
        return ended;
    }

    /** Holds ended by a lease since start: each one a holder that did not let go by itself. */
    public static int expiries() {
        return expiries;
    }

    /** Leases that could not answer or could not end their hold. */
    public static int failures() {
        return failures;
    }

    /** The most recent lease ended, or null. */
    public static Expiry lastExpiry() {
        return lastExpiry;
    }

    /**
     * Runs the leases once a tick, after everything else has had its turn to
     * let go by itself (LOWEST, POST).
     */
    public static final class Watchdog {
        private final Predicate<String> moduleOn;

        public Watchdog(Predicate<String> moduleOn) {
            this.moduleOn = moduleOn;
        }

        @EventTarget(Priority.LOWEST)
        public void onTick(TickEvent event) {
            if (event.getType() != EventType.POST) {
                return;
            }
            for (Expiry expiry : enforce(System.currentTimeMillis(), this.moduleOn)) {
                /* A fault, not an event: every line here is a holder that
                   would otherwise still be holding. */
                System.err.println("[Myau] packet hold lease ended: " + expiry);
            }
        }
    }

    /** Test support only. */
    static void clearForTests() {
        ENTRIES.clear();
        expiries = 0;
        failures = 0;
        lastExpiry = null;
    }
}
