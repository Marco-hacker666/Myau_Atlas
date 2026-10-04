package myau.management;

import myau.enums.BlinkModules;
import myau.enums.DelayModules;
import myau.module.modules.TestLagHolderModule;
import myau.util.ActionLedger;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Packet hold leases (2026-09-28, after Rise's BlinkComponent). */
public class PacketHoldsLeaseTest {

    private static final Predicate<String> ALL_ON = name -> true;

    private static Predicate<String> off(String... names) {
        Set<String> off = new HashSet<String>(Arrays.asList(names));
        return name -> !off.contains(name);
    }

    /** A holder under test's control. */
    static final class FakeHolder {
        int count;
        long since;
        String owner = "Owner";
        long ceiling = -1L;
        int expired;
        RuntimeException failOnExpire;

        void register() {
            PacketHolds.register(() -> this.count == 0 ? null
                    : new PacketHolds.Hold("Fake", PacketHolds.Direction.OUT, this.count, this.since, "r", "q"),
                    new PacketHolds.Lease() {
                        @Override
                        public String owner() {
                            return owner;
                        }

                        @Override
                        public long ceilingMs() {
                            return ceiling;
                        }

                        @Override
                        public void expire() {
                            expired++;
                            if (failOnExpire != null) {
                                throw failOnExpire;
                            }
                            count = 0;
                        }
                    });
        }
    }

    @Before
    public void setUp() {
        PacketHolds.clearForTests();
        ActionLedger.clear();
        Arbiter.setCatching("PacketHoldsLeaseTest", false);
    }

    @After
    public void tearDown() {
        PacketHolds.clearForTests();
        Arbiter.setCatching("PacketHoldsLeaseTest", false);
    }

    // ------------------------------------------------------------ the rule

    @Test
    public void aHoldWithinItsLeaseIsLeftAlone() {
        FakeHolder holder = new FakeHolder();
        holder.count = 5;
        holder.since = 1000L;
        holder.ceiling = 500L;
        holder.register();
        assertTrue(PacketHolds.enforce(1500L, ALL_ON).isEmpty());
        assertEquals(0, holder.expired);
        assertEquals(5, PacketHolds.total());
        assertEquals(0, PacketHolds.expiries());
    }

    @Test
    public void aHoldForAModuleThatIsOffIsEnded() {
        FakeHolder holder = new FakeHolder();
        holder.count = 3;
        holder.since = 1000L;
        holder.register();
        List<PacketHolds.Expiry> ended = PacketHolds.enforce(1001L, off("Owner"));
        assertEquals(1, ended.size());
        assertEquals(1, holder.expired);
        assertEquals(0, PacketHolds.total());
        assertEquals("Fake", ended.get(0).holder);
        assertEquals(3, ended.get(0).count);
        assertTrue(ended.get(0).why, ended.get(0).why.contains("Owner, which is off"));
        assertEquals(1, PacketHolds.expiries());
        assertSame(ended.get(0), PacketHolds.lastExpiry());
    }

    @Test
    public void aHoldPastItsCeilingIsEnded() {
        FakeHolder holder = new FakeHolder();
        holder.count = 2;
        holder.since = 1000L;
        holder.ceiling = 500L;
        holder.register();
        assertTrue("at the ceiling: not yet", PacketHolds.enforce(1500L, ALL_ON).isEmpty());
        List<PacketHolds.Expiry> ended = PacketHolds.enforce(1501L, ALL_ON);
        assertEquals(1, ended.size());
        assertTrue(ended.get(0).why, ended.get(0).why.contains("501ms, longer than its 500ms ceiling"));
    }

    @Test
    public void noCeilingMeansOnlyTheOwnerCanEndIt() {
        FakeHolder holder = new FakeHolder();
        holder.count = 400;
        holder.since = 1L;
        holder.ceiling = -1L;
        holder.register();
        assertTrue(PacketHolds.enforce(10_000_000L, ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(10_000_000L, off("Owner")).size());
    }

    @Test
    public void anUnknownStartIsNeverTooOld() {
        FakeHolder holder = new FakeHolder();
        holder.count = 1;
        holder.since = 0L;
        holder.ceiling = 0L;
        holder.register();
        assertTrue(PacketHolds.enforce(10_000_000L, ALL_ON).isEmpty());
    }

    @Test
    public void nothingHeldIsNeverEnded() {
        FakeHolder holder = new FakeHolder();
        holder.count = 0;
        holder.register();
        assertTrue(PacketHolds.enforce(1L, off("Owner")).isEmpty());
        assertEquals(0, holder.expired);
    }

    @Test
    public void aHolderWithoutALeaseIsOnlyReported() {
        PacketHolds.register(() -> new PacketHolds.Hold("Plain", PacketHolds.Direction.IN, 9, 1L, "r", "q"));
        assertTrue(PacketHolds.enforce(10_000_000L, name -> false).isEmpty());
        assertEquals(9, PacketHolds.total());
    }

    @Test
    public void oneHolderFailingToLetGoDoesNotStopTheOthers() {
        FakeHolder broken = new FakeHolder();
        broken.count = 1;
        broken.since = 1L;
        broken.failOnExpire = new IllegalStateException("expected by the test");
        broken.register();
        FakeHolder fine = new FakeHolder();
        fine.count = 2;
        fine.since = 1L;
        fine.register();
        List<PacketHolds.Expiry> ended = PacketHolds.enforce(2L, off("Owner"));
        assertEquals(2, ended.size());
        assertTrue(ended.get(0).why, ended.get(0).why.contains("ending it failed"));
        assertEquals(1, fine.expired);
        assertEquals(0, fine.count);
        assertEquals(1, PacketHolds.failures());
    }

    @Test
    public void aLeaseThatCannotAnswerIsLeftAlone() {
        AtomicInteger expired = new AtomicInteger();
        PacketHolds.register(() -> new PacketHolds.Hold("Odd", PacketHolds.Direction.OUT, 1, 1L, "r", "q"),
                new PacketHolds.Lease() {
                    @Override
                    public String owner() {
                        throw new IllegalStateException("expected by the test");
                    }

                    @Override
                    public long ceilingMs() {
                        return 0L;
                    }

                    @Override
                    public void expire() {
                        expired.incrementAndGet();
                    }
                });
        assertTrue(PacketHolds.enforce(10_000L, name -> false).isEmpty());
        assertEquals(0, expired.get());
        assertEquals(1, PacketHolds.failures());
    }

    @Test
    public void anEndedHoldIsNotEndedTwice() {
        FakeHolder holder = new FakeHolder();
        holder.count = 4;
        holder.since = 1L;
        holder.register();
        assertEquals(1, PacketHolds.enforce(2L, off("Owner")).size());
        assertTrue(PacketHolds.enforce(3L, off("Owner")).isEmpty());
        assertEquals(1, holder.expired);
    }

    // ------------------------------------------------------------ the holders

    @Test
    public void blinkLetsGoInOrderWhenItsOwnerIsOff() throws Exception {
        BlinkManagerTest.Recording blink = new BlinkManagerTest.Recording();
        assertTrue(blink.setBlinkState(true, BlinkModules.AUTO_BLOCK));
        for (int i = 1; i <= 3; i++) {
            offerOnClientThread(blink, new BlinkManagerTest.Numbered(i));
        }
        assertTrue("KillAura on: kept", PacketHolds.enforce(System.currentTimeMillis(), ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(System.currentTimeMillis(), off("KillAura")).size());
        assertEquals(Arrays.asList(1, 2, 3), blink.sent);
        assertEquals(BlinkModules.NONE, blink.getBlinkingModule());
        assertFalse(blink.isBlinking());
        String kinds = ActionLedger.kindsFor("KillAura", 5000L);
        assertTrue("the release is on the ledger like any other: " + kinds, kinds.contains("release"));
    }

    @Test
    public void blinkLetsGoPastItsOwnersCeiling() throws Exception {
        BlinkManagerTest.Recording blink = new BlinkManagerTest.Recording();
        blink.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        offerOnClientThread(blink, new BlinkManagerTest.Numbered(1));
        long start = PacketHolds.snapshot().get(0).since;
        long ceiling = BlinkModules.AUTO_BLOCK.leaseMs();
        assertTrue(PacketHolds.enforce(start + ceiling, ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(start + ceiling + 1L, ALL_ON).size());
        assertEquals(Collections.singletonList(1), blink.sent);
    }

    @Test
    public void thePlayersOwnBlinkHasNoCeiling() throws Exception {
        BlinkManagerTest.Recording blink = new BlinkManagerTest.Recording();
        blink.setBlinkState(true, BlinkModules.BLINK);
        offerOnClientThread(blink, new BlinkManagerTest.Numbered(1));
        assertTrue(PacketHolds.enforce(System.currentTimeMillis() + 3_600_000L, ALL_ON).isEmpty());
        assertEquals(BlinkModules.BLINK, blink.getBlinkingModule());
        assertEquals(1, PacketHolds.enforce(System.currentTimeMillis(), off("Blink")).size());
    }

    @Test
    public void afterALeaseTheOwnerCanBlinkAgain() throws Exception {
        BlinkManagerTest.Recording blink = new BlinkManagerTest.Recording();
        blink.setBlinkState(true, BlinkModules.NO_FALL);
        offerOnClientThread(blink, new BlinkManagerTest.Numbered(1));
        PacketHolds.enforce(System.currentTimeMillis(), off("NoFall"));
        /* Its own late setBlinkState(false) is harmless: it no longer holds. */
        assertFalse(blink.setBlinkState(false, BlinkModules.NO_FALL));
        assertTrue(blink.setBlinkState(true, BlinkModules.SCAFFOLD));
        assertEquals(BlinkModules.SCAFFOLD, blink.getBlinkingModule());
    }

    @Test
    public void lagLetsGoWhenTheModuleThatSetTheDelayIsOff() {
        LagManagerTest.Recording lag = new LagManagerTest.Recording();
        TestLagHolderModule.hold(lag, 20);
        lag.handlePacket(new LagManagerTest.Numbered(1));
        lag.handlePacket(new LagManagerTest.Numbered(2));
        assertTrue(PacketHolds.enforce(System.currentTimeMillis(), ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(System.currentTimeMillis(), off("TestLagHolderModule")).size());
        assertEquals(Arrays.asList(1, 2), lag.sent);
        assertEquals("the delay goes with the hold", 0, lag.getDelay());
        assertFalse("nothing more is held", lag.handlePacket(new LagManagerTest.Numbered(3)));
    }

    @Test
    public void lagLetsGoWhenItsFlushStopped() {
        LagManagerTest.Recording lag = new LagManagerTest.Recording();
        TestLagHolderModule.hold(lag, 2);
        lag.handlePacket(new LagManagerTest.Numbered(1));
        long since = PacketHolds.snapshot().get(0).since;
        /* Two ticks is 100ms; nothing flushed it (no tick ran). */
        assertTrue(PacketHolds.enforce(since + 100L + PacketHolds.LEASE_MARGIN_MS, ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(since + 101L + PacketHolds.LEASE_MARGIN_MS, ALL_ON).size());
        assertEquals(Collections.singletonList(1), lag.sent);
    }

    @Test
    public void lagWithNoKnownHolderIsOnlyAged() {
        LagManagerTest.Recording lag = new LagManagerTest.Recording();
        lag.setDelay(2);
        lag.handlePacket(new LagManagerTest.Numbered(1));
        assertEquals("LagManager", lag.holder());
        assertTrue("no module to ask about", PacketHolds.enforce(System.currentTimeMillis(), name -> false).isEmpty());
    }

    @Test
    public void delayLetsGoWhenBedNukerIsOff() {
        DelayManager delay = new DelayManager();
        delay.delay(DelayModules.BED_NUKER);
        @SuppressWarnings("unchecked")
        Packet<INetHandlerPlayClient> plain =
                (Packet<INetHandlerPlayClient>) (Packet<?>) new PacketHoldsTest.P();
        assertTrue(delay.shouldDelay(plain));
        assertTrue("no ceiling: a bed takes as long as it takes",
                PacketHolds.enforce(System.currentTimeMillis() + 3_600_000L, ALL_ON).isEmpty());
        assertEquals(1, PacketHolds.enforce(System.currentTimeMillis(), off("BedNuker")).size());
        assertEquals(0, PacketHolds.total());
        assertEquals(DelayModules.NONE, delay.getDelayModule());
    }

    @Test
    public void everyBlinkOwnerHasAModuleAndACeiling() {
        for (BlinkModules module : BlinkModules.values()) {
            assertNotNull(module.moduleName());
            if (module == BlinkModules.BLINK) {
                assertTrue(module.leaseMs() < 0L);
            } else {
                assertTrue(module + " must be bounded", module.leaseMs() >= 0L);
            }
        }
    }

    private static void offerOnClientThread(BlinkManager blink, Packet<?> packet) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        Thread thread = new Thread(() -> result.set(blink.offerPacket(packet)), "Client thread");
        thread.start();
        thread.join(5000L);
        assertTrue("held", result.get());
    }
}
