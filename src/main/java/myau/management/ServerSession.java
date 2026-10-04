package myau.management;

import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.SessionEvent;
import myau.util.ActionLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.play.server.S01PacketJoinGame;

import java.util.Locale;

/**
 * One definition of "a connection to a server", for everything that keeps
 * state about one (Phase 2, docs/ARCH-AUDIT-2026-09-28.md).
 *
 * Before this, six components each decided on their own when the server had
 * changed: on the join packet, on the next attack, by polling the address
 * every tick, or never. So state from one server leaked into the next for as
 * long as the slowest of them took to notice -- HitTimer kept Pika's latency
 * on Hypixel until the first hit, LatencyGovernor kept its samples forever,
 * FlagDetector's blame carried over (F-12, F-34).
 *
 * The lifecycle:
 *   START -- the first join packet (S01) after a disconnect. Further S01 on
 *            the same connection are a proxy moving the player between its
 *            backends: the same session, counted as joins.
 *   WORLD -- a world loaded within the session.
 *   END   -- the world unloaded to nothing: disconnect, quit to the menu, or
 *            exit. Minecraft does this on every one of them.
 * A join for a different address while a session is open (which a normal
 * client never produces) ends the old session first.
 *
 * The server is identified by the address as typed, lower-cased, without the
 * default port: "mc.hypixel.net" and "MC.Hypixel.net:25565" are one server,
 * "hypixel.net" is another (the user's choice, 2026-09-28).
 *
 * Everything here changes on the client thread: the join packet arrives on the
 * network thread and is handed over, in order, ahead of vanilla's own handling
 * of it.
 */
public final class ServerSession {

    /** One connection. Identity and counters; nothing else lives here. */
    public static final class Session {
        private final long id;
        private final String serverKey;
        private final String address;
        private final long startedAt;
        private volatile int joins;
        private volatile int worlds;
        private volatile long endedAt;

        Session(long id, String serverKey, String address, long startedAt) {
            this.id = id;
            this.serverKey = serverKey;
            this.address = address;
            this.startedAt = startedAt;
        }

        public long id() {
            return this.id;
        }

        /** The server, normalised: lower case, no default port. "singleplayer" for a local world. */
        public String serverKey() {
            return this.serverKey;
        }

        /** The address exactly as the client had it. */
        public String address() {
            return this.address;
        }

        public long startedAt() {
            return this.startedAt;
        }

        /** Join packets on this connection: 1, plus one per proxy backend switch. */
        public int joins() {
            return this.joins;
        }

        /** Worlds loaded on this connection. */
        public int worlds() {
            return this.worlds;
        }

        /** 0 while open. */
        public long endedAt() {
            return this.endedAt;
        }

        public boolean isOpen() {
            return this.endedAt == 0L;
        }

        @Override
        public String toString() {
            return "#" + this.id + " " + this.serverKey;
        }
    }

    public static final String SINGLEPLAYER = "singleplayer";

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static volatile ServerSession instance;

    private long nextId = 1L;
    private volatile Session current;
    /** The most recent session's server, open or ended, to tell a reconnect from a switch. */
    private volatile String lastServerKey;
    /**
     * Bumped at every world unload. A join captures it where the packet
     * arrives (network thread) and is dropped if it changed before the join
     * runs on the client thread: the connection it belonged to is gone.
     */
    private volatile long generation;
    /** Joins dropped that way, for diagnostics. */
    private volatile int staleJoins;

    public ServerSession() {
        instance = this;
    }

    public static ServerSession get() {
        return instance;
    }

    /** The open session, or null when not connected. */
    public static Session current() {
        ServerSession self = instance;
        return self == null ? null : self.current;
    }

    /**
     * The server connected to now; null when not connected.
     *
     * Split (2026-09-28 reliability pass) from what used to be one
     * serverKey(), which answered with the last server after a disconnect --
     * a caller could not tell "connected to X" from "not connected, was on X".
     */
    public static String currentServerKey() {
        ServerSession self = instance;
        Session open = self == null ? null : self.current;
        return open == null ? null : open.serverKey;
    }

    /** The server of the most recent session, open or ended; null if none this run. */
    public static String lastServerKey() {
        ServerSession self = instance;
        return self == null ? null : self.lastServerKey;
    }

    /** Joins dropped because their connection ended before they ran. */
    public static int staleJoins() {
        ServerSession self = instance;
        return self == null ? 0 : self.staleJoins;
    }

    /** The one normalisation of a server address. */
    public static String keyFor(String address) {
        if (address == null || address.trim().isEmpty()) {
            return SINGLEPLAYER;
        }
        String key = address.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(":25565")) {
            key = key.substring(0, key.length() - ":25565".length());
        }
        while (key.endsWith(".")) {
            key = key.substring(0, key.length() - 1);
        }
        return key.isEmpty() ? SINGLEPLAYER : key;
    }

    // ------------------------------------------------------------- inputs

    /* HIGHEST: modules that also react to the join packet by scheduling work
       for the client thread (ServerFingerprint) find the session already
       started when theirs runs. */
    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S01PacketJoinGame)
                || mc == null) {
            return;
        }
        ServerData data = mc.getCurrentServerData();
        mc.addScheduledTask(joinArrived(data == null ? null : data.serverIP));
    }

    /**
     * A join packet arrived (network thread): the task to run on the client
     * thread, bound to the connection it arrived on.
     *
     * The race it closes: the packet arrives while the client thread is
     * mid-tick, the player disconnects in that same tick (world unloaded, no
     * session open yet -- nothing to end), and the queued join then runs
     * first thing next loop and opens a session for a connection that no
     * longer exists. That session stayed open until the next connect.
     */
    Runnable joinArrived(final String address) {
        final long bound = this.generation;
        return new Runnable() {
            @Override
            public void run() {
                if (generation != bound) {
                    staleJoins++;
                    return;
                }
                joined(address);
            }
        };
    }

    @EventTarget(Priority.HIGHEST)
    public void onLoadWorld(LoadWorldEvent event) {
        worldLoaded(!event.isUnload());
    }

    // ---------------------------------------------------------- lifecycle

    /** A join packet for this address was received (client thread). */
    void joined(String address) {
        String key = keyFor(address);
        Session open = this.current;
        if (open != null) {
            if (open.serverKey.equals(key)) {
                open.joins++;
                return;
            }
            end(open);
        }
        boolean changed = !key.equals(this.lastServerKey);
        Session session = new Session(this.nextId++, key, address == null ? SINGLEPLAYER : address,
                System.currentTimeMillis());
        session.joins = 1;
        this.current = session;
        this.lastServerKey = key;
        /* Blame is evidence about this connection only: whatever the last
           server objected to says nothing about this one. */
        ActionLedger.clear();
        EventManager.call(new SessionEvent(SessionEvent.Type.START, session, changed));
    }

    /** Minecraft.loadWorld: a world (true) or nothing (false). Client thread. */
    void worldLoaded(boolean world) {
        if (!world) {
            /* Every unload, session or not: any join still queued belongs to
               a connection that is over. */
            this.generation++;
        }
        Session open = this.current;
        if (open == null) {
            return;
        }
        if (world) {
            open.worlds++;
            EventManager.call(new SessionEvent(SessionEvent.Type.WORLD, open, false));
        } else {
            end(open);
        }
    }

    private void end(Session session) {
        if (this.current == session) {
            this.current = null;
        }
        session.endedAt = System.currentTimeMillis();
        EventManager.call(new SessionEvent(SessionEvent.Type.END, session, false));
    }
}
