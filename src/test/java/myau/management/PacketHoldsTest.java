package myau.management;

import myau.enums.BlinkModules;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** The packet-holder registry (plan step 13, 2026-09-28). */
public class PacketHoldsTest {

    public static final class P implements Packet<INetHandler> {
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

    @Before
    public void setUp() {
        PacketHolds.clearForTests();
        Arbiter.setCatching("PacketHoldsTest", false);
    }

    @After
    public void tearDown() {
        PacketHolds.clearForTests();
        Arbiter.setCatching("PacketHoldsTest", false);
    }

    @Test
    public void nothingHeldIsNothingReported() {
        PacketHolds.register(() -> null);
        PacketHolds.register(() -> new PacketHolds.Hold("Empty", PacketHolds.Direction.IN, 0, 0L, "r", "q"));
        assertTrue(PacketHolds.snapshot().isEmpty());
        assertEquals("", PacketHolds.describe());
        assertEquals(0, PacketHolds.total());
    }

    @Test
    public void everyHoldSaysWhoWhatSinceAndUntil() {
        long since = System.currentTimeMillis() - 850L;
        PacketHolds.register(() -> new PacketHolds.Hold("Blink/AntiVoid", PacketHolds.Direction.OUT, 12, since,
                "blink", "until AntiVoid ends it"));
        String line = PacketHolds.describe();
        assertTrue(line, line.startsWith("Blink/AntiVoid out 12 for "));
        assertTrue(line, line.contains("(blink; until AntiVoid ends it)"));
    }

    @Test
    public void aFailingSourceIsSkipped() {
        PacketHolds.register(() -> {
            throw new IllegalStateException("expected by the test");
        });
        PacketHolds.register(() -> new PacketHolds.Hold("Backtrack", PacketHolds.Direction.IN, 3, 0L, "r", "q"));
        assertEquals(1, PacketHolds.snapshot().size());
        assertEquals(3, PacketHolds.total());
    }

    @Test
    public void theManagersReportTheirQueues() throws Exception {
        LagManagerTest.Recording lag = new LagManagerTest.Recording();
        lag.setDelay(4);
        lag.handlePacket(new P());
        lag.handlePacket(new P());

        BlinkManagerTest.Recording blink = new BlinkManagerTest.Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        AtomicBoolean held = new AtomicBoolean();
        Thread client = new Thread(() -> held.set(blink.offerPacket(new P())), "Client thread");
        client.start();
        client.join(5000L);
        assertTrue(held.get());

        List<PacketHolds.Hold> holds = PacketHolds.snapshot();
        assertEquals(2, holds.size());
        assertEquals(2, holds.get(0).count);
        assertEquals(PacketHolds.Direction.OUT, holds.get(0).direction);
        assertTrue(holds.get(0).since > 0L);
        assertEquals("Blink/AntiVoid", holds.get(1).holder);
        assertEquals(1, holds.get(1).count);
    }

    @Test
    public void delayManagerLetsGoForACatch() {
        DelayManager delay = new DelayManager();
        delay.delay(myau.enums.DelayModules.BED_NUKER);
        /* Before a catch: held. (A world-render or keep-alive packet would pass;
           a plain one is held.) */
        @SuppressWarnings("unchecked")
        Packet<net.minecraft.network.play.INetHandlerPlayClient> plain =
                (Packet<net.minecraft.network.play.INetHandlerPlayClient>) (Packet<?>) new P();
        assertTrue(delay.shouldDelay(plain));
        assertEquals(1, PacketHolds.total());
        Arbiter.setCatching("PacketHoldsTest", true);
        assertFalse("the catch releases what was held", delay.shouldDelay(plain));
        assertFalse("nothing is held during a catch", delay.shouldDelay(plain));
        assertEquals(0, PacketHolds.total());
    }
}
