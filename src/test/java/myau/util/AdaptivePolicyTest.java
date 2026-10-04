package myau.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Adaptive's decision rules (plan step 10, 2026-09-28). */
public class AdaptivePolicyTest {

    private static final long MIN = 60_000L;
    private static final long T0 = 1_800_000_000_000L;

    /** A controllable world for the policy. */
    private static final class World implements AdaptivePolicy.Context {
        long now = T0 + 60L * MIN;
        long sessionStart = T0;
        final Map<String, String> ineligible = new HashMap<String, String>();
        final Map<String, AdaptivePolicy.Epoch> epochs = new HashMap<String, AdaptivePolicy.Epoch>();

        @Override
        public long now() {
            return this.now;
        }

        @Override
        public long sessionStartedAt() {
            return this.sessionStart;
        }

        @Override
        public String ineligible(String module) {
            return this.ineligible.get(module);
        }

        @Override
        public AdaptivePolicy.Epoch epoch(String module) {
            return this.epochs.get(module);
        }
    }

    /** A suspect: +excess flags/min over an off-rate of 0.1, with the given confidence and sample size. */
    private static Brain.Suspect suspect(String module, double excess, double confidence, long samples, long lastSeen) {
        Brain.Kind kind = new Brain.Kind("LAGBACK|air");
        kind.session = 5;
        kind.total = samples;
        kind.recent = samples;
        kind.lastSeen = lastSeen;
        double rateOff = 0.1;
        double rateOn = rateOff + excess;
        double minutesOn = 60.0;
        return new Brain.Suspect(module, kind, rateOn * minutesOn, rateOn, rateOff, minutesOn, 30.0, confidence);
    }

    private static Brain.Suspect strong(String module, long now) {
        return suspect(module, 1.0, 1.0, 40L, now - MIN);
    }

    private static AdaptivePolicy.Settings settings() {
        return new AdaptivePolicy.Settings();
    }

    @Test
    public void nothingWhileNotConnected() {
        World world = new World();
        world.sessionStart = 0L;
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(
                Collections.singletonList(strong("Reach", world.now)), settings(), world);
        assertFalse(outcome.acts());
        assertEquals("not connected", outcome.reason);
    }

    @Test
    public void nothingInTheFirstMinutesOfAConnection() {
        World world = new World();
        world.now = world.sessionStart + 2L * MIN;
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(
                Collections.singletonList(strong("Reach", world.now)), settings(), world);
        assertFalse(outcome.acts());
        assertTrue(outcome.reason, outcome.reason.startsWith("warming up"));
        assertFalse("no probing either", AdaptivePolicy.mayProbe(settings(), world));
        world.now = world.sessionStart + 3L * MIN;
        assertTrue(AdaptivePolicy.decide(Collections.singletonList(strong("Reach", world.now)), settings(), world).acts());
    }

    @Test
    public void strongEvidenceIsActedOn() {
        World world = new World();
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(
                Collections.singletonList(strong("Reach", world.now)), settings(), world);
        assertTrue(outcome.acts());
        assertEquals("Reach", outcome.chosen.module);
    }

    @Test
    public void weakEvidenceIsPassedOverWithTheReasonCounted() {
        World world = new World();
        world.ineligible.put("Trajectories", "cannot-cause");
        List<Brain.Suspect> suspects = Arrays.asList(
                suspect("A", 0.2, 1.0, 40L, world.now),                   // below threshold
                suspect("B", 1.0, 0.3, 40L, world.now),                   // low confidence
                suspect("C", 1.0, 1.0, 3L, world.now),                    // few samples
                suspect("D", 1.0, 1.0, 40L, world.now - 30L * 24L * 60L * MIN), // stale
                strong("Trajectories", world.now),                        // cannot cause a correction
                strong("Velocity", world.now));
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(suspects, settings(), world);
        assertTrue(outcome.acts());
        assertEquals("Velocity", outcome.chosen.module);
        assertEquals("below-threshold 1, low-confidence 1, few-samples 1, stale 1, cannot-cause 1",
                outcome.skippedSummary());
    }

    @Test
    public void fewFlagsOnTheModulesSideIsNotEnough() {
        World world = new World();
        Brain.Kind kind = new Brain.Kind("LAGBACK|air");
        kind.session = 5;
        kind.total = 40L;
        kind.lastSeen = world.now;
        /* A high rate from two flags in a sliver of on-time. */
        Brain.Suspect thin = new Brain.Suspect("Reach", kind, 2.0, 2.0, 0.1, 1.0, 60.0, 1.0);
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(Collections.singletonList(thin), settings(), world);
        assertFalse(outcome.acts());
        assertEquals("few-flags-on 1", outcome.skippedSummary());
    }

    @Test
    public void afterAnActionTheModuleWaitsForNewEvidence() {
        World world = new World();
        AdaptivePolicy.Epoch epoch = new AdaptivePolicy.Epoch("Reach");
        epoch.acted("LAGBACK|air", world.now, 0.1);
        world.epochs.put("Reach", epoch);
        List<Brain.Suspect> same = Collections.singletonList(strong("Reach", world.now));

        epoch.minutesOn = 10.0;
        epoch.flagsOn = 15.0;
        assertEquals("awaiting-evidence 1", AdaptivePolicy.decide(same, settings(), world).skippedSummary());

        /* Enough play since, and the excess is still there: a second action is justified. */
        epoch.minutesOn = 25.0;
        epoch.flagsOn = 30.0;
        assertTrue(AdaptivePolicy.decide(same, settings(), world).acts());
    }

    @Test
    public void anActionThatWorkedIsNotRepeated() {
        World world = new World();
        AdaptivePolicy.Epoch epoch = new AdaptivePolicy.Epoch("Reach");
        epoch.acted("LAGBACK|air", world.now, 0.1);
        epoch.minutesOn = 30.0;
        epoch.flagsOn = 3.0; // 0.1/min: back to the off-side rate
        world.epochs.put("Reach", epoch);
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(
                Collections.singletonList(strong("Reach", world.now)), settings(), world);
        assertFalse(outcome.acts());
        assertEquals("resolved 1", outcome.skippedSummary());
    }

    @Test
    public void actionsPerModuleAreCapped() {
        World world = new World();
        AdaptivePolicy.Epoch epoch = new AdaptivePolicy.Epoch("Reach");
        epoch.acted("LAGBACK|air", world.now, 0.1);
        epoch.acted("LAGBACK|air", world.now, 0.1);
        epoch.minutesOn = 60.0;
        epoch.flagsOn = 60.0;
        world.epochs.put("Reach", epoch);
        assertEquals("act-cap 1", AdaptivePolicy.decide(
                Collections.singletonList(strong("Reach", world.now)), settings(), world).skippedSummary());
    }

    /**
     * The 2026-09-23 evening, replayed: the same historical suspect offered at
     * every five-minute round for three hours. The old loop acted every round
     * (nine "would disable Trajectories" in a row). Now: once, then again
     * only if the problem persists after it, and never more than the cap.
     */
    @Test
    public void theRatchetIsGone() {
        for (boolean persists : new boolean[]{false, true}) {
            World world = new World();
            int actions = 0;
            for (int round = 0; round < 36; round++) {
                world.now += 5L * MIN;
                AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(
                        Collections.singletonList(strong("KeepSprint", world.now)), settings(), world);
                if (outcome.acts()) {
                    actions++;
                    AdaptivePolicy.Epoch epoch = world.epochs.get("KeepSprint");
                    if (epoch == null) {
                        epoch = new AdaptivePolicy.Epoch("KeepSprint");
                        world.epochs.put("KeepSprint", epoch);
                    }
                    epoch.acted(outcome.chosen.kind, world.now, outcome.chosen.rateOff);
                }
                AdaptivePolicy.Epoch epoch = world.epochs.get("KeepSprint");
                if (epoch != null) {
                    epoch.minutesOn += 5.0;
                    epoch.flagsOn += persists ? 5.0 * 1.1 : 5.0 * 0.1;
                }
            }
            assertEquals(persists ? "persisting: acted up to the cap" : "resolved: acted once",
                    persists ? 2 : 1, actions);
        }
    }

    @Test
    public void historyAloneIsNotEnough() {
        /* 10:06 on 2026-09-28: strong evidence from days before, none this session. */
        World world = new World();
        Brain.Suspect old = strong("TargetFilter", world.now - 5054L * MIN);
        Brain.Kind kind = new Brain.Kind(old.kind);
        kind.total = 60L;
        kind.lastSeen = world.now - 5054L * MIN;
        kind.session = 0;
        Brain.Suspect quiet = new Brain.Suspect("TargetFilter", kind, 16.8, 1.81, 0.02, 9.3, 169.8, 0.58);
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(java.util.Collections.singletonList(quiet), settings(), world);
        assertFalse(outcome.acts());
        assertEquals("not-this-session 1", outcome.skippedSummary());
    }

    @Test
    public void strongestEligibleWins() {
        World world = new World();
        List<Brain.Suspect> suspects = new ArrayList<Brain.Suspect>();
        suspects.add(strong("First", world.now));
        suspects.add(strong("Second", world.now));
        assertEquals("First", AdaptivePolicy.decide(suspects, settings(), world).chosen.module);
    }
}
