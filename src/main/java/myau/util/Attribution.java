package myau.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who most plausibly caused one server correction, and how sure that is
 * (plan step 12, docs/ARCH-AUDIT-2026-09-28.md).
 *
 * The old blame named every module that did anything in the two seconds
 * before a correction, once each, and FlagResponder switched off whichever
 * collected five names in thirty seconds. That is a count of activity, not of
 * cause: Backtrack holding forty packets of OTHER players' movement -- which
 * cannot move this player -- was named on every flag in a fight, exactly as
 * often as a Blink releasing a queue of this player's own positions (flags of
 * 2026-09-28, 09:41: "Backtrackx41 BLINKx2"). Evidence here is weighed:
 *
 *   BY KIND. Withholding or releasing this player's own movement is what a
 *   correction is usually about (1.0); holding this player's own knockback or
 *   position (0.8); a placement (0.5), a dig (0.4), a sprint or sneak toggle
 *   (0.3), an attack (0.2); holding other entities' packets (0.1); a swing
 *   (0.05).
 *
 *   BY TIME. Each action counts less the longer before the correction it
 *   happened (time constant one second), and nothing outside the window
 *   counts at all.
 *
 *   NOT BY VOLUME. Many actions of one kind approach that kind's weight and
 *   no further: forty held entity packets are still "held entity packets".
 *
 * And the answer can be "not sure". A share of every correction is left to an
 * UNKNOWN cause -- the server, the route, vanilla movement, a module that
 * acts without packets -- and the verdict is:
 *   SINGLE              one module holds at least half, and twice the next;
 *   MULTIPLE_CANDIDATES two or more are close, none decisive;
 *   INCONCLUSIVE        something was going on, but nothing strongly;
 *   UNKNOWN             nothing in the window at all.
 * Only SINGLE should ever be acted on.
 *
 * Pure: records in, result out.
 */
public final class Attribution {

    public enum Verdict { SINGLE, MULTIPLE_CANDIDATES, INCONCLUSIVE, UNKNOWN }

    /** The weight left to causes the ledger cannot see. */
    public static final double UNKNOWN_PRIOR = 0.5;
    /** Time constant of the recency weight. */
    public static final double TAU_MS = 1000.0;

    private Attribution() {
    }

    /** How plausibly an action of this kind moves the player's server-side position. */
    public static double weight(String kind) {
        if (kind == null) {
            return 0.2;
        }
        switch (kind) {
            case "held-send":
            case "release":
                return 1.0;
            case "held-self":
            case "release-self":
                return 0.8;
            case "place":
                return 0.5;
            case "dig":
                return 0.4;
            case "action":
                return 0.3;
            case "attack":
                return 0.2;
            case "held-recv":
                return 0.1;
            case "swing":
                return 0.05;
            default:
                return 0.2;
        }
    }

    public static final class Candidate {
        public final String module;
        public final double score;
        public final double confidence;
        /** Actions considered, by kind, with counts. */
        public final Map<String, Integer> kinds;

        Candidate(String module, double score, double confidence, Map<String, Integer> kinds) {
            this.module = module;
            this.score = score;
            this.confidence = confidence;
            this.kinds = kinds;
        }

        String kindsText() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : this.kinds.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(entry.getKey()).append(" x").append(entry.getValue());
            }
            return sb.toString();
        }
    }

    public static final class Result {
        public final Verdict verdict;
        /** Strongest first. */
        public final List<Candidate> candidates;
        /** The share left to an unseen cause. */
        public final double unknownShare;
        /** Why the verdict is what it is. */
        public final String reason;

        Result(Verdict verdict, List<Candidate> candidates, double unknownShare, String reason) {
            this.verdict = verdict;
            this.candidates = candidates;
            this.unknownShare = unknownShare;
            this.reason = reason;
        }

        /** The strongest candidate, or null. */
        public Candidate top() {
            return this.candidates.isEmpty() ? null : this.candidates.get(0);
        }

        /** The one module to blame: only for SINGLE. */
        public String culprit() {
            return this.verdict == Verdict.SINGLE ? top().module : null;
        }

        /** "SINGLE Blink 57% (held-send x2) | AutoClicker 12% (attack x3) | unknown 25%" */
        public String describe(int max) {
            StringBuilder sb = new StringBuilder(this.verdict.name());
            int shown = 0;
            for (Candidate candidate : this.candidates) {
                if (shown >= max) {
                    break;
                }
                sb.append(shown == 0 ? " " : " | ").append(candidate.module)
                        .append(String.format(Locale.ROOT, " %.0f%%", candidate.confidence * 100.0))
                        .append(" (").append(candidate.kindsText()).append(')');
                shown++;
            }
            sb.append(shown == 0 ? " " : " | ")
                    .append(String.format(Locale.ROOT, "unknown %.0f%%", this.unknownShare * 100.0));
            return sb.toString();
        }
    }

    /**
     * A correction whose cause is known outright -- a refused block and the
     * module whose click sent it (2026-10-04) -- rather than weighed from the
     * window: SINGLE, all of it, nothing left to an unseen cause.
     */
    public static Result direct(String module, String kind) {
        Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        kinds.put(kind, 1);
        List<Candidate> candidates = new ArrayList<Candidate>();
        candidates.add(new Candidate(module, 1.0, 1.0, kinds));
        return new Result(Verdict.SINGLE, candidates, 0.0, module + " " + kind);
    }

    /**
     * Weighs the records in the {@code window} before {@code at}.
     */
    public static Result attribute(List<ActionLedger.Record> records, long at, long window) {
        /* module -> kind -> summed recency, and counts */
        Map<String, Map<String, double[]>> byModule = new LinkedHashMap<String, Map<String, double[]>>();
        for (ActionLedger.Record record : records) {
            long age = at - record.at;
            if (record.module == null || age < 0L || age > window) {
                continue;
            }
            Map<String, double[]> kinds = byModule.get(record.module);
            if (kinds == null) {
                kinds = new LinkedHashMap<String, double[]>();
                byModule.put(record.module, kinds);
            }
            double[] sums = kinds.get(record.kind);
            if (sums == null) {
                sums = new double[2];
                kinds.put(record.kind, sums);
            }
            sums[0] += record.count * Math.exp(-age / TAU_MS);
            sums[1] += record.count;
        }

        List<String> names = new ArrayList<String>();
        List<Double> scores = new ArrayList<Double>();
        List<Map<String, Integer>> counts = new ArrayList<Map<String, Integer>>();
        double total = 0.0;
        for (Map.Entry<String, Map<String, double[]>> module : byModule.entrySet()) {
            double score = 0.0;
            Map<String, Integer> kindCounts = new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, double[]> kind : module.getValue().entrySet()) {
                /* Saturating: many of one kind approach the kind's weight. */
                score += weight(kind.getKey()) * (1.0 - Math.exp(-kind.getValue()[0]));
                kindCounts.put(kind.getKey(), (int) kind.getValue()[1]);
            }
            names.add(module.getKey());
            scores.add(score);
            counts.add(kindCounts);
            total += score;
        }

        List<Candidate> candidates = new ArrayList<Candidate>();
        double denominator = total + UNKNOWN_PRIOR;
        for (int i = 0; i < names.size(); i++) {
            candidates.add(new Candidate(names.get(i), scores.get(i), scores.get(i) / denominator, counts.get(i)));
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Double.compare(b.score, a.score);
            }
        });
        double unknownShare = UNKNOWN_PRIOR / denominator;

        if (candidates.isEmpty() || total < 0.05) {
            return new Result(Verdict.UNKNOWN, candidates, unknownShare, "nothing acted in the window");
        }
        Candidate top = candidates.get(0);
        Candidate second = candidates.size() > 1 ? candidates.get(1) : null;
        boolean decisive = second == null || top.score >= 2.0 * second.score;
        if (top.confidence >= 0.5 && decisive) {
            return new Result(Verdict.SINGLE, candidates, unknownShare,
                    String.format(Locale.ROOT, "%s holds %.0f%%%s", top.module, top.confidence * 100.0,
                            second == null ? "" : String.format(Locale.ROOT, ", %.1fx the next", top.score / second.score)));
        }
        if (second != null && !decisive && second.confidence >= 0.2) {
            return new Result(Verdict.MULTIPLE_CANDIDATES, candidates, unknownShare,
                    String.format(Locale.ROOT, "%s and %s within a factor of two", top.module, second.module));
        }
        return new Result(Verdict.INCONCLUSIVE, candidates, unknownShare,
                String.format(Locale.ROOT, "strongest is %s at %.0f%%", top.module, top.confidence * 100.0));
    }
}
