package myau.util;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Evidence-weighted attribution of a correction (plan step 12, 2026-09-28). */
public class AttributionTest {

    private static final long WINDOW = 2000L;
    private long now;

    @Before
    public void setUp() {
        ActionLedger.clear();
        this.now = System.currentTimeMillis();
    }

    /** Notes {@code times} actions and returns the ledger's records. */
    private static void act(String module, String kind, int times) {
        for (int i = 0; i < times; i++) {
            ActionLedger.note(module, kind);
        }
    }

    private Attribution.Result attribute() {
        return Attribution.attribute(ActionLedger.records(WINDOW), System.currentTimeMillis(), WINDOW);
    }

    @Test
    public void nothingInTheWindowIsUnknown() {
        Attribution.Result result = attribute();
        assertEquals(Attribution.Verdict.UNKNOWN, result.verdict);
        assertNull(result.culprit());
        assertEquals(1.0, result.unknownShare, 1e-9);
    }

    @Test
    public void heldMovementNamesItsHolder() {
        act("Blink", "held-send", 2);
        Attribution.Result result = attribute();
        assertEquals(Attribution.Verdict.SINGLE, result.verdict);
        assertEquals("Blink", result.culprit());
    }

    @Test
    public void manyEntityPacketsDoNotOutweighOneOwnMovementHold() {
        /* 09:41:16 on 2026-09-28: "Backtrackx41 BLINKx2". Backtrack held other
           players' packets; Blink held this player's own movement. */
        act("BackTrack", "held-recv", 41);
        act("Blink", "held-send", 2);
        Attribution.Result result = attribute();
        assertEquals(result.describe(3), "Blink", result.culprit());
        assertEquals("BackTrack", result.candidates.get(1).module);
        assertTrue(result.candidates.get(1).confidence < 0.1);
    }

    @Test
    public void busyButHarmlessIsNotACulprit() {
        /* A fight: clicks and swings, and entity packets held -- nothing that moves us. */
        act("AutoClicker", "attack", 6);
        act("AutoClicker", "swing", 6);
        act("BackTrack", "held-recv", 30);
        Attribution.Result result = attribute();
        assertNotEquals(Attribution.Verdict.SINGLE, result.verdict);
        assertNull(result.culprit());
    }

    @Test
    public void twoStrongCandidatesAreBothReported() {
        act("Blink", "held-send", 3);
        act("FakeLag", "release", 3);
        Attribution.Result result = attribute();
        assertEquals(Attribution.Verdict.MULTIPLE_CANDIDATES, result.verdict);
        assertNull("no single culprit is declared", result.culprit());
        assertEquals(2, result.candidates.size());
    }

    @Test
    public void aLonePlacementIsNotEnoughToBlame() {
        act("Scaffold", "place", 1);
        Attribution.Result result = attribute();
        assertEquals(Attribution.Verdict.INCONCLUSIVE, result.verdict);
    }

    @Test
    public void ownKnockbackHeldIsStrongEvidence() {
        act("KnockbackDelay", "held-self", 1);
        act("KnockbackDelay", "release-self", 1);
        assertEquals("KnockbackDelay", attribute().culprit());
    }

    @Test
    public void olderActionsCountLess() {
        List<ActionLedger.Record> records = new ArrayList<ActionLedger.Record>();
        act("Old", "held-send", 1);
        act("New", "held-send", 1);
        records.addAll(ActionLedger.records(WINDOW));
        long at = records.get(0).at;
        /* Seen from 1.5 s after both: equal. Now pretend "Old" was 1.5 s earlier. */
        Attribution.Result result = Attribution.attribute(records, at + 1500L, WINDOW);
        assertEquals(result.candidates.get(0).score, result.candidates.get(1).score, 0.2);
        Attribution.Result fresh = Attribution.attribute(records, at, WINDOW);
        assertTrue(fresh.candidates.get(0).score > result.candidates.get(0).score);
    }

    @Test
    public void actionsOutsideTheWindowDoNotCount() {
        act("Blink", "held-send", 5);
        List<ActionLedger.Record> records = ActionLedger.records(WINDOW);
        long newest = records.get(records.size() - 1).at;
        Attribution.Result result = Attribution.attribute(records, newest + WINDOW + 1L, WINDOW);
        assertEquals(Attribution.Verdict.UNKNOWN, result.verdict);
    }

    @Test
    public void aReleasedQueueCountsAsOneActionOfManyPackets() {
        ActionLedger.note("LagRange", "release", 12);
        Attribution.Result result = attribute();
        assertEquals("LagRange", result.culprit());
        assertEquals(Integer.valueOf(12), result.top().kinds.get("release"));
    }

    @Test
    public void theExplanationSaysWhatItRestsOn() {
        act("Blink", "held-send", 2);
        act("AutoClicker", "attack", 3);
        String line = attribute().describe(3);
        assertTrue(line, line.startsWith("SINGLE Blink "));
        assertTrue(line, line.contains("(held-send x2)"));
        assertTrue(line, line.contains("AutoClicker"));
        assertTrue(line, line.contains("(attack x3)"));
        assertTrue(line, line.contains("unknown "));
    }

    @Test
    public void weightsFollowWhatCanMoveThePlayer() {
        assertTrue(Attribution.weight("held-send") > Attribution.weight("place"));
        assertTrue(Attribution.weight("place") > Attribution.weight("attack"));
        assertTrue(Attribution.weight("attack") > Attribution.weight("held-recv"));
        assertTrue(Attribution.weight("held-recv") > Attribution.weight("swing"));
        assertEquals(Attribution.weight("held-self"), Attribution.weight("release-self"), 0.0);
    }

    @Test
    public void emptyRecordsAreHarmless() {
        assertEquals(Attribution.Verdict.UNKNOWN,
                Attribution.attribute(Collections.<ActionLedger.Record>emptyList(), this.now, WINDOW).verdict);
    }

    /* 2026-10-04: a refused block whose placing module is known is blamed on
       it outright, nothing left to "unknown". */
    @Test
    public void aDirectCauseIsSingleWithNothingUnknown() {
        Attribution.Result result = Attribution.direct("Clutch", "placed-this-block");
        assertEquals(Attribution.Verdict.SINGLE, result.verdict);
        assertEquals("Clutch", result.culprit());
        assertEquals(0.0, result.unknownShare, 1e-9);
        assertEquals("SINGLE Clutch 100% (placed-this-block x1) | unknown 0%", result.describe(3));
    }
}
