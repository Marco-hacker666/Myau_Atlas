package myau.util;

/**
 * Which latency tier a connection is in, without flapping (plan step 8, F-25).
 *
 * LatencyGovernor used one threshold both ways and acted on every sample, so an
 * average hovering at a threshold -- 225 ms against a 250 ms line is where this
 * user's connection lives -- switched tier, and rewrote BackTrack and Reach,
 * as often as the average crossed it. Two things stop that here:
 *
 *   HYSTERESIS. A tier is entered at its threshold but only left once the
 *   average is {@code margin} below it. The jitter flag works the same way:
 *   set above the limit, cleared below four fifths of it.
 *
 *   SETTLING. A different tier has to be wanted for {@code settle}
 *   consecutive samples before it is taken. One odd sample changes nothing.
 *
 * The first decision is taken at once: there is nothing to flap from --
 * but never straight to the worst tier.
 *
 *   THE WORST TIER IS STICKY, SO IT IS SLOW TO ENTER. Tier 2 switches the
 *   packet-holding modules off for the rest of the connection, so a short
 *   visit costs as much as a long one. The first live session on the 09:27
 *   jar (2026-09-28, 09:36:24) entered it on a jitter spike and left six
 *   seconds later -- the cut would have stood until the disconnect. Tier 2
 *   therefore has to be wanted three times as long as any other change.
 *
 * Pure arithmetic, no Minecraft, so it is tested directly.
 */
public final class LatencyTiers {
    public static final int UNKNOWN = -1;

    /** The tier in force. */
    private int tier = UNKNOWN;
    /** The tier the average alone asks for, with hysteresis. */
    private int pingTier = UNKNOWN;
    private boolean jittery;
    private int pending = UNKNOWN;
    private int pendingCount;

    public int tier() {
        return this.tier;
    }

    public boolean jittery() {
        return this.jittery;
    }

    public void reset() {
        this.tier = UNKNOWN;
        this.pingTier = UNKNOWN;
        this.jittery = false;
        this.pending = UNKNOWN;
        this.pendingCount = 0;
    }

    /**
     * One sample; returns the tier in force afterwards.
     *
     * @param average     mean round trip, ms
     * @param jitter      mean deviation, ms
     * @param mid         threshold of tier 1
     * @param high        threshold of tier 2
     * @param jitterLimit jitter above which the line counts as one tier worse
     * @param margin      how far below a threshold the average must fall to leave that tier
     * @param settle      samples in a row a new tier must be wanted before it is taken
     */
    public int update(int average, int jitter, int mid, int high, int jitterLimit, int margin, int settle) {
        this.pingTier = byPing(average, this.pingTier, mid, high, margin);
        if (jitter > jitterLimit) {
            this.jittery = true;
        } else if (jitter < jitterLimit * 4 / 5) {
            this.jittery = false;
        }
        /* Jitter can make a clear line "holding back", never "minimum": on
           Pika (2026-09-28, 10:01-10:08) the jitter sat at the limit while the
           average swung 110-210 ms, and jitter on top of a middling average
           put the governor into the worst tier four times in eleven minutes,
           twice for under ten seconds -- each costing LagRange for the rest of
           the connection. The worst tier is for a slow line, by the average. */
        int wanted = this.jittery ? Math.max(this.pingTier, 1) : this.pingTier;

        if (this.tier == UNKNOWN) {
            this.tier = Math.min(1, wanted);
            this.pending = UNKNOWN;
            this.pendingCount = 0;
            if (wanted == this.tier) {
                return this.tier;
            }
        } else if (wanted == this.tier) {
            this.pending = UNKNOWN;
            this.pendingCount = 0;
            return this.tier;
        }
        if (wanted != this.pending) {
            this.pending = wanted;
            this.pendingCount = 1;
        } else {
            this.pendingCount++;
        }
        int needed = Math.max(1, settle) * (wanted == 2 ? 3 : 1);
        if (this.pendingCount >= needed) {
            this.tier = wanted;
            this.pending = UNKNOWN;
            this.pendingCount = 0;
        }
        return this.tier;
    }

    /** The tier by average alone: up at a threshold, down only {@code margin} below it. */
    static int byPing(int average, int current, int mid, int high, int margin) {
        int enter = average >= high ? 2 : (average >= mid ? 1 : 0);
        if (current == UNKNOWN || enter >= current) {
            return enter;
        }
        int stay = average >= high - margin ? 2 : (average >= mid - margin ? 1 : 0);
        return Math.max(enter, Math.min(current, stay));
    }
}
