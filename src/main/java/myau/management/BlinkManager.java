package myau.management;

import myau.enums.BlinkModules;
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
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;

public class BlinkManager {
    public static Minecraft mc = Minecraft.getMinecraft();
    /* Volatile: set on the client thread, read by the send mixin on
       whichever thread sends (the keep-alive reply comes from the network
       thread). */
    public volatile BlinkModules blinkModule = BlinkModules.NONE;
    public volatile boolean blinking = false;
    public Deque<Packet<?>> blinkedPackets = new ConcurrentLinkedDeque<>();
    private volatile boolean releasing = false;
    /** When the current owner took the blink (PacketHolds). */
    private volatile long blinkSince;

    public BlinkManager() {
        PacketHolds.register(() -> {
            int count = this.blinkedPackets.size();
            if (count == 0) {
                return null;
            }
            String owner = this.blinkModule.moduleName();
            return new PacketHolds.Hold("Blink/" + owner, PacketHolds.Direction.OUT, count, this.blinkSince,
                    "blink", "until " + owner + " ends it; all on a catch");
        }, new PacketHolds.Lease() {
            @Override
            public String owner() {
                BlinkModules owner = blinkModule;
                return owner == BlinkModules.NONE ? null : owner.moduleName();
            }

            @Override
            public long ceilingMs() {
                return blinkModule.leaseMs();
            }

            @Override
            public void expire() {
                releaseAll();
            }
        });
    }

    /** Requests refused because another module already held the blink (F-32). */
    private volatile int refusals;
    private volatile String lastRefusal = "";

    /**
     * Sends one previously held packet without this manager taking it back.
     *
     * sendPacketNoEvent still passes through the mixin's blink check, and
     * while blinking is on that check offers the packet straight back to the
     * queue. Blink's graduated drain went through that path, so every packet
     * it "released" landed at the tail of the queue again: the queue never
     * shrank (78 -> 368 -> 1221 packets in one session's log), and a queue with
     * no C03 in it would spin the drain loop forever. The lag manager still
     * sees the packet, which is the composition that is wanted.
     */
    public void release(Packet<?> packet) {
        this.releasing = true;
        try {
            ActionLedger.note(this.blinkModule.moduleName(), "release");
            send(packet);
        } finally {
            this.releasing = false;
        }
    }

    /**
     * Whether transaction replies are held with everything else, in order.
     * Blink's hold-transactions (on by default there); false here, so tests start from the old rule.
     *
     * Pika was once seen disconnecting a client whose replies were late (see
     * offerPacket; that was with the drain bug, which held them for seconds
     * without end), and Grim reads a hold
     * whose replies keep flowing as a client sending too many movements for
     * the time that has passed ("Timer"), and a hold whose replies wait in
     * line with the movement as plain lag.
     */
    public static volatile BooleanSupplier holdTransactions = () -> false;

    public boolean offerPacket(Packet<?> packet) {
        if (this.releasing || this.blinkModule == BlinkModules.NONE || packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if (packet instanceof C0FPacketConfirmTransaction && holdTransactions.getAsBoolean()) {
            /* Held, from whichever thread sent it, so that no reply can overtake
               one already in the queue: replies out of order are a flag of their
               own (Grim TransactionOrder). This is what keeps Grim's clock for
               the client honest during a hold. Grim times the client by its
               replies; when they keep coming while the movement waits, the
               release is "a second of movement in a clock that says it has
               already been answered for" -- Timer x5 after every Blink release
               on test.ccbluex.net, 2026-10-02 22:10:49 and 22:10:55. Raven Alter
               queues every outgoing packet, replies included, in one ordered
               track, and is not flagged. */
            ActionLedger.note(this.blinkModule.moduleName(), "held-send");
            this.blinkedPackets.offer(packet);
            return true;
        } else if (!"Client thread".equals(Thread.currentThread().getName())) {
            /* Only what the game loop itself is sending gets held.
            
               A packet originating on the network thread is a reply the
               protocol owes the server -- a transaction confirmation, a
               handshake step -- and those are answers, not actions. Holding
               one does not delay a decision this client made; it leaves the
               server waiting on a reply it is entitled to, which is a far more
               visible thing than a player who stopped walking. Vape makes the
               same check by comparing against its tick executor's thread. */
            return false;
        } else if (packet instanceof C0FPacketConfirmTransaction) {
            /* Not held unless hold-transactions says so (above). The default,
               because of Pika:
            
               A transaction confirmation is not an action this client chose to
               take -- it is the reply the protocol owes the server for a ping
               it sent. Hold it and the server is left waiting on an answer it
               is entitled to, and 1.8 anticheats that time the client with
               these disconnect outright rather than flag: "Unexpected pong
               response -380 -366 window: 0" is that, with the numbers being
               the ids it gave up waiting for.
            
               The old condition only skipped them while the queue was empty,
               which is exactly backwards: it let them through when holding
               them would have been harmless and held them once a queue had
               built up, which is when the delay actually costs something. */
            return false;
        } else {
            /* Named to the ledger here, because nothing else can see it.
            
               The ledger learns who withheld a packet by watching a packet
               event go from not-cancelled to cancelled between two listeners.
               This path never touches that: the mixin cancels the callback
               directly, so every packet swallowed here was invisible to
               attribution. The practical result was that a Blink holding
               forty movement packets was never once named for the corrections
               that followed, while a module that merely happened to be
               cancelling something through the normal path was named every
               time -- the same mistaken-coincidence failure as before, one
               layer lower down. */
            ActionLedger.note(this.blinkModule.moduleName(), "held-send");
            this.blinkedPackets.offer(packet);
            return true;
        }
    }

    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (module == BlinkModules.NONE) {
            return false;
        }
        if (state) {
            if (Arbiter.catching()) {
                /* A catch is on: its clicks are judged against the movement
                   this would hold. See Arbiter. */
                Arbiter.yielded(module.name());
                return false;
            }
            if (this.blinkModule != BlinkModules.NONE && this.blinkModule != module) {
                /* First come, first served (F-32, 2026-09-28).

                   Taking over used to be silent: the new module became the
                   owner of everything already held, and the old one's
                   setBlinkState(false) then returned without releasing
                   anything -- it believed its packets had gone while they
                   sat in the queue until the other module happened to let
                   go. Now the module already holding keeps its hold, and the
                   request is refused (false, which AntiVoid already checks).

                   Modules that mean to take over still can, and do: Blink,
                   NoFall and Hitflick release the current owner explicitly
                   first -- setBlinkState(false, getBlinkingModule()) -- and
                   that is unchanged. */
                this.refusals++;
                this.lastRefusal = module.name() + " refused, " + this.blinkModule.name() + " holds";
                return false;
            }
            if (this.blinkModule != module) {
                this.blinkSince = System.currentTimeMillis();
            }
            this.blinkModule = module;
            this.blinking = true;
        } else {
            if(blinkModule != module){
                return false;
            }
            releaseAll();
        }
        return true;
    }

    /**
     * Ends the blink, whoever holds it, and sends everything held in order.
     * The owner's own setBlinkState(false), and the lease (PacketHolds) when
     * the owner never got there.
     */
    private void releaseAll() {
        BlinkModules module = this.blinkModule;
        this.blinking = false;
        /* The owner is cleared on every exit. The early return used to skip
           this whenever the queue was already empty, so getBlinkingModule()
           kept naming a module that had stopped blinking. */
        this.blinkModule = BlinkModules.NONE;
        if (!connected()) {
            /* Nothing to send them on; the previous loop threw here. */
            this.blinkedPackets.clear();
            return;
        }
        /* The release is what the server sees: the whole queue at once.
           Stamped now, when it happens (plan step 12). */
        if (!this.blinkedPackets.isEmpty()) {
            ActionLedger.note(module.moduleName(), "release", this.blinkedPackets.size());
        }
        for (Packet<?> blinkedPacket : blinkedPackets) {
            send(blinkedPacket);
        }
        this.blinkedPackets.clear();
    }

    /**
     * Throws the held movement away, as on a server correction or a panic,
     * but still sends the transaction replies among it, in order. A dropped
     * movement is a step the server never hears about; a dropped reply is an
     * answer it is owed and never gets. With hold-transactions off the queue
     * holds no replies and this is a plain clear.
     */
    public void discardHeld() {
        Packet<?> packet;
        while ((packet = this.blinkedPackets.poll()) != null) {
            if (packet instanceof C0FPacketConfirmTransaction && connected()) {
                release(packet);
            }
        }
    }

    public BlinkModules getBlinkingModule() {
        return this.blinkModule;
    }

    /** How many blink requests were refused because another module held it. */
    public int refusals() {
        return this.refusals;
    }

    /** The most recent refusal, e.g. "SCAFFOLD refused, ANTI_VOID holds"; "" if none. */
    public String lastRefusal() {
        return this.lastRefusal;
    }

    /** Whether there is a connection to send on. Overridden by tests. */
    protected boolean connected() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.getNetHandler() != null;
    }

    /** Sends one held packet past the event. Overridden by tests. */
    protected void send(Packet<?> packet) {
        PacketUtil.sendPacketNoEvent(packet);
    }

    public long countMovement() {
        return this.blinkedPackets.stream().filter(packet -> packet instanceof C03PacketPlayer).count();
    }

    public boolean isBlinking() {
        return blinking;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
                || event.getPacket() instanceof C00PacketServerQuery
                || event.getPacket() instanceof C01PacketPing
                || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.setBlinkState(false, this.blinkModule);
        }
    }

    /**
     * A new world -- a server switch behind a proxy, a dimension change, or
     * leaving: what is held describes the old one. Released, it was movement
     * in a world the server had already taken this player out of, sent into
     * the new one (Blink's own world-load handler switches it off, which
     * released everything; so did every other holder's). Dropped instead,
     * transaction replies still sent in order while there is a connection
     * (2026-10-04). First, before any module's own handler.
     */
    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        dropForWorldChange(event.getWorld() != null);
    }

    /** See onWorldLoad. sendReplies false (leaving) drops the replies too. */
    public void dropForWorldChange(boolean sendReplies) {
        this.blinking = false;
        this.blinkModule = BlinkModules.NONE;
        if (sendReplies) {
            discardHeld();
        } else {
            this.blinkedPackets.clear();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            if (mc.thePlayer.isDead) {
                this.setBlinkState(false, this.blinkModule);
            }
        }
    }
}
