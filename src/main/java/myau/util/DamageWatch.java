package myau.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * How much each landed hit took off its target, and whether that has fallen
 * to a fraction of what hits usually do (2026-09-28).
 *
 * Some anticheats do not refuse a hit they distrust -- the target still
 * flinches -- they quietly cut its damage: a "silent" flag. Nothing in the
 * client says so. What it can see is the target's health (and absorption)
 * before and after the flinch that answers its own attack, so a run of hits
 * that each take a small part of the usual amount is the sign.
 *
 * Fed once a client tick with each watched target's health and hurt time;
 * plain arithmetic, so it can be tested. Client thread.
 */
public final class DamageWatch {

    /** One landed hit, resolved. */
    public static final class Hit {
        public final int targetId;
        public final float dealt;
        public final float usual;
        public final long at;
        /** The least vanilla would have taken off (DamageModel); NaN when not known. */
        public final float expected;

        Hit(int targetId, float dealt, float usual, long at, float expected) {
            this.targetId = targetId;
            this.dealt = dealt;
            this.usual = usual;
            this.at = at;
            this.expected = expected;
        }
    }

    /** A run of hits that did far less than usual. */
    public static final class Verdict {
        public final int hits;
        public final float dealt;
        public final float usual;

        Verdict(int hits, float dealt, float usual) {
            this.hits = hits;
            this.dealt = dealt;
            this.usual = usual;
        }
    }

    /* Tuning, deliberately fixed: see ENGINEERING-NOTES. */
    /** A hit this share of the usual or less is a low one. */
    public static final float LOW_SHARE = 0.35F;
    /** Low hits in a row before it is called. */
    public static final int STREAK = 3;
    /** Hits needed before "usual" means anything. */
    public static final int MIN_BASELINE = 6;
    /** Hits kept for "usual". */
    private static final int BASELINE_SIZE = 12;
    /** Ticks after the flinch to wait for the health to follow it. */
    private static final int SETTLE_TICKS = 6;
    /** Hits read before deciding health is hidden, if none moved it. */
    private static final int HIDDEN_AFTER = 5;

    private static final class Target {
        float lastHealth = Float.NaN;
        int lastHurtTime;
        long lastAttackAt;
        /* The hit being resolved: health before the flinch, lowest since. */
        float before = Float.NaN;
        float lowest;
        int ticksLeft;
        long hitAt;
        /* The least the pending attack should take off, and that of the hit being resolved. */
        float attackExpected = Float.NaN;
        float hitExpected = Float.NaN;
        final Deque<Float> own = new ArrayDeque<Float>();
    }

    private final Map<Integer, Target> targets = new HashMap<Integer, Target>();
    private final Deque<Float> session = new ArrayDeque<Float>();
    private int lowStreak;
    private float lowSum;
    private int resolved;
    private int moved;
    private boolean hidden;

    /** This client attacked the target (the packet left). */
    public void attack(int targetId, long now) {
        attack(targetId, now, Float.NaN);
    }

    /** The same, with the least the hit should take off (DamageModel), for the mitigation check. */
    public void attack(int targetId, long now, float expected) {
        Target t = target(targetId);
        t.lastAttackAt = now;
        t.attackExpected = expected;
    }

    /**
     * One tick of a watched target. Returns the hit it resolved, if any.
     *
     * @param health     health plus absorption as the client has it
     * @param hurtTime   the target's hurt time (10 on the flinch, counting down)
     * @param window     how long after an attack a flinch is still its answer
     */
    public Hit tick(int targetId, float health, int hurtTime, long now, long window) {
        Target t = target(targetId);
        Hit hit = null;
        if (t.ticksLeft > 0) {
            t.lowest = Math.min(t.lowest, health);
            if (--t.ticksLeft == 0) {
                hit = resolve(targetId, t);
            }
        }
        boolean flinched = hurtTime > t.lastHurtTime;
        if (flinched && t.ticksLeft == 0 && t.lastAttackAt > 0L && now - t.lastAttackAt <= window
                && !Float.isNaN(t.lastHealth) && t.lastHealth > 0.0F) {
            /* The health before the flinch: the tick before, since the
               metadata can land with it or a tick after. */
            t.before = t.lastHealth;
            t.lowest = Math.min(t.lastHealth, health);
            t.ticksLeft = SETTLE_TICKS;
            t.hitAt = now;
            t.lastAttackAt = 0L;
            t.hitExpected = t.attackExpected;
        }
        t.lastHurtTime = hurtTime;
        t.lastHealth = health;
        return hit;
    }

    private Hit resolve(int targetId, Target t) {
        float dealt = Math.max(0.0F, t.before - t.lowest);
        t.before = Float.NaN;
        if (t.lowest <= 0.0F) {
            /* Dead: the last hit's damage is capped by what was left. */
            return null;
        }
        this.resolved++;
        if (dealt > 0.001F) {
            this.moved++;
        }
        if (!this.hidden && this.resolved >= HIDDEN_AFTER && this.moved == 0) {
            this.hidden = true;
        }
        float usual = usual(t);
        push(t.own, dealt);
        push(this.session, dealt);
        Hit hit = new Hit(targetId, dealt, usual, t.hitAt, t.hitExpected);
        noteMitigation(hit);
        if (!Float.isNaN(usual) && dealt <= usual * LOW_SHARE) {
            this.lowStreak++;
            this.lowSum += dealt;
        } else {
            this.lowStreak = 0;
            this.lowSum = 0.0F;
        }
        return hit;
    }

    /**
     * A run of low hits, once per run: STREAK in a row, each at most
     * LOW_SHARE of the usual. Null otherwise.
     */
    public Verdict verdict(Hit last) {
        if (this.hidden || last == null || this.lowStreak < STREAK) {
            return null;
        }
        Verdict verdict = new Verdict(this.lowStreak, this.lowSum / this.lowStreak, last.usual);
        this.lowStreak = 0;
        this.lowSum = 0.0F;
        return verdict;
    }

    /** "Usual": this target's median once it has enough, else the session's. NaN until either does. */
    private float usual(Target t) {
        if (t.own.size() >= MIN_BASELINE) {
            return median(t.own);
        }
        if (this.session.size() >= MIN_BASELINE) {
            return median(this.session);
        }
        return Float.NaN;
    }

    /** Whether the server looks to hide other players' health (it never moves). */
    public boolean healthHidden() {
        return this.hidden;
    }

    /** Hits resolved since start. */
    public int resolved() {
        return this.resolved;
    }

    /** Forget targets not attacked for a while. */
    public void forget(long now, long olderThan) {
        Iterator<Map.Entry<Integer, Target>> it = this.targets.entrySet().iterator();
        while (it.hasNext()) {
            Target t = it.next().getValue();
            if (t.ticksLeft == 0 && now - Math.max(t.lastAttackAt, t.hitAt) > olderThan) {
                it.remove();
            }
        }
    }

    /** The targets being watched. */
    public List<Integer> watched() {
        return new ArrayList<Integer>(this.targets.keySet());
    }

    // ------------------------------------------------------------ dropped hits

    /** Hits the server did not react to at all, in a row, before it is called. */
    public static final int DROP_STREAK = 4;
    /** A gap this long between two dropped hits starts a new run. */
    private static final long DROP_GAP_MS = 3000L;
    private int dropStreak;
    private long lastDroppedAt;

    /**
     * A hit the server did not act on at all -- no flinch, no damage, though
     * in range and in sight (HitCheck's "dropped"). Returns the run's length
     * the moment it reaches DROP_STREAK, once per run; 0 otherwise.
     */
    public int dropped(long now) {
        if (now - this.lastDroppedAt > DROP_GAP_MS) {
            this.dropStreak = 0;
        }
        this.lastDroppedAt = now;
        this.dropStreak++;
        return this.dropStreak == DROP_STREAK ? this.dropStreak : 0;
    }

    /** A hit that landed ends a run of dropped ones. */
    public void landed() {
        this.dropStreak = 0;
    }

    // -------------------------------------------------------------- mitigation

    /*
     * "Mitigation" (2026-10-04): the server cutting this client's damage, read
     * as recent hits doing far less than vanilla says they must -- not less
     * than usual, which a mitigation present from the first hit would make
     * the usual. A hit is low when it took off at most MITIGATED_SHARE of the
     * least it should have, less the health precision. Called when LOW_RUN of
     * the last RECENT hits with a known expectation are low.
     */
    public static final float MITIGATED_SHARE = 0.5F;
    public static final int RECENT = 4;
    public static final int LOW_RUN = 3;
    /** Below this an expectation is too small to tell anything from. */
    private static final float MIN_EXPECTED = 2.0F;
    private final Deque<Hit> recentKnown = new ArrayDeque<Hit>();
    /** Health precision: 0 for exact health, 1 for a whole-number score (tab, under the name). */
    private float precision;

    /** How exact the health readings are, for the next hits: see recentKnown. */
    public void setPrecision(float precision) {
        this.precision = precision;
    }

    private void noteMitigation(Hit hit) {
        if (Float.isNaN(hit.expected) || hit.expected < MIN_EXPECTED) {
            return;
        }
        this.recentKnown.addLast(hit);
        while (this.recentKnown.size() > RECENT) {
            this.recentKnown.removeFirst();
        }
    }

    /** Whether a hit took off much less than it had to. */
    public boolean mitigated(Hit hit) {
        return !Float.isNaN(hit.expected) && hit.dealt + this.precision <= hit.expected * MITIGATED_SHARE;
    }

    /** The state of the mitigation check. */
    public static final class Mitigation {
        public final boolean active;
        public final int low;
        public final int of;
        public final float dealt;
        public final float expected;
        public final long lastAt;

        Mitigation(boolean active, int low, int of, float dealt, float expected, long lastAt) {
            this.active = active;
            this.low = low;
            this.of = of;
            this.dealt = dealt;
            this.expected = expected;
            this.lastAt = lastAt;
        }
    }

    /**
     * The recent hits, judged: active when LOW_RUN of the last RECENT are low,
     * and the newest of them is no older than maxAge. dealt/expected are the
     * averages of the low ones.
     */
    public Mitigation mitigation(long now, long maxAge) {
        int low = 0;
        float dealt = 0.0F;
        float expected = 0.0F;
        long last = 0L;
        for (Hit hit : this.recentKnown) {
            last = Math.max(last, hit.at);
            if (mitigated(hit)) {
                low++;
                dealt += hit.dealt;
                expected += hit.expected;
            }
        }
        boolean active = low >= LOW_RUN && now - last <= maxAge;
        return new Mitigation(active, low, this.recentKnown.size(),
                low > 0 ? dealt / low : 0.0F, low > 0 ? expected / low : 0.0F, last);
    }

    /** The current run of dropped hits (0 when the last one was more than DROP_GAP_MS ago). */
    public int droppedRun(long now) {
        return now - this.lastDroppedAt > DROP_GAP_MS ? 0 : this.dropStreak;
    }

    /** A new server: its hits and its health rules are its own. */
    public void reset() {
        this.recentKnown.clear();
        this.dropStreak = 0;
        this.lastDroppedAt = 0L;
        this.targets.clear();
        this.session.clear();
        this.lowStreak = 0;
        this.lowSum = 0.0F;
        this.resolved = 0;
        this.moved = 0;
        this.hidden = false;
    }

    private Target target(int id) {
        Target t = this.targets.get(id);
        if (t == null) {
            t = new Target();
            this.targets.put(id, t);
        }
        return t;
    }

    private static void push(Deque<Float> values, float value) {
        values.addLast(value);
        while (values.size() > BASELINE_SIZE) {
            values.removeFirst();
        }
    }

    static float median(Deque<Float> values) {
        float[] sorted = new float[values.size()];
        int i = 0;
        for (Float v : values) {
            sorted[i++] = v;
        }
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0F;
    }
}
