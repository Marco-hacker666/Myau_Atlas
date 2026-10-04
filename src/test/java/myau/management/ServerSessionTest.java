package myau.management;

import myau.event.EventManager;
import myau.event.EventTarget;
import myau.events.SessionEvent;
import myau.util.ActionLedger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ServerSession lifecycle and isolation (Phase 2, 2026-09-28). */
public class ServerSessionTest {

    /** Records every session event as "TYPE key changed?". */
    public static class Recorder {
        final List<String> events = new ArrayList<String>();
        final List<SessionEvent> raw = new ArrayList<SessionEvent>();

        @EventTarget
        public void onSession(SessionEvent event) {
            this.raw.add(event);
            this.events.add(event.getType() + " " + event.getSession().serverKey()
                    + (event.serverChanged() ? " changed" : ""));
        }
    }

    private Recorder recorder;
    private ServerSession sessions;
    private HitTimer timer;

    @Before
    public void setUp() {
        this.recorder = new Recorder();
        this.sessions = new ServerSession();
        this.timer = new HitTimer();
        EventManager.register(this.recorder);
        EventManager.register(this.timer);
        ActionLedger.clear();
    }

    @After
    public void tearDown() {
        EventManager.unregister(this.recorder);
        EventManager.unregister(this.timer);
    }

    private List<String> events() {
        return this.recorder.events;
    }

    @Test
    public void keyIsTheTypedAddressNormalised() {
        assertEquals("mc.hypixel.net", ServerSession.keyFor("MC.Hypixel.net"));
        assertEquals("mc.hypixel.net", ServerSession.keyFor(" mc.hypixel.net:25565 "));
        assertEquals("mc.hypixel.net", ServerSession.keyFor("mc.hypixel.net."));
        assertEquals("play.example.net:25566", ServerSession.keyFor("play.example.net:25566"));
        assertEquals("hypixel.net", ServerSession.keyFor("hypixel.net"));
        assertEquals(ServerSession.SINGLEPLAYER, ServerSession.keyFor(null));
        assertEquals(ServerSession.SINGLEPLAYER, ServerSession.keyFor("  "));
    }

    @Test
    public void connectLoadDisconnect() {
        assertNull(ServerSession.current());
        this.sessions.joined("play.pika-network.net");
        this.sessions.worldLoaded(true);
        ServerSession.Session session = ServerSession.current();
        assertNotNull(session);
        assertEquals(1L, session.id());
        assertEquals("play.pika-network.net", session.serverKey());
        assertTrue(session.isOpen());
        assertEquals(1, session.worlds());

        this.sessions.worldLoaded(false);
        assertNull(ServerSession.current());
        assertFalse(session.isOpen());
        assertNull("not connected: no current server", ServerSession.currentServerKey());
        assertEquals("play.pika-network.net", ServerSession.lastServerKey());
        assertEquals(java.util.Arrays.asList(
                "START play.pika-network.net changed",
                "WORLD play.pika-network.net",
                "END play.pika-network.net"), events());
    }

    @Test
    public void proxyHopIsTheSameSession() {
        this.sessions.joined("mc.hypixel.net");
        this.sessions.worldLoaded(true);
        this.sessions.joined("mc.hypixel.net");
        this.sessions.worldLoaded(true);
        ServerSession.Session session = ServerSession.current();
        assertEquals(1L, session.id());
        assertEquals(2, session.joins());
        assertEquals(2, session.worlds());
        assertEquals(1, this.recorder.raw.stream().filter(e -> e.getType() == SessionEvent.Type.START).count());
    }

    @Test
    public void unloadWithoutASessionDoesNothing() {
        /* GuiConnecting unloads the world before connecting. */
        this.sessions.worldLoaded(false);
        this.sessions.worldLoaded(true);
        assertTrue(events().isEmpty());
    }

    @Test
    public void serverAStateDoesNotReachServerB() {
        /* Connect A, generate state, disconnect, connect B. */
        this.sessions.joined("play.pika-network.net");
        this.timer.record(250L);
        this.timer.record(260L);
        this.timer.record(270L);
        assertEquals(3, this.timer.samples());
        ActionLedger.note("FakeLag", "held-send");
        this.sessions.worldLoaded(false);

        this.sessions.joined("mc.hypixel.net");
        SessionEvent start = this.recorder.raw.get(this.recorder.raw.size() - 1);
        assertEquals(SessionEvent.Type.START, start.getType());
        assertTrue(start.serverChanged());
        assertEquals("latency samples are per server", 0, this.timer.samples());
        assertTrue("blame is per connection", ActionLedger.within(60000L).isEmpty());
        assertEquals(2L, ServerSession.current().id());
    }

    @Test
    public void reconnectToTheSameServerKeepsServerStateButNotConnectionState() {
        this.sessions.joined("mc.hypixel.net");
        this.timer.record(80L);
        this.timer.record(90L);
        this.timer.record(100L);
        ActionLedger.note("Blink", "held-send");
        this.sessions.worldLoaded(false);

        this.sessions.joined("MC.HYPIXEL.NET:25565");
        SessionEvent start = this.recorder.raw.get(this.recorder.raw.size() - 1);
        assertEquals(SessionEvent.Type.START, start.getType());
        assertFalse("same server", start.serverChanged());
        assertEquals("route unchanged: samples kept, on purpose", 3, this.timer.samples());
        assertTrue("a new connection starts with no blame", ActionLedger.within(60000L).isEmpty());
        assertEquals(2L, ServerSession.current().id());
    }

    @Test
    public void joinForAnotherServerWhileOpenEndsTheOldSessionFirst() {
        this.sessions.joined("a.example");
        this.sessions.joined("b.example");
        assertEquals(java.util.Arrays.asList(
                "START a.example changed",
                "END a.example",
                "START b.example changed"), events());
        assertEquals("b.example", ServerSession.current().serverKey());
    }

    @Test
    public void currentAndLastServerAreSeparate() {
        assertNull(ServerSession.currentServerKey());
        assertNull(ServerSession.lastServerKey());
        this.sessions.joined("a.example");
        assertEquals("a.example", ServerSession.currentServerKey());
        assertEquals("a.example", ServerSession.lastServerKey());
        this.sessions.worldLoaded(false);
        assertNull(ServerSession.currentServerKey());
        assertEquals("a.example", ServerSession.lastServerKey());
        this.sessions.joined("b.example");
        assertEquals("b.example", ServerSession.currentServerKey());
        assertEquals("b.example", ServerSession.lastServerKey());
    }

    @Test
    public void aJoinQueuedBeforeADisconnectIsDropped() {
        /* The packet arrives, the player disconnects in the same tick, then
           the queued join runs: it must not open a session. */
        Runnable queued = this.sessions.joinArrived("a.example");
        this.sessions.worldLoaded(false);
        queued.run();
        assertNull(ServerSession.current());
        assertEquals(1, ServerSession.staleJoins());
        assertTrue("no START, no END", events().isEmpty());
    }

    @Test
    public void queuedJoinsOnOneConnectionRunInOrder() {
        /* Two joins queued before either runs: the second is a proxy hop. */
        Runnable first = this.sessions.joinArrived("mc.hypixel.net");
        Runnable second = this.sessions.joinArrived("mc.hypixel.net");
        first.run();
        second.run();
        assertEquals(1L, ServerSession.current().id());
        assertEquals(2, ServerSession.current().joins());
        assertEquals(0, ServerSession.staleJoins());
    }

    @Test
    public void aStaleJoinDoesNotDisturbTheNextConnection() {
        Runnable stale = this.sessions.joinArrived("a.example");
        this.sessions.worldLoaded(false);          // disconnect
        Runnable fresh = this.sessions.joinArrived("b.example");
        stale.run();
        fresh.run();
        assertEquals("b.example", ServerSession.current().serverKey());
        assertEquals(1, ServerSession.staleJoins());
        assertEquals(java.util.Arrays.asList("START b.example changed"), events());
    }

    @Test
    public void reconnectAfterAStaleJoinStillCountsAsTheSameServer() {
        this.sessions.joinArrived("a.example").run();
        this.sessions.worldLoaded(false);
        Runnable stale = this.sessions.joinArrived("a.example");
        this.sessions.worldLoaded(false);
        stale.run();
        this.sessions.joinArrived("a.example").run();
        SessionEvent start = this.recorder.raw.get(this.recorder.raw.size() - 1);
        assertEquals(SessionEvent.Type.START, start.getType());
        assertFalse("same server as last time", start.serverChanged());
    }

    @Test
    public void singleplayerIsAServerLikeAnyOther() {
        this.sessions.joined(null);
        assertEquals(ServerSession.SINGLEPLAYER, ServerSession.current().serverKey());
        this.sessions.worldLoaded(false);
        this.sessions.joined("mc.hypixel.net");
        assertTrue(this.recorder.raw.get(this.recorder.raw.size() - 1).serverChanged());
    }
}
