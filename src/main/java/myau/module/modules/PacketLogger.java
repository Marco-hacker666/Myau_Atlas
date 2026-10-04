package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S40PacketDisconnect;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the packet stream so a disconnect can be explained after the fact.
 *
 * A kick ends the session before anything printed to chat can be read, so the
 * recent traffic is held in a ring buffer and written to disk the moment a
 * disconnect packet arrives. The file is what survives; chat output is only
 * for watching live.
 *
 * Detail is extracted per packet type rather than logging class names alone,
 * because "sent 40 C08s" says nothing while "40 C08s at the same block in 6
 * ticks" identifies the module responsible.
 */
public class PacketLogger extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public final ModeProperty direction = new ModeProperty("direction", 0, new String[]{"SEND", "RECEIVE", "BOTH"});
    public final IntProperty bufferSize = new IntProperty("buffer", 600, 50, 4000);
    public final TextProperty filter = new TextProperty("filter", "");
    public final BooleanProperty onDisconnect = new BooleanProperty("dump-on-kick", true);
    /* A proxy network does not disconnect you when a child server kicks you:
       it moves you to the lobby, so no disconnect packet ever arrives. The
       chat line is the only thing that reliably marks the moment, and the
       join-game packet marks the server switch that follows it. */
    public final TextProperty triggerText = new TextProperty("dump-on-text", "Invalid packets");
    public final BooleanProperty onServerSwitch = new BooleanProperty("dump-on-switch", true);
    public final KeyProperty dumpKey = new KeyProperty("dump-key", org.lwjgl.input.Keyboard.KEY_NONE);
    public final BooleanProperty chatSummary = new BooleanProperty("chat-summary", true);
    public final IntProperty summarySeconds = new IntProperty("summary-seconds", 15, 3, 120);
    public final BooleanProperty countOnly = new BooleanProperty("count-only", false);

    private final ArrayDeque<String> buffer = new ArrayDeque<String>();
    private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
    private long startedAt;
    private long nextSummaryAt;
    private int total;
    private long lastManualDump;

    public PacketLogger() {
        super("PacketLogger", false, true, "Records outgoing packets and dumps them to a file on kick");
    }

    @Override
    public void onEnabled() {
        buffer.clear();
        counts.clear();
        total = 0;
        startedAt = System.currentTimeMillis();
        nextSummaryAt = startedAt + summarySeconds.getValue() * 1000L;
        ChatUtil.sendFormatted("&7[&bPacketLogger&7] recording -> &f" + LOG_DIR.getPath());
    }

    @Override
    public void onDisabled() {
        dump("module disabled");
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        boolean send = event.getType() == EventType.SEND;

        /* Kick detection runs whatever direction is being recorded: it is the
           only chance to write the buffer before the evidence rolls out of it. */
        if (!send) {
            if (packet instanceof S40PacketDisconnect && onDisconnect.getValue()) {
                record(false, packet, event.isCancelled());
                dump("DISCONNECTED: "
                        + ((S40PacketDisconnect) packet).getReason().getUnformattedText());
                return;
            }
            if (packet instanceof S02PacketChat) {
                String needle = triggerText.getValue();
                if (needle != null && !needle.isEmpty()) {
                    String text = ((S02PacketChat) packet).getChatComponent().getUnformattedText();
                    if (text != null && text.toLowerCase().contains(needle.toLowerCase())) {
                        record(false, packet, event.isCancelled());
                        dump("CHAT MATCH: " + text.trim());
                        return;
                    }
                }
            }
            if (packet instanceof S01PacketJoinGame && onServerSwitch.getValue() && total > 0) {
                record(false, packet, event.isCancelled());
                dump("SERVER SWITCH (moved to another server)");
                return;
            }
        }

        int mode = direction.getValue();
        if (mode == 0 && !send) {
            return;
        }
        if (mode == 1 && send) {
            return;
        }
        record(send, packet, event.isCancelled());

        if (dumpKey.getValue() != org.lwjgl.input.Keyboard.KEY_NONE && dumpKey.isDown()
                && System.currentTimeMillis() - lastManualDump > 2000L) {
            lastManualDump = System.currentTimeMillis();
            dump("manual dump");
        }

        long now = System.currentTimeMillis();
        if (chatSummary.getValue() && now >= nextSummaryAt) {
            nextSummaryAt = now + summarySeconds.getValue() * 1000L;
            ChatUtil.sendFormatted("&7[&bPacketLogger&7] " + topCounts(4) + " &8(" + total + " total)");
        }
    }

    private void record(boolean send, Packet<?> packet, boolean cancelled) {
        String name = packet.getClass().getSimpleName();
        String needle = filter.getValue();
        if (needle != null && !needle.isEmpty()
                && !name.toLowerCase().contains(needle.toLowerCase())) {
            return;
        }

        total++;
        Integer seen = counts.get(name);
        counts.put(name, seen == null ? 1 : seen + 1);

        if (countOnly.getValue()) {
            return;
        }
        String line = STAMP.format(new Date()) + (send ? "  >>  " : "  <<  ") + name
                + (cancelled ? " [cancelled]" : "") + "  " + describe(packet);
        buffer.addLast(line);
        while (buffer.size() > bufferSize.getValue()) {
            buffer.removeFirst();
        }
    }

    /** Per-type detail, limited to the fields that identify a bad packet. */
    private String describe(Packet<?> packet) {
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement p = (C08PacketPlayerBlockPlacement) packet;
            return "pos=" + p.getPosition() + " face=" + p.getPlacedBlockDirection()
                    + " item=" + (p.getStack() == null ? "null" : p.getStack().getDisplayName())
                    + " hit=" + round(p.getPlacedBlockOffsetX()) + "," + round(p.getPlacedBlockOffsetY())
                    + "," + round(p.getPlacedBlockOffsetZ());
        }
        if (packet instanceof C09PacketHeldItemChange) {
            return "slot=" + ((C09PacketHeldItemChange) packet).getSlotId();
        }
        if (packet instanceof C07PacketPlayerDigging) {
            C07PacketPlayerDigging p = (C07PacketPlayerDigging) packet;
            return "action=" + p.getStatus() + " pos=" + p.getPosition() + " face=" + p.getFacing();
        }
        if (packet instanceof C02PacketUseEntity) {
            C02PacketUseEntity p = (C02PacketUseEntity) packet;
            return "action=" + p.getAction();
        }
        if (packet instanceof C0BPacketEntityAction) {
            return "action=" + ((C0BPacketEntityAction) packet).getAction();
        }
        if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer p = (C03PacketPlayer) packet;
            return "onGround=" + p.isOnGround() + " moving=" + p.isMoving()
                    + " pos=" + round(p.getPositionX()) + "," + round(p.getPositionY()) + "," + round(p.getPositionZ())
                    + " look=" + round(p.getYaw()) + "," + round(p.getPitch());
        }
        if (packet instanceof C0DPacketCloseWindow) {
            return "closeWindow";
        }
        if (packet instanceof C0EPacketClickWindow) {
            C0EPacketClickWindow p = (C0EPacketClickWindow) packet;
            return "window=" + p.getWindowId() + " slot=" + p.getSlotId() + " mode=" + p.getMode();
        }
        if (packet instanceof C16PacketClientStatus) {
            return "status=" + ((C16PacketClientStatus) packet).getStatus();
        }
        if (packet instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) packet;
            return "to=" + round(p.getX()) + "," + round(p.getY()) + "," + round(p.getZ());
        }
        if (packet instanceof S02PacketChat) {
            return "chat";
        }
        return "";
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String topCounts(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            if (i > 0) {
                sb.append("&8, &7");
            }
            sb.append(entries.get(i).getKey().replaceAll("Packet.*", "")).append(" &fx")
                    .append(entries.get(i).getValue()).append("&7");
        }
        return sb.toString();
    }

    /** Writes the ring buffer and the per-type totals to a timestamped file. */
    public void dump(String reason) {
        if (total == 0) {
            return;
        }
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            File out = new File(LOG_DIR, "packetlog-" + FILE_STAMP.format(new Date()) + ".txt");
            writer = new PrintWriter(new FileWriter(out));
            writer.println("reason   : " + reason);
            writer.println("recorded : " + total + " packets over "
                    + ((System.currentTimeMillis() - startedAt) / 1000) + "s");
            writer.println("direction: " + direction.getModeString());
            if (filter.getValue() != null && !filter.getValue().isEmpty()) {
                writer.println("filter   : " + filter.getValue());
            }
            writer.println();
            writer.println("--- totals by type ---");
            List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            for (Map.Entry<String, Integer> entry : entries) {
                writer.printf("%6d  %s%n", entry.getValue(), entry.getKey());
            }
            writer.println();
            writer.println("--- last " + buffer.size() + " packets (oldest first) ---");
            for (String line : buffer) {
                writer.println(line);
            }
            writer.flush();
            ChatUtil.sendFormatted("&7[&bPacketLogger&7] wrote &f" + out.getName()
                    + " &7(" + buffer.size() + " packets)");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{direction.getModeString(), String.valueOf(total)};
    }
}
