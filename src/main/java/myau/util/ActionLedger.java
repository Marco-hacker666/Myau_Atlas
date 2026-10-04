package myau.util;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A running record of which module did what, kept for the last few seconds.
 *
 * Blame used to be a list. {@code FlagDetector} knew about eight modules by
 * name, asked each of them a question written for it, and said nothing about
 * the rest -- so a correction caused by anything not on the list came out as
 * an unattributed flag, and a module added afterwards was invisible until
 * someone remembered to extend the list. That is the wrong shape for the
 * question being asked, which is not "was it one of these eight" but "what was
 * running when this happened".
 *
 * Nothing here is written per module. Two generic points feed it:
 *
 *   CANCELLATION. Whoever stops a packet is identified by the dispatcher, at
 *   the moment it happens, from the listener that did it. Withholding packets
 *   is the single most common cause of a correction, and this catches every
 *   module that does it -- including ones that do it through a shared manager,
 *   and ones written after this was.
 *
 *   INJECTION. A packet the client sends from inside a module rather than from
 *   the game loop is found by looking at who is on the stack. Only the packets
 *   worth attributing are examined: movement is sent every tick by vanilla and
 *   would be nothing but noise, while an attack, a placement or a dig that a
 *   module produced by itself is exactly what is being looked for.
 *
 * The record is evidence of coincidence, not of cause. Two modules acting in
 * the same tick both appear, and it is the count over many flags -- not one
 * line -- that says which one is responsible.
 */
public final class ActionLedger {

    /** Entries older than this are dropped; nothing asks about them. */
    private static final long RETENTION_MS = 15000L;
    private static final int CAP = 512;

    private static final ArrayDeque<Record> ENTRIES = new ArrayDeque<Record>();
    private static final Object LOCK = new Object();

    private ActionLedger() {
    }

    /** One action: who, what, when, and how many packets it stood for. */
    public static final class Record {
        public final long at;
        public final String module;
        public final String kind;
        public final int count;

        Record(String module, String kind, int count) {
            this.at = System.currentTimeMillis();
            this.module = module;
            this.kind = kind;
            this.count = count;
        }
    }

    public static void note(String module, String kind) {
        note(module, kind, 1);
    }

    /** One action standing for {@code count} packets (a released queue, say). */
    public static void note(String module, String kind, int count) {
        if (module == null || count <= 0) {
            return;
        }
        synchronized (LOCK) {
            ENTRIES.addLast(new Record(module, kind, count));
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            while (!ENTRIES.isEmpty()
                    && (ENTRIES.size() > CAP || ENTRIES.peekFirst().at < cutoff)) {
                ENTRIES.removeFirst();
            }
        }
    }

    /**
     * The module a packet came from, or null when the game loop sent it.
     *
     * Walking the stack is the only way to answer this without every module
     * being changed to announce itself, which is the coupling this exists to
     * remove. It is reached for a few packets a second at most.
     */
    public static String callerModule() {
        return callerModuleExcept(null);
    }

    /**
     * callerModule() for a module that is itself on the stack -- a listener
     * asking who sent the packet it is looking at -- skipping its own frames.
     */
    public static String callerModuleExcept(String self) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (!name.startsWith("myau.module.modules.")) {
                continue;
            }
            String simple = name.substring("myau.module.modules.".length());
            /* Inner classes belong to the module that owns them. */
            int inner = simple.indexOf('$');
            String module = inner < 0 ? simple : simple.substring(0, inner);
            if (module.equals(self)) {
                continue;
            }
            return module;
        }
        return null;
    }

    /**
     * Packets worth attributing: the ones a module sends deliberately. Movement
     * is excluded because vanilla sends it every tick regardless.
     */
    public static boolean isAttributable(Packet<?> packet) {
        return packet instanceof C02PacketUseEntity
                || packet instanceof C07PacketPlayerDigging
                || packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C0APacketAnimation
                || packet instanceof C0BPacketEntityAction
                || packet instanceof C13PacketPlayerAbilities;
    }

    /**
     * Whether stopping this packet could plausibly move the player, or delay
     * the stream that carries the things that do.
     *
     * Without this the ledger reports whoever cancels the most packets, which
     * is not the same as whoever causes the most corrections. A module that
     * suppresses the day/night cycle and the weather cancels two packets a
     * second and appeared on every single flag -- consistently, which is
     * exactly what makes a coincidence look like a cause.
     *
     * Named as a list of what cannot matter rather than what can: a packet
     * nobody thought about should count, and be argued out later, rather than
     * silently not counting because it was not on a list.
     */
    public static boolean cancelMatters(Packet<?> packet) {
        String name = packet.getClass().getSimpleName();
        for (String cosmetic : COSMETIC) {
            if (name.startsWith(cosmetic)) {
                return false;
            }
        }
        return true;
    }

    /** World time, weather, sound, chat, tab list, scoreboard, titles, effects. */
    private static final String[] COSMETIC = {
            "S03PacketTimeUpdate", "S2BPacketChangeGameState", "S29PacketSoundEffect",
            "S02PacketChat", "S38PacketPlayerListItem", "S3APacketTabComplete",
            "S3BPacketScoreboardObjective", "S3CPacketUpdateScore", "S3DPacketDisplayScoreboard",
            "S3EPacketTeams", "S45PacketTitle", "S47PacketPlayerListHeaderFooter",
            "S2APacketParticles", "S28PacketEffect", "S24PacketBlockAction",
            "S25PacketBlockBreakAnim", "S1CPacketEntityMetadata", "S04PacketEntityEquipment"
    };

    /**
     * What withholding this packet is (plan step 12): an outgoing packet is
     * this player's own action ("held-send"); an incoming one either moves
     * this player -- a correction, an explosion, knockback addressed to us --
     * ("held-self"), or only describes someone else ("held-recv"). The last
     * kind cannot move this player, and is weighed accordingly.
     */
    public static String holdKind(Packet<?> packet, boolean send) {
        if (send) {
            return "held-send";
        }
        if (packet instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook
                || packet instanceof net.minecraft.network.play.server.S27PacketExplosion) {
            return "held-self";
        }
        if (packet instanceof net.minecraft.network.play.server.S12PacketEntityVelocity) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null
                    && ((net.minecraft.network.play.server.S12PacketEntityVelocity) packet).getEntityID()
                    == mc.thePlayer.getEntityId()) {
                return "held-self";
            }
        }
        return "held-recv";
    }

    /** The records of the last {@code millis}, oldest first: a snapshot. */
    public static java.util.List<Record> records(long millis) {
        long cutoff = System.currentTimeMillis() - millis;
        java.util.List<Record> out = new java.util.ArrayList<Record>();
        synchronized (LOCK) {
            for (Record record : ENTRIES) {
                if (record.at >= cutoff) {
                    out.add(record);
                }
            }
        }
        return out;
    }

    public static String kindOf(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity) {
            return "attack";
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return "dig";
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return "place";
        }
        if (packet instanceof C0APacketAnimation) {
            return "swing";
        }
        if (packet instanceof C0BPacketEntityAction) {
            return "action";
        }
        return "packet";
    }

    /** Modules that acted within the window, with how many actions each. */
    public static Map<String, Integer> within(long millis) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        long cutoff = System.currentTimeMillis() - millis;
        synchronized (LOCK) {
            for (Record entry : ENTRIES) {
                if (entry.at < cutoff) {
                    continue;
                }
                Integer seen = counts.get(entry.module);
                counts.put(entry.module, seen == null ? entry.count : seen + entry.count);
            }
        }
        return counts;
    }

    /** The most active modules in the window, as a short readable line. */
    public static String summary(long millis, int max) {
        Map<String, Integer> counts = within(millis);
        if (counts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int printed = 0;
        while (printed < max && !counts.isEmpty()) {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            if (best == null) {
                break;
            }
            counts.remove(best);
            if (printed > 0) {
                sb.append(' ');
            }
            sb.append(best).append('x').append(bestCount);
            printed++;
        }
        return sb.toString();
    }

    /** Distinct kinds one module was seen doing, for an explanation line. */
    public static String kindsFor(String module, long millis) {
        StringBuilder sb = new StringBuilder();
        long cutoff = System.currentTimeMillis() - millis;
        synchronized (LOCK) {
            Iterator<Record> it = ENTRIES.iterator();
            while (it.hasNext()) {
                Record entry = it.next();
                if (entry.at < cutoff || !module.equals(entry.module)) {
                    continue;
                }
                if (sb.indexOf(entry.kind) < 0) {
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(entry.kind);
                }
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            ENTRIES.clear();
        }
    }
}
