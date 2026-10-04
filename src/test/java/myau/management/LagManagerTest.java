package myau.management;

import myau.module.modules.TestLagHolderModule;
import myau.util.ActionLedger;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** LagManager: F-28 (flush race) and F-11 (holder name), 2026-09-28. */
public class LagManagerTest {

    public static final class Numbered implements Packet<INetHandler> {
        final int n;

        Numbered(int n) {
            this.n = n;
        }

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

    /** Records what would have gone on the wire, and widens race windows. */
    static class Recording extends LagManager {
        final List<Integer> sent = Collections.synchronizedList(new ArrayList<Integer>());
        final AtomicBoolean otherThreadSawFlushing = new AtomicBoolean(false);
        volatile boolean probeOtherThread;

        @Override
        protected boolean connected() {
            return true;
        }

        @Override
        protected void send(Packet<?> packet) {
            if (packet instanceof Numbered) {
                this.sent.add(((Numbered) packet).n);
            }
            if (this.probeOtherThread) {
                this.probeOtherThread = false;
                Thread other = new Thread(() -> this.otherThreadSawFlushing.set(isFlushing()));
                other.start();
                try {
                    other.join(2000L);
                } catch (InterruptedException ignored) {
                }
            }
            Thread.yield();
        }
    }

    @Before
    public void reset() {
        ActionLedger.clear();
        Arbiter.setCatching("LagManagerTest", false);
    }

    @After
    public void tearDown() {
        Arbiter.setCatching("LagManagerTest", false);
    }

    private static List<Integer> range(int count) {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) {
            list.add(i);
        }
        return list;
    }

    @Test
    public void heldWhileDelayedAndReleasedInOrder() {
        Recording lag = new Recording();
        lag.setDelay(3);
        for (int i = 0; i < 5; i++) {
            assertTrue("held", lag.handlePacket(new Numbered(i)));
        }
        assertEquals(5, lag.packetQueue.size());
        assertTrue(lag.sent.isEmpty());
        lag.setDelay(0);
        assertFalse(lag.handlePacket(new C00PacketKeepAlive()));
        assertEquals(range(5), lag.sent);
        assertTrue(lag.packetQueue.isEmpty());
    }

    @Test
    public void keepAliveIsNeverHeld() {
        Recording lag = new Recording();
        lag.setDelay(5);
        assertFalse(lag.handlePacket(new C00PacketKeepAlive()));
        assertTrue(lag.packetQueue.isEmpty());
    }

    @Test
    public void concurrentFlushesSendEachPacketExactlyOnceInOrder() throws Exception {
        for (int round = 0; round < 20; round++) {
            Recording lag = new Recording();
            lag.setDelay(5);
            int count = 400;
            for (int i = 0; i < count; i++) {
                lag.handlePacket(new Numbered(i));
            }
            lag.setDelay(0);
            /* Two threads flushing at once: the client thread's tick and the
               network thread answering a keep-alive. */
            CountDownLatch start = new CountDownLatch(1);
            Runnable flusher = () -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                for (int n = 0; n < 50; n++) {
                    lag.handlePacket(new C00PacketKeepAlive());
                }
            };
            Thread a = new Thread(flusher, "client-like");
            Thread b = new Thread(flusher, "netty-like");
            a.start();
            b.start();
            start.countDown();
            a.join(10000L);
            b.join(10000L);
            Set<Integer> unique = new HashSet<Integer>(lag.sent);
            assertEquals("round " + round + ": no packet twice", lag.sent.size(), unique.size());
            assertEquals("round " + round + ": all sent, in order", range(count), lag.sent);
        }
    }

    @Test
    public void isFlushingOnlyOnTheFlushingThread() {
        Recording lag = new Recording();
        lag.setDelay(2);
        lag.handlePacket(new Numbered(1));
        lag.setDelay(0);
        lag.probeOtherThread = true;
        AtomicReference<Boolean> selfSaw = new AtomicReference<Boolean>();
        Recording probe = new Recording() {
            @Override
            protected void send(Packet<?> packet) {
                selfSaw.set(isFlushing());
            }
        };
        probe.setDelay(2);
        probe.handlePacket(new Numbered(2));
        probe.setDelay(0);
        probe.handlePacket(new C00PacketKeepAlive());
        assertEquals(Boolean.TRUE, selfSaw.get());
        assertFalse(probe.isFlushing());

        lag.handlePacket(new C00PacketKeepAlive());
        assertFalse("another thread must not see this flush as its own", lag.otherThreadSawFlushing.get());
    }

    @Test
    public void catchReleasesEverythingWithoutTouchingTheDelay() {
        Recording lag = new Recording();
        lag.setDelay(4);
        for (int i = 0; i < 3; i++) {
            lag.handlePacket(new Numbered(i));
        }
        Arbiter.setCatching("LagManagerTest", true);
        assertFalse(lag.handlePacket(new Numbered(99)));
        assertEquals(range(3), lag.sent);
        assertEquals(4, lag.getDelay());
    }

    @Test
    public void holderIsTheModuleThatSetTheDelay() {
        Recording lag = new Recording();
        TestLagHolderModule.hold(lag, 3);
        assertEquals("TestLagHolderModule", lag.holder());
        lag.handlePacket(new Numbered(1));
        assertEquals("held-send", ActionLedger.kindsFor("TestLagHolderModule", 5000L));
    }

    @Test
    public void holderUnknownOutsideAModule() {
        Recording lag = new Recording();
        lag.setDelay(3);
        assertEquals("LagManager", lag.holder());
    }

    @Test
    public void disconnectedDropsTheQueue() {
        LagManager lag = new LagManager() {
            @Override
            protected boolean connected() {
                return false;
            }
        };
        lag.setDelay(3);
        lag.handlePacket(new Numbered(1));
        /* Held like any other; dropped at the next flush, which finds no
           connection to send it on (unchanged behaviour). */
        assertEquals(1, lag.packetQueue.size());
        lag.handlePacket(new C00PacketKeepAlive());
        assertTrue(lag.packetQueue.isEmpty());
    }
}
