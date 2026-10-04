package myau.management;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;

/**
 * Which kinds of action packet this client has already sent in the current
 * tick (2026-09-28, item 2 of the Rise comparison, docs/PAID-CLIENT-GAP.md;
 * Rise calls its version BadPacketsComponent).
 *
 * Anticheats check the combinations a hand cannot make inside one tick: an
 * attack in the same tick as a block placed or an inventory click, two slot
 * changes, a use and a release. Each module that sends actions used to have
 * no way of knowing what another had sent earlier in the tick -- the user's
 * own right click, AutoTool's slot change, InvManager's click -- so the
 * aura attacked straight after them. A module now asks here first.
 *
 * What counts is what left through the event bus, uncancelled, since the
 * start of the client tick (runTick HEAD). A packet swallowed by a hold is
 * not counted until it is sent. Client thread.
 */
public final class TickActions {

    public static final int SLOT = 1;
    public static final int ATTACK = 1 << 1;
    public static final int INTERACT = 1 << 2;
    public static final int SWING = 1 << 3;
    /** A block placed or an item used (C08). */
    public static final int USE = 1 << 4;
    /** Digging, including releasing a used item (C07). */
    public static final int DIG = 1 << 5;
    /** A window click, opening the inventory, or closing a window. */
    public static final int INVENTORY = 1 << 6;

    private static volatile int sent;
    private static volatile long refused;

    /** Whether any of these kinds was sent this tick. */
    public static boolean any(int kinds) {
        return (sent & kinds) != 0;
    }

    /** Every kind sent this tick, as flags. */
    public static int sent() {
        return sent;
    }

    /** Counts one action a module held back because of this tick's others. */
    public static void refuse() {
        refused++;
    }

    /** Actions held back for that reason since start. */
    public static long refusals() {
        return refused;
    }

    /** The kind flag for a packet; 0 for anything that is not an action. */
    static int kindOf(Packet<?> packet) {
        if (packet instanceof C09PacketHeldItemChange) {
            return SLOT;
        }
        if (packet instanceof C02PacketUseEntity) {
            return ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK ? ATTACK : INTERACT;
        }
        if (packet instanceof C0APacketAnimation) {
            return SWING;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return USE;
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return DIG;
        }
        if (packet instanceof C0EPacketClickWindow || packet instanceof C0DPacketCloseWindow) {
            return INVENTORY;
        }
        if (packet instanceof C16PacketClientStatus
                && ((C16PacketClientStatus) packet).getStatus() == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT) {
            return INVENTORY;
        }
        return 0;
    }

    /** A packet that left. Package-private for tests. */
    static void noteSent(Packet<?> packet) {
        int kind = kindOf(packet);
        if (kind != 0) {
            sent |= kind;
        }
    }

    /** The start of a tick. Package-private for tests. */
    static void newTick() {
        sent = 0;
    }

    /** Listens for the tick and for what is sent. */
    public static final class Listener {
        @EventTarget(Priority.HIGHEST)
        public void onTick(TickEvent event) {
            if (event.getType() == EventType.PRE) {
                newTick();
            }
        }

        /* LOWEST: after every module that might cancel it. */
        @EventTarget(Priority.LOWEST)
        public void onPacket(PacketEvent event) {
            if (event.getType() == EventType.SEND && !event.isCancelled()) {
                noteSent(event.getPacket());
            }
        }
    }

    /** Test support only. */
    static void resetForTests() {
        sent = 0;
        refused = 0L;
    }

    private TickActions() {
    }
}
