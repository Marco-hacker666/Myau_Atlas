package myau.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive's decision, apart from the module so it can be read and tested on
 * its own (plan step 10, docs/ARCH-AUDIT-2026-09-28.md).
 *
 * The pipeline: an OBSERVATION (a flag, from FlagDetector) becomes EVIDENCE
 * (Brain's on/off counts), which yields HYPOTHESES (Brain.Suspect: "module M
 * adds x flags/min of kind K"), each with a CONFIDENCE; this class makes the
 * DECISION, and Adaptive carries out the ACTION and writes down why.
 *
 * What it refuses to do, and the reason each rule exists:
 *   - act while not connected, or in the first minutes of a connection:
 *     joining produces corrections (placement, lobby moves) that say nothing
 *     about any module.
 *   - act on weak evidence: below the excess threshold, below the confidence
 *     threshold, on a kind seen only a handful of times, or with only a few
 *     flags on the module's side.
 *   - act on stale evidence: a kind not seen for a half-life is history.
 *   - act on a module that cannot cause a correction (it only draws), is
 *     protected, or is already off.
 *   - act twice on the same evidence (F-19). The log of 2026-09-23/24 shows
 *     the old loop naming the same module every five minutes -- "would
 *     disable Trajectories" nine times in a row -- because nothing remembered
 *     that it had already acted. After an action on a module, it is not acted
 *     on again until enough play has passed since (react minutes) and the
 *     flags measured SINCE the action still show the excess. If they do not,
 *     the action worked and the module is left alone.
 *   - act on one module more than a set number of times per server.
 */
public final class AdaptivePolicy {

    private static final long DAY_MS = 24L * 3600L * 1000L;

    private AdaptivePolicy() {
    }

    public static final class Settings {
        public double minExcess = 0.35;
        public double minConfidence = 0.55;
        /** Lifetime flags of the kind. */
        public long minSamples = 8L;
        /** Weighted flags of the kind while the module was on. */
        public double minFlagsOn = 3.0;
        /** No action this soon after a connection starts. */
        public long warmupMs = 3L * 60_000L;
        /** A kind not seen for this long is stale. */
        public double staleDays = Brain.HALF_LIFE_DAYS;
        /**
         * Flags of the kind this session before acting. History says where to
         * look; only a problem happening now justifies changing something now.
         * 2026-09-28 10:06: "would disable TargetFilter" on a kind last seen
         * 3.5 days earlier and not once that session.
         */
        public int minSessionFlags = 1;
        /** Actions on one module before the server changes. */
        public int maxActs = 2;
        /** Minutes with the module on, after an action, before it can be acted on again. */
        public double reactMinutes = 20.0;
    }

    /**
     * What has happened since the last action on a module: the evidence a
     * second action must rest on. Adaptive adds to it as play goes on; minutes
     * on the client thread, flags wherever FlagDetector reports them.
     */
    public static final class Epoch {
        public final String module;
        public volatile String kind;
        public volatile long at;
        /** The off-side rate the last action was measured against. */
        public volatile double rateOffAtAct;
        public volatile double minutesOn;
        public volatile double flagsOn;
        public volatile int acts;

        public Epoch(String module) {
            this.module = module;
        }

        /** Starts a new epoch at an action. */
        public void acted(String kind, long at, double rateOff) {
            this.kind = kind;
            this.at = at;
            this.rateOffAtAct = rateOff;
            this.minutesOn = 0.0;
            this.flagsOn = 0.0;
            this.acts++;
        }

        public double rateOnSince() {
            return this.minutesOn <= 0.0 ? 0.0 : this.flagsOn / this.minutesOn;
        }
    }

    public interface Context {
        long now();

        /** When the current connection started; 0 when not connected. */
        long sessionStartedAt();

        /** Why this module may not be acted on ("protected", "off", "cannot-cause", ...); null if it may. */
        String ineligible(String module);

        /** The epoch since the last action on this module, or null if there has been none. */
        Epoch epoch(String module);
    }

    public static final class Outcome {
        /** The suspect to act on; null to hold. */
        public final Brain.Suspect chosen;
        /** Why it was chosen, or why nothing was. */
        public final String reason;
        /** How many suspects were passed over, by reason, in the order met. */
        public final Map<String, Integer> skipped;

        Outcome(Brain.Suspect chosen, String reason, Map<String, Integer> skipped) {
            this.chosen = chosen;
            this.reason = reason;
            this.skipped = skipped;
        }

        public boolean acts() {
            return this.chosen != null;
        }

        /** "low-confidence 5, cannot-cause 3" */
        public String skippedSummary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : this.skipped.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append(' ').append(entry.getValue());
            }
            return sb.toString();
        }
    }

    /** Whether probing (buying evidence by switching a module off) may happen now. */
    public static boolean mayProbe(Settings settings, Context context) {
        long started = context.sessionStartedAt();
        return started > 0L && context.now() - started >= settings.warmupMs;
    }

    /** Picks the strongest suspect that passes every rule, or holds. Suspects come strongest first. */
    public static Outcome decide(List<Brain.Suspect> suspects, Settings settings, Context context) {
        Map<String, Integer> skipped = new LinkedHashMap<String, Integer>();
        long started = context.sessionStartedAt();
        if (started <= 0L) {
            return new Outcome(null, "not connected", skipped);
        }
        long age = context.now() - started;
        if (age < settings.warmupMs) {
            return new Outcome(null, "warming up, " + (settings.warmupMs - age) / 1000L + "s left", skipped);
        }
        for (Brain.Suspect suspect : suspects) {
            String why = check(suspect, settings, context);
            if (why == null) {
                return new Outcome(suspect, "strongest suspect passing every rule", skipped);
            }
            Integer seen = skipped.get(why);
            skipped.put(why, seen == null ? 1 : seen + 1);
        }
        return new Outcome(null, suspects.isEmpty() ? "no suspects" : "no suspect passes the rules", skipped);
    }

    /** Null if this suspect may be acted on, else the rule it fails. */
    static String check(Brain.Suspect suspect, Settings settings, Context context) {
        if (suspect.excess() < settings.minExcess) {
            return "below-threshold";
        }
        if (suspect.confidence < settings.minConfidence) {
            return "low-confidence";
        }
        if (suspect.samples < settings.minSamples) {
            return "few-samples";
        }
        if (suspect.flagsOn < settings.minFlagsOn) {
            return "few-flags-on";
        }
        if (suspect.lastSeen > 0L && context.now() - suspect.lastSeen > settings.staleDays * DAY_MS) {
            return "stale";
        }
        if (suspect.session < settings.minSessionFlags) {
            return "not-this-session";
        }
        String ineligible = context.ineligible(suspect.module);
        if (ineligible != null) {
            return ineligible;
        }
        Epoch epoch = context.epoch(suspect.module);
        if (epoch != null && epoch.acts > 0) {
            if (epoch.acts >= settings.maxActs) {
                return "act-cap";
            }
            if (epoch.minutesOn < settings.reactMinutes) {
                return "awaiting-evidence";
            }
            if (epoch.rateOnSince() - epoch.rateOffAtAct < settings.minExcess) {
                return "resolved";
            }
        }
        return null;
    }
}
