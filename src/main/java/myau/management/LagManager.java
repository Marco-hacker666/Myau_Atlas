package myau.management;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.util.ActionLedger;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.util.Vec3;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class LagManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final Deque<LagPacket> packetQueue;
    /* Written on the client thread, read on the network thread too (the
       keep-alive reply passes through handlePacket there). */
    private volatile int tickDelay;
    /**
     * The thread inside flushQueue, or null.
     *
     * isFlushing() exists so the packets this class sends while flushing are
     * not offered straight back to it by the mixin. It used to be one flag for
     * everyone: while the network thread flushed (a keep-alive reply arriving),
     * a movement packet the client thread sent in that moment saw "flushing",
     * skipped both the blink and the lag hold, and went out ahead of the
     * packets still queued. Only the flushing thread's own sends are exempt now.
     */
    private volatile Thread flushingThread;
    /** The module that last asked for a delay (F-11, 2026-09-28). */
    private volatile String holder = "LagManager";
    private volatile Vec3 lastPosition;

    public LagManager() {
        this.packetQueue = new ConcurrentLinkedDeque<>();
        this.tickDelay = 0;
        this.lastPosition = new Vec3(0.0, 0.0, 0.0);
        /* What is held, for PacketHolds (plan step 13). */
        PacketHolds.register(() -> {
            int count = this.packetQueue.size();
            if (count == 0) {
                return null;
            }
            LagPacket head = this.packetQueue.peek();
            return new PacketHolds.Hold(this.holder, PacketHolds.Direction.OUT, count,
                    head == null ? 0L : head.at, "delay " + this.tickDelay + " ticks",
                    "each after its delay; all on a catch or a join");
        }, new PacketHolds.Lease() {
            /* The delay is the holder's, and lasts until it sets 0 -- which
               a holder switched off without doing so never does. */
            @Override
            public String owner() {
                String holder = LagManager.this.holder;
                return "LagManager".equals(holder) ? null : holder;
            }

            /* Each packet leaves once it is tickDelay ticks old; a second
               beyond that and the flush is not happening. */
            @Override
            public long ceilingMs() {
                return LagManager.this.tickDelay * 50L + PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                LagManager.this.tickDelay = 0;
                flushQueue(true);
            }
        });
    }

    private void flushQueue() {
        flushQueue(false);
    }

    /**
     * Sends whatever is due -- everything, when {@code all}.
     *
     * Synchronized (F-28, 2026-09-28). This runs on the network thread as
     * well as the client thread: vanilla answers a keep-alive from inside the
     * packet handler, without moving to the client thread, and that reply
     * comes through handlePacket, which flushes first. The loop is peek, send,
     * poll; two threads in it at once could both send the head and then poll
     * two entries -- one packet sent twice, the next never sent.
     */
    private synchronized void flushQueue(boolean all) {
        if (!connected()) {
            this.packetQueue.clear();
            return;
        }
        this.flushingThread = Thread.currentThread();
        int sent = 0;
        try {
            for (; !this.packetQueue.isEmpty(); this.packetQueue.poll()) {
                LagPacket lagPacket = this.packetQueue.peek();
                if (!all && this.tickDelay > 0 && lagPacket.delay <= this.tickDelay) {
                    break;
                }
                send(lagPacket.packet);
                sent++;
                if (lagPacket.packet instanceof C03PacketPlayer) {
                    C03PacketPlayer c03 = (C03PacketPlayer) lagPacket.packet;
                    if (c03.isMoving()) {
                        this.lastPosition = new Vec3(c03.getPositionX(), c03.getPositionY(), c03.getPositionZ());
                    }
                }
            }
        } finally {
            this.flushingThread = null;
            /* The release, stamped when it happens (plan step 12). */
            if (sent > 0) {
                ActionLedger.note(this.holder, "release", sent);
            }
        }
    }

    /** Whether there is a connection to send on. Overridden by tests. */
    protected boolean connected() {
        return mc != null && mc.getNetHandler() != null;
    }

    /** Sends one held packet past the hooks. Overridden by tests. */
    protected void send(Packet<?> packet) {
        PacketUtil.sendPacketNoEvent(packet);
    }

    private synchronized void incrementDelays() {
        this.packetQueue.forEach(z -> z.delay++);
    }

    public boolean handlePacket(Packet<?> packet) {
        if (Arbiter.catching()) {
            /* A catch is on (see Arbiter): everything held goes now, and
               nothing more is held until it is over. */
            if (!this.packetQueue.isEmpty()) {
                /* Everything, regardless of the delay -- without setting the
                   delay to zero and back, which from the network thread could
                   overwrite a delay the client thread set in between. */
                this.flushQueue(true);
                Arbiter.yielded("LagManager");
            }
            return false;
        }
        this.flushQueue();
        if (packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if ((long) this.tickDelay > 0L) {
            /* Same blind spot as the blink path: this swallows the packet
               through the mixin rather than by cancelling an event, so without
               saying so here nothing that withholds packets through the lag
               manager could ever be blamed for the corrections it causes.
               
               Named after the module that set the delay (see setDelay). */
            ActionLedger.note(this.holder, "held-send");
            this.packetQueue.offer(new LagPacket(packet));
            return true;
        } else {
            if (packet instanceof C03PacketPlayer) {
                C03PacketPlayer c03 = (C03PacketPlayer) packet;
                if (c03.isMoving()) {
                    this.lastPosition = new Vec3(c03.getPositionX(), c03.getPositionY(), c03.getPositionZ());
                }
            }
            return false;
        }
    }

    /**
     * Sets how many ticks packets are held; 0 stops holding.
     *
     * The holder used to be guessed from a list of modules that were switched
     * on -- LagRange, ServerLag, FakeLag, Blink -- three of which never use
     * this class, while BlockHit, which does, was missing (F-11). It is now
     * whoever actually set a delay: the calling module, found on the stack.
     * Looked up only when a non-zero delay changes, not on every call.
     */
    public void setDelay(int delay) {
        if (delay > 0 && delay != this.tickDelay) {
            String caller = ActionLedger.callerModule();
            if (caller != null) {
                this.holder = caller;
            }
        }
        this.tickDelay = delay;
    }

    /** The module that last asked for a delay; "LagManager" if unknown. */
    public String holder() {
        return this.holder;
    }

    public int getDelay() {
        return this.tickDelay;
    }

    public Vec3 getLastPosition() {
        return this.lastPosition;
    }

    /** True only on the thread currently flushing: its own sends pass untouched. */
    public boolean isFlushing() {
        return this.flushingThread == Thread.currentThread();
    }

    /**
     * A new world: the queue is the old one's. Delayed movement, clicks and
     * attacks from there were flushed into the new world as they came due.
     * Dropped now, transaction replies sent in order when there is a
     * connection; the holder keeps its delay for the new world (2026-10-04).
     */
    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        dropForWorldChange(event.getWorld() != null);
    }

    /** See onWorldLoad. sendReplies false (leaving) drops everything. */
    public synchronized void dropForWorldChange(boolean sendReplies) {
        LagPacket lagPacket;
        while ((lagPacket = this.packetQueue.poll()) != null) {
            if (sendReplies && lagPacket.packet instanceof net.minecraft.network.play.client.C0FPacketConfirmTransaction
                    && connected()) {
                send(lagPacket.packet);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            if (mc.thePlayer.isDead) {
                this.setDelay(0);
            }
            this.incrementDelays();
            this.flushQueue();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
                || event.getPacket() instanceof C00PacketServerQuery
                || event.getPacket() instanceof C01PacketPing
                || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.setDelay(0);
        }
    }

    public static class LagPacket {
        public final Packet<?> packet;
        public int delay;
        /** When it was taken (PacketHolds). */
        public final long at = System.currentTimeMillis();

        public LagPacket(Packet<?> packet) {
            this.packet = packet;
            this.delay = 0;
        }
    }
}
