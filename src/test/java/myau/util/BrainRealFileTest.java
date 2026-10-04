package myau.util;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Migrates a copy of the real Pika brain-1 file, when it is present on this
 * machine (skipped elsewhere). The original is never touched.
 */
public class BrainRealFileTest {

    private static final File REAL = new File("../config/Myau/brain-play.pika-network.net.txt");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void tearDown() {
        Brain.useForTests(new File("./build/brain-test-unused"), new File("./build/brain-test-unused"),
                System::currentTimeMillis);
    }

    @Test
    public void theRealFileMigratesCleanly() throws Exception {
        Assume.assumeTrue(REAL.isFile());
        List<String> original = Files.readAllLines(REAL.toPath(), StandardCharsets.UTF_8);
        Assume.assumeTrue("brain-1".equals(original.get(0).trim()));
        File data = this.folder.newFolder("config");
        File archive = this.folder.newFolder("backups");
        File copy = new File(data, REAL.getName());
        Files.copy(REAL.toPath(), copy.toPath());
        assertTrue(copy.setLastModified(REAL.lastModified()));

        Brain.useForTests(data, archive, System::currentTimeMillis);
        Brain.setServer("play.pika-network.net");
        assertTrue(Brain.lastProblem(), Brain.lastProblem().equals("migrated from brain-1"));

        long kinds = original.stream().filter(line -> line.startsWith("K\t")).count();
        long exposures = original.stream().filter(line -> line.startsWith("E\t")).count();
        assertEquals(kinds, Brain.kindCount());
        int seen = 0;
        for (String line : original) {
            if (line.startsWith("E\t")) {
                assertNotNull(line, Brain.exposure(line.split("\t")[1]));
                seen++;
            }
        }
        assertEquals(exposures, seen);
        System.out.println("real brain: " + kinds + " kinds, " + exposures + " modules; suspects now: "
                + Brain.suspects(0.35).size());

        Brain.save();
        List<String> rewritten = Files.readAllLines(copy.toPath(), StandardCharsets.UTF_8);
        assertEquals("brain-2", rewritten.get(0));
        assertEquals("end", rewritten.get(rewritten.size() - 1));
        assertEquals("the brain-1 original is kept", 1, archive.list().length);
    }
}
