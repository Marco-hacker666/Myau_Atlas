package myau.event;

import myau.event.events.EventStoppable;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.ActionLedger;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Characterisation tests for EventManager (Phase 1, docs/ARCH-AUDIT-2026-09-28.md).
 *
 * These pin down what the dispatcher does today, including the parts that are
 * surprising (a cancelled event is still delivered to later handlers; dispatch
 * is by exact class). They exist so that a later change to the dispatcher that
 * alters any of it fails here first, on purpose, rather than in a game.
 */
public class EventManagerTest {

    /** Shared call record; every handler appends "<who>" when it runs. */
    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void reset() {
        EventManager.clearForTests();
        ActionLedger.clear();
        CALLS.clear();
    }

    // ------------------------------------------------------------ fixtures

    /** A module with one plain handler. */
    public static class PlainModule extends Module {
        public PlainModule(String name, boolean enabled) {
            super(name, enabled);
        }

        @EventTarget
        public void onTick(TickEvent event) {
            CALLS.add(getName());
        }
    }

    /** A module whose handler runs while it is switched off. */
    public static class AlwaysModule extends Module {
        public AlwaysModule(String name, boolean enabled) {
            super(name, enabled);
        }

        @EventTarget(whenDisabled = true)
        public void onTick(TickEvent event) {
            CALLS.add(getName());
        }
    }

    /** Not a module: never filtered. */
    public static class Listener {
        final String name;

        Listener(String name) {
            this.name = name;
        }

        @EventTarget
        public void onTick(TickEvent event) {
            CALLS.add(this.name);
        }
    }

    public static class PriorityListener {
        @EventTarget(Priority.LOWEST)
        public void lowest(TickEvent event) {
            CALLS.add("LOWEST");
        }

        @EventTarget(Priority.HIGH)
        public void high(TickEvent event) {
            CALLS.add("HIGH");
        }

        @EventTarget(Priority.HIGHEST)
        public void highest(TickEvent event) {
            CALLS.add("HIGHEST");
        }

        @EventTarget(Priority.LOW)
        public void low(TickEvent event) {
            CALLS.add("LOW");
        }

        @EventTarget
        public void medium(TickEvent event) {
            CALLS.add("MEDIUM");
        }
    }

    public static class Canceller {
        @EventTarget(Priority.HIGH)
        public void cancel(TickEvent event) {
            event.setCancelled(true);
            CALLS.add("cancel");
        }
    }

    public static class CancelWatcher {
        final List<Boolean> seen = new ArrayList<Boolean>();

        @EventTarget(Priority.LOW)
        public void watch(TickEvent event) {
            this.seen.add(event.isCancelled());
            CALLS.add("watch");
        }
    }

    public static class Thrower {
        @EventTarget(Priority.HIGH)
        public void boom(TickEvent event) {
            CALLS.add("boom");
            throw new IllegalStateException("handler failure (expected by the test)");
        }
    }

    /** Disables another module from inside a handler that runs first. */
    public static class Disabler {
        final Module victim;

        Disabler(Module victim) {
            this.victim = victim;
        }

        @EventTarget(Priority.HIGHEST)
        public void disable(TickEvent event) {
            this.victim.setEnabled(false);
            CALLS.add("disabler");
        }
    }

    public static class Stoppable extends EventStoppable {
    }

    public static class StoppableListener {
        @EventTarget(Priority.HIGH)
        public void first(Stoppable event) {
            CALLS.add("first");
            event.stop();
        }

        @EventTarget(Priority.LOW)
        public void second(Stoppable event) {
            CALLS.add("second");
        }
    }

    /** A subclass of TickEvent. */
    public static class SubTick extends TickEvent {
        public SubTick() {
            super(EventType.PRE);
        }
    }

    public static class SlowListener {
        @EventTarget
        public void slow(TickEvent event) {
            long until = System.nanoTime() + 2_000_000L;
            while (System.nanoTime() < until) {
                // busy for ~2 ms, so the profile has something to report
            }
            CALLS.add("slow");
        }
    }

    public static class ThreadRecorder {
        final AtomicReference<Thread> thread = new AtomicReference<Thread>();

        @EventTarget
        public void onTick(TickEvent event) {
            this.thread.set(Thread.currentThread());
        }
    }

    /** A packet no list knows about: its cancellation must count. */
    public static class TestPacket implements Packet<INetHandler> {
        @Override
        public void readPacketData(PacketBuffer buf) {
        }

        @Override
        public void writePacketData(PacketBuffer buf) {
        }

        @Override
        public void processPacket(INetHandler handler) {
        }
    }

    public static class PacketHolder extends Module {
        public PacketHolder() {
            super("PacketHolder", true);
        }

        @EventTarget
        public void onPacket(PacketEvent event) {
            event.setCancelled(true);
        }
    }

    public static class PacketHolderNotModule {
        @EventTarget
        public void onPacket(PacketEvent event) {
            event.setCancelled(true);
        }
    }

    private static TickEvent tick() {
        return new TickEvent(EventType.PRE);
    }

    // --------------------------------------------------------------- tests

    @Test
    public void enabledModuleHandlerRuns() {
        EventManager.register(new PlainModule("A", true));
        EventManager.call(tick());
        assertEquals(Collections.singletonList("A"), CALLS);
    }

    @Test
    public void disabledModuleHandlerIsSkipped() {
        EventManager.register(new PlainModule("A", false));
        EventManager.call(tick());
        assertTrue(CALLS.isEmpty());
    }

    @Test
    public void toggledModuleIsFilteredAtDispatchTime() {
        PlainModule module = new PlainModule("A", false);
        EventManager.register(module);
        EventManager.call(tick());
        module.setEnabled(true);
        EventManager.call(tick());
        module.setEnabled(false);
        EventManager.call(tick());
        assertEquals(Collections.singletonList("A"), CALLS);
    }

    @Test
    public void whenDisabledHandlerRunsWhileOff() {
        EventManager.register(new AlwaysModule("W", false));
        EventManager.call(tick());
        assertEquals(Collections.singletonList("W"), CALLS);
    }

    @Test
    public void nonModuleListenerIsNeverFiltered() {
        EventManager.register(new Listener("L"));
        EventManager.call(tick());
        assertEquals(Collections.singletonList("L"), CALLS);
    }

    @Test
    public void multipleListenersAllRun() {
        EventManager.register(new Listener("L1"));
        EventManager.register(new PlainModule("M1", true));
        EventManager.register(new Listener("L2"));
        EventManager.call(tick());
        assertEquals(3, CALLS.size());
        assertTrue(CALLS.containsAll(java.util.Arrays.asList("L1", "M1", "L2")));
    }

    @Test
    public void priorityOrdersHighestToLowest() {
        EventManager.register(new PriorityListener());
        EventManager.call(tick());
        assertEquals(java.util.Arrays.asList("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"), CALLS);
    }

    @Test
    public void samePriorityKeepsRegistrationOrderAcrossObjects() {
        EventManager.register(new Listener("first"));
        EventManager.register(new Listener("second"));
        EventManager.register(new Listener("third"));
        EventManager.call(tick());
        assertEquals(java.util.Arrays.asList("first", "second", "third"), CALLS);
    }

    @Test
    public void cancelledEventIsStillDeliveredToLaterHandlers() {
        /* Documented semantic, not a bug to fix silently: cancelling does not
           stop dispatch. Handlers that must ignore cancelled events check
           isCancelled() themselves. */
        CancelWatcher watcher = new CancelWatcher();
        EventManager.register(watcher);
        EventManager.register(new Canceller());
        TickEvent event = tick();
        EventManager.call(event);
        assertEquals(java.util.Arrays.asList("cancel", "watch"), CALLS);
        assertEquals(Collections.singletonList(Boolean.TRUE), watcher.seen);
        assertTrue(event.isCancelled());
    }

    @Test
    public void callReturnsTheSameEvent() {
        TickEvent event = tick();
        assertSame(event, EventManager.call(event));
    }

    @Test
    public void eventWithNoListenersIsHarmless() {
        TickEvent event = tick();
        assertSame(event, EventManager.call(event));
        assertFalse(event.isCancelled());
    }

    @Test
    public void throwingHandlerDoesNotStopLaterHandlers() {
        EventManager.register(new Thrower());
        EventManager.register(new CancelWatcher());
        EventManager.call(tick());
        assertEquals(java.util.Arrays.asList("boom", "watch"), CALLS);
    }

    @Test
    public void moduleDisabledByEarlierHandlerIsSkippedInSameDispatch() {
        PlainModule victim = new PlainModule("victim", true);
        EventManager.register(victim);
        EventManager.register(new Disabler(victim));
        EventManager.call(tick());
        assertEquals(Collections.singletonList("disabler"), CALLS);
    }

    @Test
    public void dispatchIsByExactClass() {
        /* A TickEvent handler does not see a subclass of TickEvent. */
        EventManager.register(new Listener("L"));
        EventManager.call(new SubTick());
        assertTrue(CALLS.isEmpty());
    }

    @Test
    public void stoppableEventStopsDispatch() {
        EventManager.register(new StoppableListener());
        EventManager.call(new Stoppable());
        assertEquals(Collections.singletonList("first"), CALLS);
    }

    @Test
    public void registeringTwiceDoesNotDoubleTheHandler() {
        Listener listener = new Listener("L");
        EventManager.register(listener);
        EventManager.register(listener);
        EventManager.register(listener, TickEvent.class);
        EventManager.call(tick());
        assertEquals(Collections.singletonList("L"), CALLS);
    }

    @Test
    public void twoInstancesOfOneClassAreTwoListeners() {
        EventManager.register(new Listener("a"));
        EventManager.register(new Listener("b"));
        EventManager.call(tick());
        assertEquals(java.util.Arrays.asList("a", "b"), CALLS);
    }

    @Test
    public void unregisterRemovesHandlers() {
        Listener keep = new Listener("keep");
        Listener drop = new Listener("drop");
        EventManager.register(keep);
        EventManager.register(drop);
        EventManager.unregister(drop);
        EventManager.call(tick());
        assertEquals(Collections.singletonList("keep"), CALLS);
    }

    @Test
    public void cleanMapAllDoesNotThrowAndEmptiesTheRegistry() {
        EventManager.register(new Listener("L"));
        EventManager.cleanMap(false);
        EventManager.call(tick());
        assertTrue(CALLS.isEmpty());
    }

    @Test
    public void handlerTimingIsRecordedAndReset() {
        EventManager.register(new SlowListener());
        EventManager.takeProfile(1.0, 0); // discard anything earlier
        EventManager.call(tick());
        EventManager.call(tick());
        List<String> profile = EventManager.takeProfile(1.0, 10);
        String joined = String.join("\n", profile);
        assertTrue(joined, joined.contains("SlowListener.slow(TickEvent)"));
        assertTrue(joined, joined.contains("       2 calls"));
        /* Counters were reset by the take: nothing ran since. */
        List<String> again = EventManager.takeProfile(1.0, 10);
        assertFalse(String.join("\n", again).contains("SlowListener"));
    }

    @Test
    public void dispatchRunsOnTheCallersThread() throws Exception {
        ThreadRecorder recorder = new ThreadRecorder();
        EventManager.register(recorder);
        Thread other = new Thread(() -> EventManager.call(tick()), "not-the-client-thread");
        other.start();
        other.join(5000L);
        assertSame(other, recorder.thread.get());
    }

    @Test
    public void concurrentDispatchDeliversEveryEvent() throws Exception {
        EventManager.register(new Listener("L"));
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int n = 0; n < 500; n++) {
                    EventManager.call(tick());
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(10000L);
        }
        assertEquals(2000, CALLS.size());
    }

    @Test
    public void moduleCancellingAPacketIsNamedInTheLedger() {
        EventManager.register(new PacketHolder());
        PacketEvent event = new PacketEvent(EventType.SEND, new TestPacket());
        EventManager.call(event);
        assertTrue(event.isCancelled());
        Map<String, Integer> acting = ActionLedger.within(5000L);
        assertEquals(Integer.valueOf(1), acting.get("PacketHolder"));
        assertEquals("held-send", ActionLedger.kindsFor("PacketHolder", 5000L));
    }

    @Test
    public void cancellingACosmeticPacketIsNotEvidence() {
        EventManager.register(new PacketHolder());
        EventManager.call(new PacketEvent(EventType.RECEIVE, new S03PacketTimeUpdate()));
        assertTrue(ActionLedger.within(5000L).isEmpty());
    }

    @Test
    public void cancelByANonModuleIsNotAttributedByTheDispatcher() {
        EventManager.register(new PacketHolderNotModule());
        EventManager.call(new PacketEvent(EventType.SEND, new TestPacket()));
        assertTrue(ActionLedger.within(5000L).isEmpty());
    }

    @Test
    public void receivedPacketCancelIsNamedAsHeldRecv() {
        EventManager.register(new PacketHolder());
        EventManager.call(new PacketEvent(EventType.RECEIVE, new TestPacket()));
        assertEquals("held-recv", ActionLedger.kindsFor("PacketHolder", 5000L));
    }
}
