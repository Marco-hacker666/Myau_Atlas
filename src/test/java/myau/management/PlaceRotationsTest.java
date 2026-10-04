package myau.management;

import org.junit.Test;

import static org.junit.Assert.*;

/** Grim DuplicateRotPlace's memory, followed from sent packets (2026-10-04). */
public class PlaceRotationsTest {

    @Test
    public void aRepeatedStepAtTheNextPlacementIsCaught() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 100.0F);
        s.place();
        s.flying(true, 106.5F);          // placement judged with step 6.5
        assertTrue(s.wouldDuplicate(113.0F));   // 6.5 again
        assertFalse(s.wouldDuplicate(113.2F));
    }

    @Test
    public void aPlacementWithoutALookLeavesTheMemoryAsItWas() {
        /* The case the modules' own bookkeeping got wrong: a placement on a
           tick whose packet carried no look does not change what the server
           compares against. */
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 6.5F);            // judged: 6.5
        s.flying(true, 9.7F);            // a turn of 3.2, no placement
        s.place();
        s.flying(false, 9.7F);           // judged with 3.2: a rotation came since
        s.place();
        s.flying(false, 9.7F);           // no look since: not judged, memory stays 3.2
        assertTrue(s.wouldDuplicate(12.9F));
        assertFalse(s.wouldDuplicate(16.2F));
    }

    @Test
    public void smallStepsNeverCount() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 1.5F);
        assertFalse(s.wouldDuplicate(3.0F));
    }

    @Test
    public void nothingIsKnownAfterAReset() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 6.5F);
        s.reset();
        assertFalse(s.wouldDuplicate(13.0F));
    }
}
