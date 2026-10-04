package myau.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Brain persistence and ageing (plan step 9, 2026-09-28). */
public class BrainTest {

    private static final long DAY = 24L * 3600L * 1000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File data;
    private File archive;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @Before
    public void setUp() throws Exception {
        this.data = this.folder.newFolder("config");
        this.archive = this.folder.newFolder("backups");
        Brain.useForTests(this.data, this.archive, this.now::get);
    }

    @After
    public void tearDown() {
        Brain.useForTests(new File("./build/brain-test-unused"), new File("./build/brain-test-unused"),
                System::currentTimeMillis);
    }

    /** Simulates a restart: memory gone, same files. */
    private void restart() {
        Brain.useForTests(this.data, this.archive, this.now::get);
    }

    private static Map<String, Boolean> states(Object... pairs) {
        Map<String, Boolean> map = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return map;
    }

    /** Twenty minutes with Reach on, twenty off; 30 flags with it on, 2 without. */
    private void someEvidence() {
        Brain.noteExposure(states("Reach", true), 20.0);
        Brain.noteExposure(states("Reach", false), 20.0);
        Brain.noteFlag("LAGBACK|air", 30, states("Reach", true));
        Brain.noteFlag("LAGBACK|air", 2, states("Reach", false));
    }

    private File file(String server) {
        return new File(this.data, "brain-" + server + ".txt");
    }

    private List<String> lines(File file) throws Exception {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private String[] archived() {
        String[] names = this.archive.list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void saveAndLoadRoundTrip() throws Exception {
        Brain.setServer("a.example");
        someEvidence();
        Brain.save();
        List<String> written = lines(file("a.example"));
        assertEquals("brain-2", written.get(0));
        assertEquals("server\ta.example", written.get(1));
        assertEquals("end", written.get(written.size() - 1));
        assertFalse("no temporary file left", new File(this.data, "brain-a.example.txt.tmp").exists());

        restart();
        Brain.setServer("a.example");
        assertEquals("", Brain.lastProblem());
        assertEquals(1, Brain.kindCount());
        Brain.Exposure exposure = Brain.exposure("Reach");
        assertEquals(20.0, exposure.minutesOn, 1e-3);
        assertEquals(20.0, exposure.minutesOff, 1e-3);
        Brain.Kind kind = Brain.kinds().get("LAGBACK|air");
        assertEquals(32L, kind.total);
        List<Brain.Suspect> suspects = Brain.suspects(0.1);
        assertEquals(1, suspects.size());
        assertEquals("Reach", suspects.get(0).module);
        assertEquals(1.5, suspects.get(0).rateOn, 1e-3);
        assertEquals(0.1, suspects.get(0).rateOff, 1e-3);
    }

    @Test
    public void missingFileIsAnEmptyStart() {
        Brain.setServer("new.example");
        assertEquals(0, Brain.kindCount());
        assertEquals("", Brain.lastProblem());
        assertEquals(0, archived().length);
    }

    @Test
    public void differentServersNeverMix() {
        Brain.setServer("a.example");
        someEvidence();
        Brain.setServer("b.example");
        assertEquals("B starts empty", 0, Brain.kindCount());
        Brain.noteExposure(states("Velocity", true), 5.0);
        Brain.noteFlag("REJECT|place", 3, states("Velocity", true));
        Brain.setServer("a.example");
        assertNotNull(Brain.kinds().get("LAGBACK|air"));
        assertNull("B's kind is not in A", Brain.kinds().get("REJECT|place"));
        assertNull(Brain.exposure("Velocity"));
    }

    @Test
    public void evidenceHalvesEveryHalfLife() {
        Brain.setServer("a.example");
        someEvidence();
        Brain.Suspect fresh = Brain.suspects(0.1).get(0);
        assertEquals(1.0, fresh.confidence, 1e-9);

        this.now.addAndGet(14L * DAY);
        Brain.Exposure aged = Brain.exposure("Reach");
        Brain.suspects(0.1); // forces ageing
        aged = Brain.exposure("Reach");
        assertEquals(10.0, aged.minutesOn, 1e-3);
        Brain.Suspect old = Brain.suspects(0.1).get(0);
        assertEquals("the rate is a weighted average: unchanged", fresh.rateOn, old.rateOn, 1e-6);
        assertEquals("but it rests on half the evidence", 10.0 / 16.0, old.confidence, 1e-6);
        assertEquals("the lifetime count is not decayed", 32L, old.samples);
        assertEquals(16.0, old.recent, 1e-3);
    }

    @Test
    public void newEvidenceOutweighsOldAfterAChange() {
        Brain.setServer("a.example");
        someEvidence(); // Reach used to add 1.4 flags/min
        this.now.addAndGet(60L * DAY);
        /* Two months later the server no longer minds: equal rates either way. */
        Brain.noteExposure(states("Reach", true), 20.0);
        Brain.noteExposure(states("Reach", false), 20.0);
        Brain.noteFlag("LAGBACK|air", 2, states("Reach", true));
        Brain.noteFlag("LAGBACK|air", 2, states("Reach", false));
        List<Brain.Suspect> suspects = Brain.suspects(0.35);
        assertTrue("the old conclusion has faded below the threshold: " + suspects, suspects.isEmpty());
    }

    @Test
    public void ageingSurvivesARestart() {
        Brain.setServer("a.example");
        someEvidence();
        Brain.save();
        this.now.addAndGet(14L * DAY);
        restart();
        Brain.setServer("a.example");
        assertEquals(10.0, Brain.exposure("Reach").minutesOn, 1e-3);
    }

    @Test
    public void corruptFileKeepsItsGoodLinesAndTheOriginal() throws Exception {
        Files.write(file("a.example").toPath(), Arrays.asList(
                "brain-2", "server\ta.example", "asof\t" + this.now.get(), "halflife-days\t14.0",
                "E\tReach\t20.0000\t20.0000\t1\t2",
                "E\tBroken\tnot-a-number\t1\t1\t1",
                "K\tLAGBACK|air\t32\t32.0000\t1\t2",
                "F\tLAGBACK|air\tReach\t30.0000\t2.0000"
                /* no "end": truncated */), StandardCharsets.UTF_8);
        Brain.setServer("a.example");
        assertTrue(Brain.lastProblem(), Brain.lastProblem().contains("truncated"));
        assertTrue(Brain.lastProblem(), Brain.lastProblem().contains("1 unreadable"));
        assertEquals(20.0, Brain.exposure("Reach").minutesOn, 1e-3);
        assertNull(Brain.exposure("Broken"));
        assertEquals(1, Brain.kindCount());
        String[] kept = archived();
        assertEquals(1, kept.length);
        assertTrue(kept[0], kept[0].startsWith("brain-a.example.txt.corrupt-"));
    }

    @Test
    public void brainOneIsMigratedAndTheOriginalKept() throws Exception {
        File old = file("a.example");
        Files.write(old.toPath(), Arrays.asList(
                "brain-1",
                "E\tReach\t100.0000\t40.0000",
                "K\tLAGBACK|air\t50",
                "F\tLAGBACK|air\tReach\t40.00\t10.00"), StandardCharsets.UTF_8);
        /* Last written fourteen days ago: its evidence is aged from then. */
        assertTrue(old.setLastModified(this.now.get() - 14L * DAY));
        Brain.setServer("a.example");
        assertTrue(Brain.lastProblem(), Brain.lastProblem().startsWith("migrated from brain-1"));
        assertEquals(50.0, Brain.exposure("Reach").minutesOn, 0.01);
        assertEquals(50L, Brain.kinds().get("LAGBACK|air").total);

        String[] kept = archived();
        assertEquals(1, kept.length);
        assertTrue(kept[0], kept[0].startsWith("brain-a.example.txt.v1-"));
        assertEquals("brain-1", lines(new File(this.archive, kept[0])).get(0));

        Brain.save();
        assertEquals("rewritten as brain-2", "brain-2", lines(old).get(0));
    }

    @Test
    public void unknownFormatIsKeptNotOverwrittenSilently() throws Exception {
        Files.write(file("a.example").toPath(), Arrays.asList("brain-9", "whatever"), StandardCharsets.UTF_8);
        Brain.setServer("a.example");
        assertEquals(0, Brain.kindCount());
        assertTrue(Brain.lastProblem(), Brain.lastProblem().startsWith("unknown format"));
        String[] kept = archived();
        assertEquals(1, kept.length);
        assertEquals("brain-9", lines(new File(this.archive, kept[0])).get(0));
    }

    @Test
    public void aFileForAnotherServerIsNotUsed() throws Exception {
        Files.write(file("a.example").toPath(), Arrays.asList(
                "brain-2", "server\tb.example", "asof\t" + this.now.get(), "halflife-days\t14.0",
                "E\tReach\t20.0000\t20.0000\t1\t2", "end"), StandardCharsets.UTF_8);
        Brain.setServer("a.example");
        assertNull(Brain.exposure("Reach"));
        assertTrue(Brain.lastProblem(), Brain.lastProblem().contains("b.example"));
        assertTrue(archived()[0].startsWith("brain-a.example.txt.foreign-"));
    }

    @Test
    public void sessionCountsStartOverEachSession() {
        Brain.setServer("a.example");
        Brain.noteFlag("LAGBACK|air", 3, states("Reach", true));
        assertEquals(3, Brain.kinds().get("LAGBACK|air").session);
        Brain.beginSession();
        assertEquals(0, Brain.kinds().get("LAGBACK|air").session);
        assertEquals("lifetime kept", 3L, Brain.kinds().get("LAGBACK|air").total);
    }

    @Test
    public void nothingIsWrittenWithoutEvidence() {
        Brain.setServer("a.example");
        Brain.save();
        assertFalse(file("a.example").exists());
        assertEquals(Collections.emptyList(), Brain.suspects(0.0));
    }
}
