package myau.events;

import myau.event.events.Event;
import myau.management.ServerSession;

/**
 * A server session began, loaded a world, or ended (see ServerSession).
 *
 * Always fired on the client thread. On START the world and the player may
 * not exist yet -- it runs just before vanilla handles the join packet.
 */
public class SessionEvent implements Event {

    public enum Type {
        /** A new connection to a server. */
        START,
        /** A world was loaded within the session (the first one, a dimension, a lobby). */
        WORLD,
        /** The session is over: disconnect, back to the menu, or exit. */
        END
    }

    private final Type type;
    private final ServerSession.Session session;
    private final boolean serverChanged;

    public SessionEvent(Type type, ServerSession.Session session, boolean serverChanged) {
        this.type = type;
        this.session = session;
        this.serverChanged = serverChanged;
    }

    public Type getType() {
        return this.type;
    }

    public ServerSession.Session getSession() {
        return this.session;
    }

    /**
     * On START: the server differs from the previous session's. State that
     * belongs to a server (latency samples, learned statistics) resets on
     * this; state that belongs to one connection resets on every START.
     * Always false for WORLD and END.
     */
    public boolean serverChanged() {
        return this.serverChanged;
    }
}
