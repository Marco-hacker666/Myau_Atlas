package myau.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** AutoTune's experiment: current, candidate, current, candidate (plan step 11, 2026-09-28). */
public class TuneExperimentTest {

    private static TuneExperiment experiment() {
        return new TuneExperiment(7, "Reach.range", 3.10, 3.15, 2.0, 3);
    }

    @Test
    public void blocksAlternateBetweenTheTwoValues() {
        TuneExperiment e = experiment();
        assertEquals(TuneExperiment.State.BASELINE_1, e.state());
        assertEquals(3.10, e.valueNow(), 0.0);
        e.blockScored(90, 30);
        assertEquals(3.15, e.valueNow(), 0.0);
        e.blockScored(93, 30);
        assertEquals(TuneExperiment.State.BASELINE_2, e.state());
        assertEquals(3.10, e.valueNow(), 0.0);
        e.blockScored(91, 30);
        assertEquals(3.15, e.valueNow(), 0.0);
    }

    @Test
    public void betterTwiceByTheMarginIsCommitted() {
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        e.blockScored(93, 30);
        e.blockScored(91, 30);
        assertEquals(TuneExperiment.State.COMMITTED, e.blockScored(94, 30));
        assertEquals(3.0, e.difference(0), 1e-9);
        assertEquals(3.0, e.difference(1), 1e-9);
        assertTrue(e.reason(), e.reason().startsWith("better both times"));
    }

    @Test
    public void oneLuckyBlockIsNotEnough() {
        /* The old rule kept this: one block out-scored the baseline. */
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        e.blockScored(96, 30); // +6, lucky
        e.blockScored(92, 30);
        assertEquals(TuneExperiment.State.REJECTED, e.blockScored(91, 30)); // -1 the second time
        assertTrue(e.reason(), e.reason().startsWith("not better both times"));
    }

    @Test
    public void betterTwiceButByLessThanTheMarginIsRejected() {
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        e.blockScored(90.5, 30);
        e.blockScored(90, 30);
        assertEquals(TuneExperiment.State.REJECTED, e.blockScored(91, 30)); // mean +0.75 < 2
    }

    @Test
    public void clearlyWorseTheFirstTimeStopsEarly() {
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        assertEquals(TuneExperiment.State.REJECTED, e.blockScored(85, 30));
        assertTrue(e.finished());
        assertTrue(e.reason(), e.reason().startsWith("worse by 5.0"));
    }

    @Test
    public void thinBlocksAreReplayedThenTheExperimentExpires() {
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        for (int i = 0; i < 3; i++) {
            assertEquals("replayed, same block", TuneExperiment.State.CANDIDATE_1, e.blockThin());
            assertEquals(3.15, e.valueNow(), 0.0);
        }
        assertEquals(TuneExperiment.State.EXPIRED, e.blockThin());
        assertEquals(3.10, e.valueNow(), 0.0);
    }

    @Test
    public void aServerChangeExpiresIt() {
        TuneExperiment e = experiment();
        e.blockScored(90, 30);
        assertEquals(TuneExperiment.State.EXPIRED, e.expire("server changed"));
        assertEquals("server changed", e.reason());
        /* Finished experiments do not change. */
        e.blockScored(99, 30);
        assertEquals(TuneExperiment.State.EXPIRED, e.state());
    }

    @Test
    public void differenceIsUnknownUntilThePairIsComplete() {
        TuneExperiment e = experiment();
        assertTrue(Double.isNaN(e.difference(0)));
        e.blockScored(90, 30);
        assertTrue(Double.isNaN(e.difference(0)));
        e.blockScored(92, 30);
        assertEquals(2.0, e.difference(0), 1e-9);
        assertTrue(Double.isNaN(e.difference(1)));
    }
}
