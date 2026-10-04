package myau.management;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

/**
 * Grim's DuplicateRotPlace memory, kept from the packets this client sends
 * (2026-10-04).
 *
 * Grim (experimental check): a placement is judged after the next flying
 * packet; if a rotation arrived since the last judged placement, its yaw step
 * |yaw - previous yaw| is compared with the step at the last judged
 * placement, and equal steps above 2 degrees flag ("x=0.0"). Both numbers are
 * the server's, from every placement and every look -- not one module's.
 *
 * Clutch and AutoBlockIn each kept their own "turn at my last placement" and
 * nudged a repeat by one mouse count. That missed whenever the server's
 * memory differed from theirs: a placement on a tick whose movement packet
 * carried no look (the module still recorded a stale turn), the player's own
 * clicks between two of the module's, or a look the module did not send
 * (19:55:31, Clutch bridging, x=0.0). This follows the packets instead.
 *
 * Fed at PacketEvent SEND (lowest priority, not cancelled): the order packets
 * are generated in is the order the server receives them, holds included, and
 * a held packet released later is not counted twice.
 */
public final class PlaceRotations {

    /** The state machine on its own, for tests. */
    public static final class State {
        private float lastYaw = Float.NaN;
        private float deltaX;
        private boolean rotated;
        private int pendingPlaces;
        private float lastPlacedDeltaX = -1.0F;

        /** A block placement went out. */
        public synchronized void place() {
            this.pendingPlaces++;
        }

        /** A movement packet went out; {@code rotating} with its yaw. */
        public synchronized void flying(boolean rotating, float yaw) {
            if (rotating) {
                if (!Float.isNaN(this.lastYaw)) {
                    this.deltaX = Math.abs(yaw - this.lastYaw);
                    this.rotated = true;
                }
                this.lastYaw = yaw;
            }
            if (this.pendingPlaces > 0) {
                if (this.rotated) {
                    this.lastPlacedDeltaX = this.deltaX;
                    this.rotated = false;
                }
                this.pendingPlaces = 0;
            }
        }

        /**
         * Whether placing before a movement packet that carries this yaw would
         * repeat the last judged placement's yaw step (above 2 degrees).
         */
        public synchronized boolean wouldDuplicate(float yaw) {
            if (Float.isNaN(this.lastYaw) || this.lastPlacedDeltaX < 0.0F) {
                return false;
            }
            float step = Math.abs(yaw - this.lastYaw);
            return step > 2.0F && Math.abs(step - this.lastPlacedDeltaX) < 1.0E-3F;
        }

        public synchronized void reset() {
            this.lastYaw = Float.NaN;
            this.rotated = false;
            this.pendingPlaces = 0;
            this.lastPlacedDeltaX = -1.0F;
        }
    }

    private static final State STATE = new State();

    /** See State.wouldDuplicate, against what this client has actually sent. */
    public static boolean wouldDuplicate(float yaw) {
        return STATE.wouldDuplicate(yaw);
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            if (((C08PacketPlayerBlockPlacement) event.getPacket()).getPlacedBlockDirection() != 255) {
                STATE.place();
            }
        } else if (event.getPacket() instanceof C03PacketPlayer) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
            STATE.flying(packet.getRotating(), packet.getYaw());
        }
    }

    /** A new world (or server): the server's memory of this player starts over. */
    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        STATE.reset();
    }
}
