package myau.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** AsyncLog: order, failure isolation, the exit drain (F-29, 2026-09-28). */
public class AsyncLogTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<String> read(File file) throws Exception {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    public void aBurstIsWrittenCompletelyAndInOrderByTheDrain() throws Exception {
        File file = new File(this.folder.getRoot(), "burst.txt");
        List<String> expected = new ArrayList<String>();
        for (int i = 0; i < 3000; i++) {
            expected.add("line " + i);
            AsyncLog.append(file, "line " + i);
        }
        AsyncLog.drain();
        assertEquals(expected, read(file));
        assertEquals(0, AsyncLog.queued());
    }

    @Test
    public void aFileThatCannotBeWrittenDoesNotStopTheOthers() throws Exception {
        File directory = this.folder.newFolder("not-a-file");
        File good = new File(this.folder.getRoot(), "good.txt");
        long failedBefore = AsyncLog.failed();
        AsyncLog.append(directory, "cannot be written: it is a directory");
        AsyncLog.append(good, "still written");
        AsyncLog.drain();
        assertEquals(1, read(good).size());
        assertTrue(AsyncLog.failed() > failedBefore);
    }

    @Test
    public void theWorkerStartsAgainAfterADrain() throws Exception {
        File file = new File(this.folder.getRoot(), "again.txt");
        AsyncLog.append(file, "before");
        AsyncLog.drain();
        AsyncLog.append(file, "after");
        long until = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < until && read(file).size() < 2) {
            Thread.sleep(20L);
        }
        assertEquals(2, read(file).size());
    }

    @Test
    public void emptyAndNullAreIgnored() {
        AsyncLog.append(null, "nowhere");
        AsyncLog.append(new File(this.folder.getRoot(), "x.txt"), new ArrayList<String>());
        assertEquals(0, AsyncLog.queued());
    }
}
