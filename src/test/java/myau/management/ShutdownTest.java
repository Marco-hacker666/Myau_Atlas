package myau.management;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The single ordered exit (F-06/F-07/F-26, 2026-09-28). */
public class ShutdownTest {

    private final List<String> ran = new ArrayList<String>();

    @Before
    public void reset() {
        Shutdown.resetForTests();
    }

    @After
    public void clear() {
        Shutdown.resetForTests();
    }

    @Test
    public void stagesRunInOrderWhateverTheRegistrationOrder() {
        Shutdown.register(Shutdown.Stage.FLUSH_LOGS, "logs", () -> this.ran.add("logs"));
        Shutdown.register(Shutdown.Stage.SAVE_STATE, "brain", () -> this.ran.add("brain"));
        Shutdown.register(Shutdown.Stage.SAVE_CONFIG, "config", () -> this.ran.add("config"));
        Shutdown.register(Shutdown.Stage.RESTORE, "restore", () -> this.ran.add("restore"));
        Shutdown.run();
        assertEquals(Arrays.asList("restore", "config", "brain", "logs"), this.ran);
    }

    @Test
    public void withinAStageRegistrationOrderIsKept() {
        Shutdown.register(Shutdown.Stage.RESTORE, "a", () -> this.ran.add("a"));
        Shutdown.register(Shutdown.Stage.RESTORE, "b", () -> this.ran.add("b"));
        Shutdown.register(Shutdown.Stage.RESTORE, "c", () -> this.ran.add("c"));
        Shutdown.run();
        assertEquals(Arrays.asList("a", "b", "c"), this.ran);
    }

    @Test
    public void aFailingTaskDoesNotStopTheRest() {
        Shutdown.register(Shutdown.Stage.RESTORE, "boom", () -> {
            throw new IllegalStateException("expected by the test");
        });
        Shutdown.register(Shutdown.Stage.RESTORE, "after", () -> this.ran.add("after"));
        Shutdown.register(Shutdown.Stage.SAVE_CONFIG, "config", () -> this.ran.add("config"));
        Shutdown.run();
        assertEquals(Arrays.asList("after", "config"), this.ran);
    }

    @Test
    public void runsOnlyOnce() {
        Shutdown.register(Shutdown.Stage.SAVE_CONFIG, "config", () -> this.ran.add("config"));
        Shutdown.run();
        Shutdown.run();
        assertEquals(Arrays.asList("config"), this.ran);
    }
}
