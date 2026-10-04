package myau.management;

import myau.enums.BlinkModules;
import myau.util.ActionLedger;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** BlinkManager ownership (F-32, 2026-09-28): first come, first served. */
public class BlinkManagerTest {

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

    static class Recording extends BlinkManager {
        final List<Integer> sent = new ArrayList<Integer>();

        @Override
        protected boolean connected() {
            return true;
        }

        @Override
        protected void send(Packet<?> packet) {
            if (packet instanceof Numbered) {
                this.sent.add(((Numbered) packet).n);
            }
        }
    }

    @Before
    public void reset() {
        ActionLedger.clear();
        Arbiter.setCatching("BlinkManagerTest", false);
    }

    @After
    public void tearDown() {
        Arbiter.setCatching("BlinkManagerTest", false);
        BlinkManager.holdTransactions = () -> false;
    }

    /** Records every packet sent, by kind: "C0F" or the Numbered's number. */
    static class RecordingAll extends BlinkManager {
        final List<String> sent = new ArrayList<String>();

        @Override
        protected boolean connected() {
            return true;
        }

        @Override
        protected void send(Packet<?> packet) {
            this.sent.add(packet instanceof Numbered ? String.valueOf(((Numbered) packet).n)
                    : packet.getClass().getSimpleName().startsWith("C0F") ? "C0F" : "?");
        }
    }

    /** offerPacket only holds what the client thread sends. */
    private static boolean offerOnClientThread(BlinkManager blink, Packet<?> packet) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        Thread thread = new Thread(() -> result.set(blink.offerPacket(packet)), "Client thread");
        thread.start();
        thread.join(5000L);
        return result.get();
    }

    /* 2026-10-04: what is held on a world change belongs to the old world. */
    @Test
    public void aWorldChangeDropsTheHoldButSendsTheReplies() throws Exception {
        BlinkManager.holdTransactions = () -> true;
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        offerOnClientThread(blink, new Numbered(1));
        offerOnClientThread(blink, new net.minecraft.network.play.client.C0FPacketConfirmTransaction());
        offerOnClientThread(blink, new Numbered(2));
        blink.dropForWorldChange(true);
        assertEquals(Arrays.asList("C0F"), blink.sent);
        assertTrue(blink.blinkedPackets.isEmpty());
        assertEquals(BlinkModules.NONE, blink.getBlinkingModule());
        assertFalse(blink.isBlinking());
        /* The old owner's own release afterwards has nothing to send. */
        assertFalse(blink.setBlinkState(false, BlinkModules.BLINK));
        assertEquals(1, blink.sent.size());
    }

    @Test
    public void leavingDropsEverything() throws Exception {
        BlinkManager.holdTransactions = () -> true;
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        offerOnClientThread(blink, new Numbered(1));
        offerOnClientThread(blink, new net.minecraft.network.play.client.C0FPacketConfirmTransaction());
        blink.dropForWorldChange(false);
        assertTrue(blink.sent.isEmpty());
        assertTrue(blink.blinkedPackets.isEmpty());
    }

    @Test
    public void firstOwnerTakesTheBlink() {
        Recording blink = new Recording();
        assertTrue(blink.setBlinkState(true, BlinkModules.ANTI_VOID));
        assertEquals(BlinkModules.ANTI_VOID, blink.getBlinkingModule());
        assertTrue(blink.isBlinking());
    }

    @Test
    public void secondOwnerIsRefusedAndTheFirstKeepsItsPackets() throws Exception {
        Recording blink = new Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        assertTrue(offerOnClientThread(blink, new Numbered(1)));
        assertTrue(offerOnClientThread(blink, new Numbered(2)));

        assertFalse(blink.setBlinkState(true, BlinkModules.SCAFFOLD));
        assertEquals(BlinkModules.ANTI_VOID, blink.getBlinkingModule());
        assertEquals(1, blink.refusals());
        assertEquals("SCAFFOLD refused, ANTI_VOID holds", blink.lastRefusal());

        /* The refused module "releasing" does nothing to the owner's hold. */
        assertFalse(blink.setBlinkState(false, BlinkModules.SCAFFOLD));
        assertEquals(2, blink.blinkedPackets.size());
        assertTrue(blink.sent.isEmpty());

        /* The owner's release sends its packets, in order. */
        assertTrue(blink.setBlinkState(false, BlinkModules.ANTI_VOID));
        assertEquals(Arrays.asList(1, 2), blink.sent);
        assertEquals(BlinkModules.NONE, blink.getBlinkingModule());
        assertFalse(blink.isBlinking());
        assertTrue(blink.blinkedPackets.isEmpty());
    }

    @Test
    public void explicitTakeoverStillWorks() throws Exception {
        /* Blink, NoFall and Hitflick: release whoever holds, then take it. */
        Recording blink = new Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        offerOnClientThread(blink, new Numbered(7));
        blink.setBlinkState(false, blink.getBlinkingModule());
        assertEquals(Arrays.asList(7), blink.sent);
        assertTrue(blink.setBlinkState(true, BlinkModules.NO_FALL));
        assertEquals(BlinkModules.NO_FALL, blink.getBlinkingModule());
        assertEquals(0, blink.refusals());
    }

    @Test
    public void sameOwnerAskingAgainIsFine() {
        Recording blink = new Recording();
        assertTrue(blink.setBlinkState(true, BlinkModules.SCAFFOLD));
        assertTrue(blink.setBlinkState(true, BlinkModules.SCAFFOLD));
        assertEquals(0, blink.refusals());
    }

    @Test
    public void catchRefusesEveryone() {
        Recording blink = new Recording();
        Arbiter.setCatching("BlinkManagerTest", true);
        assertFalse(blink.setBlinkState(true, BlinkModules.ANTI_VOID));
        assertEquals(BlinkModules.NONE, blink.getBlinkingModule());
    }

    @Test
    public void noneIsNeverAnOwner() {
        Recording blink = new Recording();
        assertFalse(blink.setBlinkState(true, BlinkModules.NONE));
        assertFalse(blink.isBlinking());
    }

    @Test
    public void heldPacketsAreNamedToTheOwner() throws Exception {
        Recording blink = new Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        offerOnClientThread(blink, new Numbered(1));
        assertEquals("held-send", ActionLedger.kindsFor("AntiVoid", 5000L));
    }

    @Test
    public void repliesAreNeverHeld() throws Exception {
        Recording blink = new Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        assertFalse(offerOnClientThread(blink, new C00PacketKeepAlive()));
        assertFalse(offerOnClientThread(blink, new C0FPacketConfirmTransaction()));
        assertTrue(blink.blinkedPackets.isEmpty());
    }

    @Test
    public void networkThreadSendsAreNeverHeld() {
        Recording blink = new Recording();
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        /* This test thread is not named "Client thread". */
        assertFalse(blink.offerPacket(new Numbered(1)));
    }

    @Test
    public void releaseWithoutAConnectionDropsThePackets() throws Exception {
        BlinkManager blink = new BlinkManager() {
            @Override
            protected boolean connected() {
                return false;
            }
        };
        blink.setBlinkState(true, BlinkModules.ANTI_VOID);
        offerOnClientThread(blink, new Numbered(1));
        assertTrue(blink.setBlinkState(false, BlinkModules.ANTI_VOID));
        assertTrue(blink.blinkedPackets.isEmpty());
    }

    /* hold-transactions (2026-10-02): Grim reads replies flowing during a hold as Timer. */

    @Test
    public void transactionsAreHeldInOrderWhenAsked() throws Exception {
        BlinkManager.holdTransactions = () -> true;
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        assertTrue(offerOnClientThread(blink, new Numbered(1)));
        /* From this test thread too: a reply must not overtake one in the queue. */
        assertTrue(blink.offerPacket(new C0FPacketConfirmTransaction()));
        assertTrue(offerOnClientThread(blink, new Numbered(2)));
        blink.setBlinkState(false, BlinkModules.BLINK);
        assertEquals(Arrays.asList("1", "C0F", "2"), blink.sent);
    }

    @Test
    public void keepAlivesStayUnheldEvenWithTransactionsHeld() throws Exception {
        BlinkManager.holdTransactions = () -> true;
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        assertFalse(offerOnClientThread(blink, new C00PacketKeepAlive()));
    }

    @Test
    public void discardDropsMovementButSendsTheReplies() throws Exception {
        BlinkManager.holdTransactions = () -> true;
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        offerOnClientThread(blink, new Numbered(1));
        blink.offerPacket(new C0FPacketConfirmTransaction());
        offerOnClientThread(blink, new Numbered(2));
        blink.offerPacket(new C0FPacketConfirmTransaction());
        blink.discardHeld();
        assertEquals(Arrays.asList("C0F", "C0F"), blink.sent);
        assertTrue(blink.blinkedPackets.isEmpty());
    }

    @Test
    public void discardWithoutHeldRepliesIsAPlainClear() throws Exception {
        RecordingAll blink = new RecordingAll();
        blink.setBlinkState(true, BlinkModules.BLINK);
        offerOnClientThread(blink, new Numbered(1));
        assertFalse(offerOnClientThread(blink, new C0FPacketConfirmTransaction()));
        blink.discardHeld();
        assertTrue(blink.sent.isEmpty());
        assertTrue(blink.blinkedPackets.isEmpty());
    }
}
